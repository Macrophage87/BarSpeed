package com.macrophage.barspeed.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [SessionRepository.endSession] writes onto the session row.
 *
 * This is the only writer of `endedAtMs`, `hrAvgBpm`, `hrMaxBpm` and
 * `hrvRmssdMs` anywhere in the tree: [SessionDao.updateSession] has exactly one
 * caller, which is this function, and this function has exactly one caller,
 * `RecordViewModel.finishSession`. Those four columns are therefore written once
 * per session or never, and nothing recomputes them afterwards. That is what
 * makes the behaviour worth pinning before anything moves underneath it.
 *
 * Three of the four are reconstructable from rows that stay durable. `endedAtMs`
 * is not the same fact as the last set's end but is near it; `hrAvgBpm` and
 * `hrMaxBpm` are derived here from [SetRecordEntity] columns that `recordSet`
 * already wrote. `hrvRmssdMs` is the exception and the reason care is taken
 * below: its input is a list of R-R intervals held only in memory in `:app`,
 * covering the rest windows that the per-set HR streams never see, so a value
 * lost or overwritten here cannot be recovered from anything.
 *
 * The fake DAO is stateful, unlike the one in [SessionRepositoryRecordSetTest],
 * and it has to be: that fake answers `sessionById` with null unconditionally,
 * so `endSession` returns at its first line and never reaches the code under
 * test here.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface, so
 * this fake stands in for it; what is verified is the repository's own reads,
 * arithmetic and the entity it hands back, and nothing about what the database
 * did with it.
 */
class SessionRepositoryEndSessionTest {
    // ---- fakes -------------------------------------------------------------

    /**
     * Holds sessions and sets so the repository's own reads are answered, and
     * keeps every [updateSession] argument in order.
     *
     * [updates] is a list rather than a last-write-wins field because the
     * question several of these tests ask is how many times the row was written
     * and with what, which a single field cannot answer.
     */
    private class FakeSessionDao(
        seedSessions: List<SessionEntity> = emptyList(),
        seedSets: List<SetRecordEntity> = emptyList(),
    ) : SessionDao {
        val sessions = seedSessions.associateBy { it.id }.toMutableMap()
        val sets = seedSets.toMutableList()
        val updates = mutableListOf<SessionEntity>()

        override suspend fun insertSession(session: SessionEntity): Long {
            val id = (sessions.keys.maxOrNull() ?: 0L) + 1L
            sessions[id] = session.copy(id = id)
            return id
        }

        override suspend fun updateSession(session: SessionEntity) {
            updates += session
            sessions[session.id] = session
        }

        override suspend fun insertSet(set: SetRecordEntity): Long {
            val id = (sets.mapNotNull { it.id.takeIf { v -> v > 0 } }.maxOrNull() ?: 0L) + 1L
            sets += set.copy(id = id)
            return id
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 0L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(sessions.values.toList())

        override suspend fun sessionById(id: Long): SessionEntity? = sessions[id]

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(sessions[id])

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            sets.filter { it.sessionId == sessionId }.sortedBy { it.orderIdx }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    // ---- fixtures ----------------------------------------------------------

    private fun session(
        id: Long = 1L,
        startedAtMs: Long = 1_000L,
        endedAtMs: Long? = null,
        hrAvgBpm: Int? = null,
        hrMaxBpm: Int? = null,
        hrvRmssdMs: Double? = null,
    ) = SessionEntity(
        id = id,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        planName = "Block A",
        planSessionName = "Lower 1",
        notes = "felt strong",
        hrAvgBpm = hrAvgBpm,
        hrMaxBpm = hrMaxBpm,
        hrvRmssdMs = hrvRmssdMs,
    )

    private fun setRow(
        id: Long,
        sessionId: Long = 1L,
        orderIdx: Int = 0,
        hrAvgBpm: Int? = null,
        hrMaxBpm: Int? = null,
    ) = SetRecordEntity(
        id = id,
        sessionId = sessionId,
        orderIdx = orderIdx,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        actualReps = 5,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        analysisJson = "{}",
        hrAvgBpm = hrAvgBpm,
        hrMaxBpm = hrMaxBpm,
    )

    private fun repo(dao: SessionDao) = SessionRepository(dao, FakeExerciseDao())

    // ---- what one close writes ---------------------------------------------

    @Test
    fun `closing a session stamps the end time it was handed`() = runTest {
        val dao = FakeSessionDao(seedSessions = listOf(session()))
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        assertEquals(1, dao.updates.size)
        assertEquals(9_000L, dao.updates.single().endedAtMs)
    }

    @Test
    fun `closing a session averages the per-set heart rates already stored`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session()),
                seedSets =
                listOf(
                    setRow(1L, orderIdx = 0, hrAvgBpm = 120, hrMaxBpm = 150),
                    setRow(2L, orderIdx = 1, hrAvgBpm = 140, hrMaxBpm = 165),
                ),
            )
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        // The unweighted mean of the per-set means, which is what the shipped
        // code computes: a three-rep single counts as much as a twenty-rep set.
        // Pinned as the behaviour that exists, not endorsed as the right one.
        assertEquals(130, dao.updates.single().hrAvgBpm)
    }

    @Test
    fun `closing a session takes the highest per-set maximum`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session()),
                seedSets =
                listOf(
                    setRow(1L, orderIdx = 0, hrAvgBpm = 120, hrMaxBpm = 150),
                    setRow(2L, orderIdx = 1, hrAvgBpm = 140, hrMaxBpm = 165),
                ),
            )
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        assertEquals(165, dao.updates.single().hrMaxBpm)
    }

    @Test
    fun `closing a session stores the session HRV it is handed`() = runTest {
        val dao = FakeSessionDao(seedSessions = listOf(session()))
        repo(dao).endSession(1L, endedAtMs = 9_000L, hrvRmssdMs = 41.5)

        assertEquals(41.5, dao.updates.single().hrvRmssdMs)
    }

    @Test
    fun `a session with no heart-rate data anywhere is closed with no summary`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session()),
                seedSets = listOf(setRow(1L), setRow(2L, orderIdx = 1)),
            )
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        val written = dao.updates.single()
        assertNull(written.hrAvgBpm)
        assertNull(written.hrMaxBpm)
        assertNull(written.hrvRmssdMs)
        // An unmeasured heart rate is absent, never zero.
        assertEquals(9_000L, written.endedAtMs)
    }

    @Test
    fun `closing a session leaves everything captured when it started alone`() = runTest {
        val dao = FakeSessionDao(seedSessions = listOf(session()))
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        val written = dao.updates.single()
        assertEquals(1L, written.id)
        assertEquals(1_000L, written.startedAtMs)
        assertEquals("Block A", written.planName)
        assertEquals("Lower 1", written.planSessionName)
        assertEquals("felt strong", written.notes)
    }

    @Test
    fun `closing an unknown session writes nothing at all`() = runTest {
        val dao = FakeSessionDao()
        repo(dao).endSession(404L, endedAtMs = 9_000L, hrvRmssdMs = 41.5)

        assertTrue(dao.updates.isEmpty())
    }

    // ---- what the summary is measured against ------------------------------

    @Test
    fun `the summary covers only the sets written before the close read them`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session()),
                seedSets = listOf(setRow(1L, orderIdx = 0, hrAvgBpm = 120, hrMaxBpm = 150)),
            )
        repo(dao).endSession(1L, endedAtMs = 9_000L)
        // A set landing after the close is not folded in afterwards, because
        // nothing rewrites these columns. This is the reason the record screen's
        // exit gate withholds "finish" while a set write is in flight.
        dao.insertSet(setRow(0L, orderIdx = 1, hrAvgBpm = 180, hrMaxBpm = 195))

        assertEquals(1, dao.updates.size)
        assertEquals(120, dao.updates.single().hrAvgBpm)
        assertEquals(150, dao.updates.single().hrMaxBpm)
    }

    @Test
    fun `sets belonging to another session are not counted`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session(id = 1L), session(id = 2L)),
                seedSets =
                listOf(
                    setRow(1L, sessionId = 1L, hrAvgBpm = 120, hrMaxBpm = 150),
                    setRow(2L, sessionId = 2L, hrAvgBpm = 190, hrMaxBpm = 200),
                ),
            )
        repo(dao).endSession(1L, endedAtMs = 9_000L)

        assertEquals(120, dao.updates.single().hrAvgBpm)
        assertEquals(150, dao.updates.single().hrMaxBpm)
    }

    // ---- what a second close does today ------------------------------------

    @Test
    fun `a second close restamps a session that was already closed`() = runTest {
        val dao = FakeSessionDao(seedSessions = listOf(session(endedAtMs = 9_000L)))
        repo(dao).endSession(1L, endedAtMs = 12_000L, hrvRmssdMs = 41.5)

        // Characterization of what ships, not an endorsement. A row that already
        // carries an end time is written over.
        assertEquals(1, dao.updates.size)
        assertEquals(12_000L, dao.updates.single().endedAtMs)
    }

    @Test
    fun `a second close with no HRV erases the HRV the first one stored`() = runTest {
        val dao =
            FakeSessionDao(seedSessions = listOf(session(endedAtMs = 9_000L, hrvRmssdMs = 41.5)))
        // hrvRmssdMs defaults to null and is copied onto the row unconditionally,
        // so a caller that omits it wipes the one figure in this row that cannot
        // be recomputed from anything durable: its input is the session's R-R
        // intervals, which live only in memory in `:app` and cover the rest
        // windows the per-set HR streams never see.
        repo(dao).endSession(1L, endedAtMs = 12_000L)

        // Characterization of what ships. No caller in the app omits the
        // argument today, so this is a property of the API rather than a
        // reachable path; it is pinned because the API is what the next caller
        // will use.
        assertNull(dao.updates.single().hrvRmssdMs)
    }

    @Test
    fun `a second close recomputes the heart-rate summary from scratch`() = runTest {
        val dao =
            FakeSessionDao(
                seedSessions = listOf(session(endedAtMs = 9_000L, hrAvgBpm = 130, hrMaxBpm = 165)),
                seedSets = listOf(setRow(1L, hrAvgBpm = 100, hrMaxBpm = 110)),
            )
        repo(dao).endSession(1L, endedAtMs = 12_000L)

        // The stored summary is not read back and merged; it is replaced by
        // whatever the set rows say now.
        assertEquals(100, dao.updates.single().hrAvgBpm)
        assertEquals(110, dao.updates.single().hrMaxBpm)
    }
}
