package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.RecordedTimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [SessionRepository.startSession] writes onto a new session row.
 *
 * This function had no test of any kind before issue 75, and it is the only
 * writer of the row that every set in a session hangs off. It runs once per
 * session, lazily, at the moment the first set is recorded — so what it writes
 * is written once and nothing revisits it.
 *
 * The zone pair is the reason this file exists. Unlike everything the DSP
 * derives, which stays recoverable because the raw capture is persisted per
 * set, the offset a session was recorded on exists only at that moment: no CSV,
 * no analysis blob and no later screen can reconstruct it. If it is not written
 * here it is gone, which puts it in the same class as RPE and the wall
 * timestamps rather than in the class of things a reprocess can fix.
 *
 * Nothing here executes Room, SQLite or Android. [SessionDao] is an interface
 * and the fake below stands in for it, so what is pinned is the entity the
 * repository hands over — never what the database did with it, and in
 * particular nothing about whether the v8 to v9 migration that adds these two
 * columns actually runs.
 */
class SessionRepositoryStartSessionTest {
    // ---- fakes -------------------------------------------------------------

    /** Keeps every inserted session so the entity handed over can be read back. */
    private class FakeSessionDao : SessionDao {
        val inserted = mutableListOf<SessionEntity>()

        override suspend fun insertSession(session: SessionEntity): Long {
            inserted += session
            return inserted.size.toLong()
        }

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun repoWith(dao: FakeSessionDao) = SessionRepository(dao, FakeExerciseDao())

    // ---- what reaches the row ----------------------------------------------

    @Test
    fun `the plan, the start instant and the zone all reach the row`() = runTest {
        val dao = FakeSessionDao()
        val id =
            repoWith(dao).startSession(
                planName = "Hypertrophy Block W3",
                planSessionName = "Lower A",
                startedAtMs = 1_786_956_595_593L,
                timeZone = RecordedTimeZone("America/New_York", -240),
            )
        assertEquals(1L, id)
        val row = dao.inserted.single()
        assertEquals("Hypertrophy Block W3", row.planName)
        assertEquals("Lower A", row.planSessionName)
        assertEquals(1_786_956_595_593L, row.startedAtMs)
        assertEquals("America/New_York", row.zoneId)
        assertEquals(-240, row.utcOffsetMinutes)
        // Opened, not closed. A session with an end time already on it would
        // never be closed by endSession, which refuses a row that has one.
        assertNull(row.endedAtMs)
    }

    /**
     * An offset of zero is stored as zero and not as an absence.
     *
     * London in winter is genuinely on UTC. A `takeIf { it != 0 }` anywhere on
     * this path, or a column defaulted rather than written, turns a lifter who
     * trains in London into a lifter whose sessions carry no zone at all.
     */
    @Test
    fun `a zero offset is stored, not dropped`() = runTest {
        val dao = FakeSessionDao()
        repoWith(dao).startSession(null, null, 1_000L, RecordedTimeZone("Europe/London", 0))
        val row = dao.inserted.single()
        assertEquals("Europe/London", row.zoneId)
        assertEquals(0, row.utcOffsetMinutes)
    }

    /**
     * No zone means both columns stay null, which is exactly how a row written
     * before these columns existed reads.
     *
     * The two cases have to be indistinguishable. A session recorded on a
     * device whose zone could not be resolved and a session recorded before the
     * app captured zones are both "the app cannot say", and inventing a
     * distinction between them would offer a reader a difference that carries
     * no information.
     */
    @Test
    fun `no zone leaves both columns null`() = runTest {
        val dao = FakeSessionDao()
        repoWith(dao).startSession("P", "S", 1_000L, timeZone = null)
        val row = dao.inserted.single()
        assertNull(row.zoneId)
        assertNull(row.utcOffsetMinutes)
    }
}
