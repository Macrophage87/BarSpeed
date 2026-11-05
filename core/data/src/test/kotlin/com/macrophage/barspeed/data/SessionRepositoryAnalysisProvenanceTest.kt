package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.SessionExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [SessionRepository.recordSet] writes into `analysisSchemaVersion`
 * (#38, database 21).
 *
 * The analysis blob is computed once, by the build that records the set, and
 * every export afterwards only decodes it. So the version that describes its
 * figures is the RECORDING build's contract, and the row has to carry it: the
 * exporting build's `schemaVersion` describes the document, not the figures
 * inside it.
 *
 * RED until the write lands. The column exists from database 21's commit and
 * nothing fills it there, so the row stores null. Nothing here executes Room,
 * SQLite or Android: what is pinned is the repository's mapping onto the
 * entity it hands the DAO.
 */
class SessionRepositoryAnalysisProvenanceTest {
    private class FakeSessionDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set
            return 7L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun completedSet() =
        CompletedSet(
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = 100.0,
            plannedLoadKg = 100.0,
            workingLoadKg = 100.0,
            plannedReps = 5,
            manualReps = 5,
            actualDurationS = null,
            plannedDurationS = null,
            tempo = null,
            targetMeanConVelMps = null,
            velocityLossStopPct = null,
            plannedRestS = null,
            restStartedAtMs = 31_000L,
            plannedPrepS = null,
            prepS = null,
            startedAtMs = 1_000L,
            endedAtMs = 31_000L,
            analysis =
            SetAnalysis(
                reps = emptyList(),
                sampleRateHz = 99.4,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            ),
            imuSamples = emptyList(),
            hrSamples = emptyList(),
        )

    /**
     * The row names the build's own [SessionExport.SCHEMA_VERSION], read at
     * the write.
     *
     * Against the constant rather than a literal, so the pin follows the
     * contract's next bump instead of failing on it. What it catches is a write
     * of nothing -- the null every pre-v21 row carries, which on a new row
     * would make a set recorded today indistinguishable from one recorded
     * before provenance existed -- and a write of any other value.
     */
    @Test
    fun `the row names the schema version its analysis was computed under`() = runTest {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao()).recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        assertEquals(
            SessionExport.SCHEMA_VERSION,
            dao.sets.single().analysisSchemaVersion,
            "the row does not say which contract its analysis was computed under",
        )
    }
}
