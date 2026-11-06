package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.LatestSession
import com.macrophage.barspeed.model.StoredSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [SessionRepository.latestSession] reads back for the resume question
 * (#48).
 *
 * Nothing here executes Room, SQLite or Android. [SessionDao] is an interface
 * and the fake below stands in for it, so what is pinned is the repository's
 * own choice of row and its arithmetic over that row's sets -- never what the
 * database's range query returned.
 */
class SessionRepositoryLatestSessionTest {
    private class FakeSessionDao(
        private val sessions: List<SessionEntity>,
        private val sets: List<SetRecordEntity>,
    ) : SessionDao {
        val ranges = mutableListOf<Pair<Long, Long>>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(sessions)

        override suspend fun sessionById(id: Long): SessionEntity? = sessions.firstOrNull { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            sets.filter { it.sessionId == sessionId }.sortedBy { it.orderIdx }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

        // Ordered by start, as the real query is; the repository must not
        // rely on the order to pick the newest, and the rows below are given
        // out of order to say so.
        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> {
            ranges += fromMs to toMs
            return sessions.filter { it.startedAtMs in fromMs..toMs }
        }

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun set(id: Long, sessionId: Long, orderIdx: Int, endedAtMs: Long) = SetRecordEntity(
        id = id,
        sessionId = sessionId,
        orderIdx = orderIdx,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = 5,
        startedAtMs = endedAtMs - 30_000L,
        endedAtMs = endedAtMs,
        analysisJson = "{}",
    )

    @Test
    fun `the newest session in the span is read back with its set count, last set and next slot`() = runTest {
        val dao =
            FakeSessionDao(
                sessions =
                listOf(
                    SessionEntity(id = 7, startedAtMs = 5_000L, planSessionName = "Lower A"),
                    SessionEntity(id = 3, startedAtMs = 1_000L, endedAtMs = 2_000L),
                ),
                // Out of order on purpose, and with a gap at orderIdx 1: the
                // next slot is one past the highest index, not the count.
                sets = listOf(set(11, 7, 2, 9_000L), set(10, 7, 0, 6_000L), set(4, 3, 0, 1_500L)),
            )
        val latest = SessionRepository(dao, FakeExerciseDao()).latestSession(sinceMs = 0L, nowMs = 10_000L)
        assertEquals(
            LatestSession(
                sessionId = 7,
                startedAtMs = 5_000L,
                endedAtMs = null,
                setCount = 2,
                lastSetEndedAtMs = 9_000L,
                nextOrderIdx = 3,
                planSessionName = "Lower A",
                // The session's own rows in orderIdx order, for where a
                // continued plan session picks up (UX review 2026-11-06, rest F1).
                storedSets = List(2) { StoredSet("back_squat", added = false, voided = false) },
            ),
            latest,
        )
        assertEquals(listOf(0L to 10_000L), dao.ranges)
    }

    @Test
    fun `no session in the span reads back as none`() = runTest {
        val dao = FakeSessionDao(sessions = listOf(SessionEntity(id = 1, startedAtMs = 1_000L)), sets = emptyList())
        assertNull(SessionRepository(dao, FakeExerciseDao()).latestSession(sinceMs = 2_000L, nowMs = 3_000L))
    }

    @Test
    fun `a session with no set reads back with no last set and slot zero next`() = runTest {
        val dao = FakeSessionDao(sessions = listOf(SessionEntity(id = 1, startedAtMs = 1_000L)), sets = emptyList())
        val latest = SessionRepository(dao, FakeExerciseDao()).latestSession(sinceMs = 0L, nowMs = 3_000L)
        assertEquals(0, latest?.setCount)
        assertNull(latest?.lastSetEndedAtMs)
        assertEquals(0, latest?.nextOrderIdx)
    }
}
