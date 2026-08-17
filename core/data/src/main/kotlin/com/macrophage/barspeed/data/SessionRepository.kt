package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Everything the record flow needs to persist about a finished set. */
data class CompletedSet(
    val exerciseId: String,
    val exerciseName: String,
    val loadKg: Double,
    val plannedLoadKg: Double?,
    val plannedReps: Int?,
    /** Lifter-counted reps for sensorless sets; overrides the analysis count. */
    val manualReps: Int? = null,
    /** Timed sets (planks, carries): actual and planned hold/carry seconds. */
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    val tempo: String?,
    val targetMeanConVelMps: Double?,
    val velocityLossStopPct: Double?,
    val plannedRestS: Int?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val analysis: SetAnalysis,
    /**
     * The direction and sensor geometry [analysis] was produced with.
     *
     * Captured at set end rather than looked up later: the resolution combines
     * a plan's declarations with the built-in definition, and neither the plan
     * nor the built-in list is guaranteed to still say the same thing when the
     * session is exported.
     */
    val geometry: ResolvedGeometry? = null,
    val imuSamples: List<ImuSample>,
    val hrSamples: List<HrSample>,
    /** Spoken cues during the set, epoch-ms stamped for IMU cross-reference. */
    val voiceCues: List<VoiceCue> = emptyList(),
    /**
     * How the set was rated at the moment it ended, stored with the row rather
     * than updated onto it afterwards.
     *
     * The effort tile IS the end-set control, so the rating is part of the same
     * gesture that finished the set and is captured once. Writing it as a
     * second statement left a window in which the row existed rated as nothing:
     * a set the lifter tapped as failed would read as an unremarkable set if
     * anything went wrong between the two, and no screen can edit a set's
     * rating once the rest screen is gone.
     *
     * The defaults are the same values [SetRecordEntity] defaults to, so a set
     * recorded without a rating is stored exactly as it was before.
     */
    val rpe: Int? = null,
    val failed: Boolean = false,
    val warmup: Boolean = false,
)

class SessionRepository(
    private val sessionDao: SessionDao,
    private val exerciseDao: ExerciseDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val sessions: Flow<List<SessionEntity>> = sessionDao.observeSessions()

    suspend fun startSession(planName: String?, planSessionName: String?, startedAtMs: Long): Long =
        sessionDao.insertSession(
            SessionEntity(startedAtMs = startedAtMs, planName = planName, planSessionName = planSessionName),
        )

    /**
     * Store one finished set: the row, every raw stream belonging to it, and
     * the rating it ended with, in a single transactional DAO call.
     *
     * One call, not four. The row and its streams used to go in separately,
     * which left a set row in history whose gzipped IMU stream could be
     * missing -- the set reads as saved while the capture everything derived
     * stays recoverable from is gone. Nothing in the repository interleaves
     * with them now; whether the database commits them together is Room's
     * `@Transaction` on [SessionDao.insertSetWithStreams], which no test here
     * can observe.
     */
    suspend fun recordSet(sessionId: Long, orderIdx: Int, set: CompletedSet): Long {
        val hr = set.hrSamples.map { it.bpm }
        // setId is stamped by the DAO once the row exists; these carry a
        // placeholder until then.
        val streams = buildList {
            if (set.imuSamples.isNotEmpty()) {
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_IMU,
                        csvGzip = Gzip.compress(ImuCsv.encode(set.imuSamples)),
                        sampleRateHz = set.analysis.sampleRateHz,
                    ),
                )
            }
            if (set.hrSamples.isNotEmpty()) {
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_HRM,
                        csvGzip = Gzip.compress(HrCsv.encode(set.hrSamples)),
                    ),
                )
            }
            if (set.voiceCues.isNotEmpty()) {
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_CUES,
                        csvGzip = Gzip.compress(CueCsv.encode(set.voiceCues)),
                    ),
                )
            }
        }
        return sessionDao.insertSetWithStreams(
            SetRecordEntity(
                sessionId = sessionId,
                orderIdx = orderIdx,
                exerciseId = set.exerciseId,
                exerciseName = set.exerciseName,
                loadKg = set.loadKg,
                plannedLoadKg = set.plannedLoadKg,
                actualReps = set.manualReps ?: set.analysis.reps.size,
                repsManual = set.manualReps != null,
                plannedReps = set.plannedReps,
                actualDurationS = set.actualDurationS,
                plannedDurationS = set.plannedDurationS,
                side = set.side,
                rpe = set.rpe,
                failed = set.failed,
                warmup = set.warmup,
                tempo = set.tempo,
                targetMeanConVelMps = set.targetMeanConVelMps,
                velocityLossStopPct = set.velocityLossStopPct,
                plannedRestS = set.plannedRestS,
                startedAtMs = set.startedAtMs,
                endedAtMs = set.endedAtMs,
                analysisJson = json.encodeToString(SetAnalysis.serializer(), set.analysis),
                hrEndOfSetBpm = set.hrSamples.lastOrNull()?.bpm,
                hrAvgBpm = if (hr.isEmpty()) null else hr.average().toInt(),
                hrMaxBpm = hr.maxOrNull(),
            ),
            streams,
        )
    }

    /**
     * Close a session: stamp when it ended and summarise its heart rate.
     *
     * Written once per session or never. [SessionDao.updateSession] has one
     * caller and it is this function, so nothing corrects these four columns
     * afterwards, and a session that is already closed is therefore left exactly
     * as it is.
     *
     * That guard is not only about a duplicated end time. [hrvRmssdMs] is an
     * argument with a null default, copied onto the row unconditionally, so a
     * caller that omits it used to erase a stored HRV -- the one figure here
     * that cannot be rebuilt from anything durable, its input being R-R
     * intervals collected across the rest windows and held in memory in `:app`.
     * The heart-rate summary is recomputed from the set rows on every call, so a
     * later close would also replace a correct summary with one drawn from a set
     * list that has since changed.
     *
     * What it does not do, said plainly. Two callers already suspended inside
     * this function can both read a null end time before either writes, and that
     * is reachable: the rest screen's Finish button is an undebounced
     * `TextButton`, so two taps launch two coroutines. The guard closes the
     * sequential case, which is the common one; re-entry is refused in the
     * ViewModel, which is where the concurrent case is actually closed. A
     * `Mutex` here would be the wrong instrument for a residue whose whole harm
     * is an end time and an HRV differing by the gap between two taps.
     */
    suspend fun endSession(sessionId: Long, endedAtMs: Long, hrvRmssdMs: Double? = null) {
        val session = sessionDao.sessionById(sessionId) ?: return
        if (session.endedAtMs != null) return
        val sets = sessionDao.setsForSession(sessionId)
        val avg = sets.mapNotNull { it.hrAvgBpm }
        sessionDao.updateSession(
            session.copy(
                endedAtMs = endedAtMs,
                hrAvgBpm = if (avg.isEmpty()) null else avg.average().toInt(),
                hrMaxBpm = sets.mapNotNull { it.hrMaxBpm }.maxOrNull(),
                hrvRmssdMs = hrvRmssdMs,
            ),
        )
    }

    fun observeSession(id: Long): Flow<SessionEntity?> = sessionDao.observeSession(id)

    fun observeSets(sessionId: Long): Flow<List<SetRecordEntity>> = sessionDao.observeSetsForSession(sessionId)

    suspend fun session(id: Long): SessionEntity? = sessionDao.sessionById(id)

    suspend fun sets(sessionId: Long): List<SetRecordEntity> = sessionDao.setsForSession(sessionId)

    suspend fun rawStreams(setId: Long): List<RawStreamEntity> = sessionDao.rawStreamsForSet(setId)

    /** Rest-screen effort rating (RPE, failed, or warm-up) applied to the just-recorded set. */
    suspend fun rateSet(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) =
        sessionDao.updateRpe(setId, rpe, failed, warmup)

    /** Lifter correction of a miscounted (or uncounted) set's reps. */
    suspend fun overrideReps(setId: Long, reps: Int) = sessionDao.overrideReps(setId, reps)

    suspend fun deleteSession(id: Long) = sessionDao.deleteSession(id)

    fun decodeAnalysis(entity: SetRecordEntity): SetAnalysis? = try {
        json.decodeFromString(SetAnalysis.serializer(), entity.analysisJson)
    } catch (e: Exception) {
        null
    }

    /**
     * The geometry this set was analysed against, or null when the row does not
     * carry one.
     *
     * Null covers two cases and deliberately does not distinguish them: a row
     * written before the column existed, and a column that will not decode.
     * Both mean the same thing to a reader — the app cannot say how this set
     * was measured — and both must stay absent rather than fall back to a
     * plausible-looking default.
     */
    fun decodeGeometry(entity: SetRecordEntity): ResolvedGeometry? = try {
        entity.geometryJson?.let { json.decodeFromString(ResolvedGeometry.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** Seeded + user-defined exercises, id → definition. Unknown ids infer a kind from the name. */
    suspend fun exerciseById(id: String): ExerciseDef {
        ExerciseDef.seedById(id)?.let { return it }
        val custom = exerciseDao.byId(id)
        return if (custom != null) {
            ExerciseDef(
                custom.id,
                custom.displayName,
                StartPhase.valueOf(custom.startsWith),
                kind = ExerciseDef.inferKind(custom.id),
                isCustom = true,
                usesBarbell = ExerciseDef.inferBarbell(custom.id),
            )
        } else {
            ExerciseDef(
                id,
                id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                ExerciseDef.inferStartPhase(id),
                kind = ExerciseDef.inferKind(id),
                isCustom = true,
                usesBarbell = ExerciseDef.inferBarbell(id),
            )
        }
    }

    suspend fun ensureExerciseExists(id: String) {
        if (ExerciseDef.seedById(id) == null && exerciseDao.byId(id) == null) {
            exerciseDao.insert(
                CustomExerciseEntity(
                    id = id,
                    displayName = id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    startsWith = ExerciseDef.inferStartPhase(id).name,
                ),
            )
        }
    }
}
