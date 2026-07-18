package com.macrophage.accelerometerlifting.data

import com.macrophage.accelerometerlifting.dsp.ImuCsv
import com.macrophage.accelerometerlifting.dsp.SetAnalysis
import com.macrophage.accelerometerlifting.model.ExerciseDef
import com.macrophage.accelerometerlifting.model.HrSample
import com.macrophage.accelerometerlifting.model.ImuSample
import com.macrophage.accelerometerlifting.model.StartPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Everything the record flow needs to persist about a finished set. */
data class CompletedSet(
    val exerciseId: String,
    val exerciseName: String,
    val loadKg: Double,
    val plannedLoadKg: Double?,
    val plannedReps: Int?,
    val tempo: String?,
    val targetMeanConVelMps: Double?,
    val velocityLossStopPct: Double?,
    val plannedRestS: Int?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val analysis: SetAnalysis,
    val imuSamples: List<ImuSample>,
    val hrSamples: List<HrSample>,
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

    suspend fun recordSet(sessionId: Long, orderIdx: Int, set: CompletedSet): Long {
        val hr = set.hrSamples.map { it.bpm }
        val setId =
            sessionDao.insertSet(
                SetRecordEntity(
                    sessionId = sessionId,
                    orderIdx = orderIdx,
                    exerciseId = set.exerciseId,
                    exerciseName = set.exerciseName,
                    loadKg = set.loadKg,
                    plannedLoadKg = set.plannedLoadKg,
                    actualReps = set.analysis.reps.size,
                    plannedReps = set.plannedReps,
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
            )
        if (set.imuSamples.isNotEmpty()) {
            sessionDao.insertRawStream(
                RawStreamEntity(
                    setId = setId,
                    kind = RawStreamEntity.KIND_IMU,
                    csvGzip = Gzip.compress(ImuCsv.encode(set.imuSamples)),
                    sampleRateHz = set.analysis.sampleRateHz,
                ),
            )
        }
        if (set.hrSamples.isNotEmpty()) {
            sessionDao.insertRawStream(
                RawStreamEntity(
                    setId = setId,
                    kind = RawStreamEntity.KIND_HRM,
                    csvGzip = Gzip.compress(HrCsv.encode(set.hrSamples)),
                ),
            )
        }
        return setId
    }

    suspend fun endSession(sessionId: Long, endedAtMs: Long) {
        val session = sessionDao.sessionById(sessionId) ?: return
        val sets = sessionDao.setsForSession(sessionId)
        val avg = sets.mapNotNull { it.hrAvgBpm }
        sessionDao.updateSession(
            session.copy(
                endedAtMs = endedAtMs,
                hrAvgBpm = if (avg.isEmpty()) null else avg.average().toInt(),
                hrMaxBpm = sets.mapNotNull { it.hrMaxBpm }.maxOrNull(),
            ),
        )
    }

    fun observeSession(id: Long): Flow<SessionEntity?> = sessionDao.observeSession(id)

    fun observeSets(sessionId: Long): Flow<List<SetRecordEntity>> = sessionDao.observeSetsForSession(sessionId)

    suspend fun session(id: Long): SessionEntity? = sessionDao.sessionById(id)

    suspend fun sets(sessionId: Long): List<SetRecordEntity> = sessionDao.setsForSession(sessionId)

    suspend fun rawStreams(setId: Long): List<RawStreamEntity> = sessionDao.rawStreamsForSet(setId)

    suspend fun deleteSession(id: Long) = sessionDao.deleteSession(id)

    fun decodeAnalysis(entity: SetRecordEntity): SetAnalysis? = try {
        json.decodeFromString(SetAnalysis.serializer(), entity.analysisJson)
    } catch (e: Exception) {
        null
    }

    /** Seeded + user-defined exercises, id → definition. */
    suspend fun exerciseById(id: String): ExerciseDef {
        ExerciseDef.seedById(id)?.let { return it }
        val custom = exerciseDao.byId(id)
        return if (custom != null) {
            ExerciseDef(custom.id, custom.displayName, StartPhase.valueOf(custom.startsWith), isCustom = true)
        } else {
            ExerciseDef(id, id.replace('_', ' ').replaceFirstChar { it.uppercase() }, isCustom = true)
        }
    }

    suspend fun ensureExerciseExists(id: String) {
        if (ExerciseDef.seedById(id) == null && exerciseDao.byId(id) == null) {
            exerciseDao.insert(
                CustomExerciseEntity(
                    id = id,
                    displayName = id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    startsWith = StartPhase.ECCENTRIC.name,
                ),
            )
        }
    }
}
