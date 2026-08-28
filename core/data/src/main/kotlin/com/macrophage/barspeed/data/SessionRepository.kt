package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RecordedTimeZone
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
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
    /**
     * Timed sets (planks, carries): recorded and planned hold/carry seconds.
     *
     * `actualDurationS` is the prescription on a set that ran to its planned
     * end (#168), the measurement on one the lifter stopped, and the stated
     * figure on one corrected afterwards from the rest screen. This is the
     * object [SessionRepository.recordSet] fills [SetRecordEntity] from, so
     * the two carry the same three cases and neither says which one a given
     * row is in.
     */
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    val tempo: String?,
    val targetMeanConVelMps: Double?,
    val velocityLossStopPct: Double?,
    val plannedRestS: Int?,
    /**
     * The prep prescribed before this set, and the prep the caller handed the
     * voice guide, in whole seconds.
     *
     * Both null on a set that ran no voice guide -- see [SetRecordEntity.plannedPrepS],
     * which is the pair these two become. They have no default, deliberately:
     * a defaulted parameter is one a call site can silently stop passing, and
     * the RESOLVED prep exists only at recording time. Null is a legitimate
     * value here and has to be written out.
     */
    val plannedPrepS: Int?,
    val prepS: Int?,
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
    /**
     * Heart rate recorded during the rest window BEFORE this set, issue #90.
     *
     * Empty when nothing was captured -- no strap, or the first set of a
     * session begun without a READY window. Empty is not zero and is not a
     * measurement; it simply writes no stream.
     *
     * RAW, and that is a requirement rather than an incidental. These samples
     * must be every notification the strap sent, duplicates included, the same
     * way [hrSamples] is. Issue #81 de-duplicates the two ANALYSIS
     * accumulators and deliberately leaves the raw capture untouched, because
     * the raw capture is the only irreplaceable artifact and a later reader may
     * know more than this code does. A de-duplicated stream stored here would
     * look entirely plausible and would silently destroy the only data anyone
     * could ever use to measure #81's cost at resting heart rates.
     */
    val restHrSamples: List<HrSample> = emptyList(),
    /** Spoken cues during the set, epoch-ms stamped for IMU cross-reference. */
    val voiceCues: List<VoiceCue> = emptyList(),
    /**
     * The instants a rep was COUNTED during this set, epoch-ms, issue #158.
     *
     * Empty is the ordinary case and does NOT mean no rep was performed: marks
     * exist only where something counted them out loud -- the lifter's `+1
     * REP` tap or the guided cadence runner -- and a sensor-counted set
     * produces none at all. The segmenter's reps carry an ordinal index and no
     * instant, so nothing can supply these after the fact.
     */
    val repMarks: List<Long> = emptyList(),
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
    /**
     * How many accelerometers this set was armed with and which stream was
     * analysed, or null on the ordinary one-sensor set (#156).
     *
     * Null covers both "one sensor, one asked for" and "recorded by a build
     * that could not capture two". It does NOT cover a set that asked for two
     * and armed one -- that carries a declaration, because the ask is
     * recoverable from nothing else.
     */
    val sensors: RecordedSensors? = null,
    /**
     * The second accelerometer's capture, or null when there was not one.
     *
     * A role and its samples as ONE object rather than two parallel fields, so
     * that samples cannot exist without a label. The alternative -- a bare
     * `imuSamplesB` list beside a nullable role -- makes a state constructible
     * in which a full stream has no role, and every way out of that state is
     * bad: dropping it is silent data loss, and writing it with a guessed role
     * puts a fabricated provenance on a real capture. Making it unconstructible
     * is cheaper than choosing.
     *
     * [imuSamples] keeps its exact meaning: the ANALYSED stream, whatever role
     * it carries.
     */
    val secondary: SecondaryCapture? = null,
)

/**
 * The stream from the accelerometer that is not analysed, with the role that
 * identifies it.
 *
 * Non-null role by construction -- see [CompletedSet.secondary]. The samples
 * may still be empty, which is the armed-but-absent case: the unit was
 * declared, its battery was flat, and no row is written for it. That is a
 * different fact from the set not having been armed for it at all, and
 * [CompletedSet.sensors] is what keeps the two apart.
 */
data class SecondaryCapture(
    val role: SensorRole,
    val samples: List<ImuSample>,
)

class SessionRepository(
    private val sessionDao: SessionDao,
    private val exerciseDao: ExerciseDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val sessions: Flow<List<SessionEntity>> = sessionDao.observeSessions()

    /**
     * Open a session row.
     *
     * [timeZone] is the device's zone and UTC offset as the caller observed
     * them, and it has no default on purpose: a defaulted parameter is one a
     * call site can silently stop passing, and this is a fact that exists only
     * at recording time. Null is a legitimate value — it means the caller could
     * not establish one — but it has to be written out.
     */
    suspend fun startSession(
        planName: String?,
        planSessionName: String?,
        startedAtMs: Long,
        timeZone: RecordedTimeZone?,
    ): Long {
        return sessionDao.insertSession(
            SessionEntity(
                startedAtMs = startedAtMs,
                // Written as a pair or not at all. The columns are separately
                // nullable because Room has no other option, but a row carrying
                // an offset and no zone is a state nothing here produces.
                zoneId = timeZone?.id,
                utcOffsetMinutes = timeZone?.utcOffsetMinutes,
                planName = planName,
                planSessionName = planSessionName,
            ),
        )
    }

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
        // Summarised over the samples the strap's own report supports, not
        // over every sample that arrived. A bpm of zero is the strap saying it
        // has no reading, and a reported R-R interval of zero is a claim that
        // no time passed between two beats; averaging either in as a value is
        // how a strap lying on a table came to publish a plausible resting
        // heart rate. What is stored is untouched -- the gzipped stream below
        // keeps every sample, zeros included.
        val hr = HrTrust.summarize(set.hrSamples)
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
                        // The role of the stream the DSP analysed, and null on
                        // a one-sensor set -- where there is no second stream
                        // to tell it from, and a label nobody assigned would
                        // state which physical unit the capture came from.
                        role = set.sensors?.analysed?.let(SensorCapturePolicy::wireOf),
                    ),
                )
            }
            // The second accelerometer, immediately after the one its figures
            // are NOT drawn from, so the archive lists the set's own analysed
            // capture first (#156).
            //
            // sampleRateHz is null and that is the whole of the reasoning: the
            // column holds `analysis.sampleRateHz`, measured over the stream
            // the DSP analysed, and there is no analysis of this one. Anything
            // written here is either a fabrication or a second, differently
            // derived quantity in a column that already means one thing.
            // Nothing is lost -- the exporter measures a rate from the stream
            // itself and treats the column only as a fallback.
            //
            // Written only when samples exist. An armed unit that captured
            // nothing gets no row, because a row holding a header and no data
            // would publish a stream that recorded nothing; the DECLARATION
            // below is what says the role was armed, and the pair of those two
            // facts is what makes a flat battery readable afterwards.
            set.secondary?.takeIf { it.samples.isNotEmpty() }?.let { secondary ->
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_IMU,
                        csvGzip = Gzip.compress(ImuCsv.encode(secondary.samples)),
                        sampleRateHz = null,
                        role = SensorCapturePolicy.wireOf(secondary.role),
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
            if (set.restHrSamples.isNotEmpty()) {
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_REST_BEFORE_HRM,
                        csvGzip = Gzip.compress(HrCsv.encode(set.restHrSamples)),
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
            // Last, and beside the cue track on purpose: the two are the
            // set's two clocks and the pair is only readable together. A cue
            // is what the app SAID, on a schedule; a mark is what was
            // COUNTED. Written only when marks exist, because an empty file
            // would claim the app counted and counted nothing -- false on
            // every sensor-counted set, which never counts out loud at all.
            if (set.repMarks.isNotEmpty()) {
                add(
                    RawStreamEntity(
                        setId = 0L,
                        kind = RawStreamEntity.KIND_REPS,
                        csvGzip = Gzip.compress(RepMarkCsv.encode(set.repMarks)),
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
                plannedPrepS = set.plannedPrepS,
                prepS = set.prepS,
                startedAtMs = set.startedAtMs,
                endedAtMs = set.endedAtMs,
                analysisJson = json.encodeToString(SetAnalysis.serializer(), set.analysis),
                // Written only when the caller states one. There is no default
                // to fall back on: an invented geometry reads exactly like a
                // measured one and would be believed.
                geometryJson =
                set.geometry?.let { json.encodeToString(ResolvedGeometry.serializer(), it) },
                // Written whenever the caller states one, which is every set
                // that is not the plain one-sensor default -- including a set
                // that asked for two and armed one. What arrived is observable
                // from the streams; what was ASKED FOR is observable from
                // nothing, so leaving this null on a shortfall would make it
                // indistinguishable from an ordinary one-sensor set forever.
                sensorsJson =
                set.sensors?.let { json.encodeToString(RecordedSensors.serializer(), it) },
                hrEndOfSetBpm = hr.endOfSetBpm,
                hrAvgBpm = hr.avgBpm,
                hrMaxBpm = hr.maxBpm,
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
     *
     * [sessionRpe] is ACCEPTED AND NOT YET STORED at this commit, deliberately
     * and for one commit only (#159).
     *
     * This is the "room" half of the partition: the parameter exists so that
     * the differential asserting the rating reaches the row can be written and
     * SEEN TO FAIL on the assertion -- "expected 7, was null" -- rather than
     * on the Kotlin compiler, which would red every test in this module at
     * once and name none of them. The commit after the differentials is what
     * writes it onto the row.
     *
     * Nothing passes it here. It has a null default and `:app` does not call
     * it with an argument until that same later commit, so no rating can be
     * dropped by this intermediate state in any build that ever ran.
     */
    suspend fun endSession(
        sessionId: Long,
        endedAtMs: Long,
        hrvRmssdMs: Double? = null,
        @Suppress("UNUSED_PARAMETER") sessionRpe: Int? = null,
    ) {
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

    /**
     * Lifter correction of a hold or carry's recorded seconds, from the rest
     * screen.
     *
     * The counterpart of [overrideReps] for the sets that have no reps. Its
     * one caller is the post-set addition #168 puts on the rest screen: a
     * timed set now ends when its clock reaches the prescription, so a hold
     * the lifter genuinely carried on past it is stated here afterwards
     * rather than mid-set, where the owner would never see it.
     *
     * Unlike [overrideReps] this sets no "corrected by the lifter" flag,
     * because `set_records` has no column for one on the duration -- reps have
     * `repsManual`, seconds have nothing. Adding one is a migration and is
     * deliberately not folded in here; the consequence, named rather than
     * hidden, is that the export cannot distinguish a corrected hold from a
     * measured one.
     */
    suspend fun overrideDuration(setId: Long, seconds: Int) = sessionDao.overrideDuration(setId, seconds)

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

    /**
     * How many accelerometers this set was armed with, or null when the row
     * does not say.
     *
     * Null covers three cases and deliberately distinguishes none of them, on
     * [decodeGeometry]'s reasoning: a row written before the column existed, an
     * ordinary one-sensor set, and a column that will not decode. All three
     * mean the same thing to a reader -- one stream, no role -- and none may
     * fall back to a plausible-looking default.
     */
    fun decodeSensors(entity: SetRecordEntity): RecordedSensors? = try {
        entity.sensorsJson?.let { json.decodeFromString(RecordedSensors.serializer(), it) }
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
