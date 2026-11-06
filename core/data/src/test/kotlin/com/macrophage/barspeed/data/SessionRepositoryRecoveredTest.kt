package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [SessionRepository] writes into `set_records.recovered` (#100,
 * database 21).
 *
 * A row written from an interrupted set's capture is not an ordinary set, and
 * #100's rule is that it must never become one implicitly: History and both
 * export documents have to be able to tell it apart. [CompletedSet.recovered]
 * is the caller's statement; this pins that both write paths -- a set added to
 * a session, and the first set that opens one -- carry it onto the row, and
 * that a set the record screen wrote is never marked.
 *
 * Nothing here executes Room, SQLite or Android: what is pinned is the
 * repository's mapping onto the entity it hands the DAO.
 */
class SessionRepositoryRecoveredTest {
    private class FakeSessionDao : SessionDao {
        val sets = mutableListOf<SetRecordEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 3L

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

    private fun completedSet() = CompletedSet(
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

    private fun opening() = SessionOpening(
        planName = null,
        planSessionName = null,
        startedAtMs = 1_000L,
        timeZone = null,
    )

    @Test
    fun `a set added from a recovered capture is stored as recovered`() = runTest {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao())
            .recordSet(sessionId = 1L, orderIdx = 2, set = completedSet().copy(recovered = true))
        assertEquals(true, dao.sets.single().recovered, "the recovered set reads as an ordinary one")
    }

    @Test
    fun `a session opened by a recovered capture stores its set as recovered`() = runTest {
        val dao = FakeSessionDao()
        SessionRepository(dao, FakeExerciseDao())
            .recordFirstSet(opening(), orderIdx = 0, set = completedSet().copy(recovered = true))
        assertEquals(true, dao.sets.single().recovered, "the recovered first set reads as an ordinary one")
    }

    @Test
    fun `a set the record screen wrote is not stored as recovered`() = runTest {
        val dao = FakeSessionDao()
        val repository = SessionRepository(dao, FakeExerciseDao())
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = completedSet())
        repository.recordFirstSet(opening(), orderIdx = 0, set = completedSet())
        assertEquals(listOf(false, false), dao.sets.map { it.recovered })
    }
}
