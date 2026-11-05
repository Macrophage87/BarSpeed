package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Where a session's first set is written, and where a set's streams are
 * encoded (#48 (b), #36).
 *
 * Nothing here executes Room, SQLite or Android. [SessionDao] is an interface
 * and the fake below stands in for it, and its "transaction" is the fake's own
 * bookkeeping: rows inserted while [FakeSessionDao.insertSessionWithFirstSet]
 * runs are kept only if it returns. That stands in for Room's `@Transaction`
 * on the real DAO and proves nothing about what SQLite does. What is pinned is
 * which DAO calls the repository makes, and in what context it does the
 * encoding.
 */
class SessionRepositoryFirstSetTest {
    private class FakeSessionDao(private val failSetInsert: Boolean = false) : SessionDao {
        val sessions = mutableListOf<SessionEntity>()
        val sets = mutableListOf<SetRecordEntity>()
        val streams = mutableListOf<RawStreamEntity>()

        /** Whether the encode had run, and outside [encodeContext], when the set row reached the DAO. */
        var encodedBeforeInsert: Boolean? = null
        var probe: RecordingDispatcher? = null

        private var inTransaction = false
        private val staged = mutableListOf<() -> Unit>()

        private fun write(apply: () -> Unit) {
            if (inTransaction) staged += apply else apply()
        }

        override suspend fun insertSession(session: SessionEntity): Long {
            write { sessions += session.copy(id = 41L) }
            return 41L
        }

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            probe?.let { encodedBeforeInsert = it.dispatched > 0 && !it.inside }
            check(!failSetInsert) { "disk full" }
            write { sets += set }
            return 7L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            write { streams += stream }
            return 1L
        }

        /** The default body, inside the fake's stand-in for a transaction. */
        override suspend fun insertSessionWithFirstSet(
            session: SessionEntity,
            set: SetRecordEntity,
            streams: List<RawStreamEntity>,
        ): Pair<Long, Long> {
            inTransaction = true
            try {
                val ids = super.insertSessionWithFirstSet(session, set, streams)
                staged.forEach { it() }
                return ids
            } finally {
                staged.clear()
                inTransaction = false
            }
        }

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

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

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

    /**
     * A dispatcher that runs what it is given at once, on the caller's
     * thread, and counts it. [inside] is true only while a block it was handed
     * is running.
     */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatched = 0
        var inside = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            inside = true
            try {
                block.run()
            } finally {
                inside = false
            }
        }
    }

    private val opening =
        SessionOpening(planName = "P", planSessionName = "Lower A", startedAtMs = 500L, timeZone = null)

    private fun completedSet() = CompletedSet(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = 100.0,
        workingLoadKg = 100.0,
        plannedLoadKg = 100.0,
        plannedReps = 5,
        actualDurationS = null,
        plannedDurationS = null,
        side = null,
        tempo = null,
        targetMeanConVelMps = null,
        velocityLossStopPct = null,
        plannedRestS = 120,
        plannedPrepS = null,
        prepS = null,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        restStartedAtMs = 2_000L,
        analysis =
        SetAnalysis(
            reps = listOf(RepAnalysis(0, 2.0, 0.5, 1.0, 0.25, 0.55, 0.8, -0.3, -0.45, 0.42, 700.0)),
            sampleRateHz = 100.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        ),
        geometry = null,
        imuSamples = listOf(ImuSample(1_000L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        hrSamples = listOf(HrSample(1_000L, bpm = 120, rrIntervalsMs = listOf(500.0))),
        restHrSamples = emptyList(),
        voiceCues = emptyList(),
        repMarks = emptyList(),
    )

    // ---- #48 (b) ------------------------------------------------------------

    /**
     * THE DEFECT (#48 (b)). The session row and its first set went in as two
     * writes, so a set insert that failed left the row behind with no set
     * under it, and Home drew a 0-set session card for a workout that
     * recorded nothing. Written in one transaction, a failed set insert takes
     * the row with it.
     */
    @Test
    fun `a first set whose insert fails leaves no session row behind`() = runTest {
        val dao = FakeSessionDao(failSetInsert = true)
        assertFailsWith<IllegalStateException> {
            SessionRepository(dao, FakeExerciseDao()).recordFirstSet(opening, 0, completedSet())
        }
        assertEquals(emptyList<SessionEntity>(), dao.sessions, "a failed first set left an empty session row")
    }

    /** The other side: a first set that lands writes its row, its set under it and the set's streams. */
    @Test
    fun `a first set that lands writes its session, its set under it and its streams`() = runTest {
        val dao = FakeSessionDao()
        val written = SessionRepository(dao, FakeExerciseDao()).recordFirstSet(opening, 0, completedSet())
        assertEquals(RecordedFirstSet(sessionId = 41L, setId = 7L), written)
        assertEquals(listOf(500L), dao.sessions.map { it.startedAtMs })
        assertEquals(listOf(41L), dao.sets.map { it.sessionId })
        assertTrue(dao.streams.isNotEmpty(), "the first set's streams were not written")
    }

    // ---- #36 ------------------------------------------------------------------

    /**
     * THE DEFECT (#36). `recordSet` CSV-encodes and gzips every stream of the
     * set, the whole IMU capture among them, on whatever thread called it, and
     * the record screen calls it from the main thread. The encode belongs on
     * [SessionRepository.encodeContext], and finished before the row reaches
     * the DAO.
     */
    @Test
    fun `a set's streams are encoded on the encode context, before the insert`() = runTest {
        val probe = RecordingDispatcher()
        val dao = FakeSessionDao().also { it.probe = probe }
        SessionRepository(dao, FakeExerciseDao(), encodeContext = probe).recordSet(41L, 1, completedSet())
        assertEquals(true, dao.encodedBeforeInsert, "the streams were not encoded on the encode context")
    }

    @Test
    fun `a first set's streams are encoded on the encode context, before the insert`() = runTest {
        val probe = RecordingDispatcher()
        val dao = FakeSessionDao().also { it.probe = probe }
        SessionRepository(dao, FakeExerciseDao(), encodeContext = probe).recordFirstSet(opening, 0, completedSet())
        assertEquals(true, dao.encodedBeforeInsert, "the streams were not encoded on the encode context")
    }
}
