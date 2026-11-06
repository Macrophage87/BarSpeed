package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.SetTargets
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.JournalSetFacts
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RecoveryRefusal
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SetGeometryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Putting an interrupted set's capture back into History (#100), end to end
 * from a journal directory on a real temporary filesystem to the rows handed a
 * fake DAO.
 *
 * ## The capture
 *
 * Set 02 of field session 36 -- `field-backsquat-4011-6rep-s36-set02-imu-a.csv`
 * and its `-cues.csv`, the bytes [AnalysedRoleFallbackTest] documents: a
 * guided 4011 back squat at 52.163122551154075 kg, armed for two units with
 * role b analysed, where b delivered nothing and a streamed the whole set. It
 * is laid out here as the journal would have held it at a kill: `imu.csv` (the
 * armed unit, b) holding its header row and nothing else, `imu-a.csv` holding
 * role a's capture, `cues.csv` the set's cue track. The prep window --
 * 10 s from the set's first instant -- is a FIXTURE CHOICE made here, not
 * measured: the session's `meta.json` records `prepS` 10 and no work-start
 * instant, and nothing below depends on which instant it is beyond its being
 * the one the analysis is handed.
 *
 * ## What is and is not pinned
 *
 * The recovered row's analysis is compared with `SetAnalyzer.analyze` over the
 * same samples, cues and window: that is the claim "the normal analyzer path",
 * and it is a comparison of two calls rather than a measured figure. Which
 * session the row goes into is `JournalRecoveryPolicy`'s and pinned there; here
 * it is pinned that the repository is driven to that answer and that the
 * capture is removed only once the row has been handed over. Nothing executes
 * Room, SQLite or Android, and nothing here can say a journal survives a real
 * kill -- #42 stage 1's field check is what measures that, and it has not been
 * run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalRecoveryTest {
    private val root: File = Files.createTempDirectory("journal-recovery").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun TestScope.store() = SetJournalStore(root, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes()
            .decodeToString()

    private val roleAText = resource("field-backsquat-4011-6rep-s36-set02-imu-a.csv")
    private val cuesText = resource("field-backsquat-4011-6rep-s36-set02-cues.csv")
    private val roleA: List<ImuSample> = ImuCsv.decode(roleAText)

    private val squat = ExerciseDef(id = "back_squat", displayName = "Back Squat")
    private val loadKg = 52.163122551154075
    private val startedAtMs = 1_788_251_947_376L
    private val window = PrepWindow(startedAtMs = startedAtMs, workStartedAtMs = startedAtMs + 10_000L)

    private val facts =
        JournalSetFacts(
            exercise = squat,
            geometry = SetGeometryPolicy.describe(squat, null),
            loadKg = loadKg,
            timed = false,
            prepCase = PrepCase.CUED,
            armedSensors =
            RecordedSensors(count = 2, expected = listOf(SensorRole.B, SensorRole.A), analysed = SensorRole.B),
        )

    private fun header(sessionId: Long? = 7L, facts: JournalSetFacts? = this.facts) = SetJournalHeader(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        sessionId = sessionId,
        sessionStartedAtMs = startedAtMs - 600_000L,
        startedAtMs = startedAtMs,
        orderIdx = 1,
        imuConnected = true,
        sensorRoles = listOf(SensorRole.B, SensorRole.A),
        armedRole = SensorRole.B,
        secondaryImuConnected = true,
        facts = facts,
    )

    /** The capture as a kill would have left it, listed the way Home lists it. */
    private suspend fun TestScope.journaled(
        header: SetJournalHeader = header(),
        prep: Boolean = true,
        roleA: String? = roleAText,
        marks: List<Long> = emptyList(),
    ): OrphanedSet {
        val store = store()
        val journal = checkNotNull(store.open(header))
        journal.close()
        val dir = journal.directory
        File(dir, SetJournal.IMU).writeText(ImuCsv.HEADER + "\n")
        roleA?.let { File(dir, SetJournal.secondaryImuFile(SensorRole.A)).writeText(it) }
        File(dir, SetJournal.CUES).writeText(cuesText)
        if (prep) File(dir, SetJournal.PREP).writeText(PrepWindowCsv.encode(window))
        if (marks.isNotEmpty()) {
            val rows = marks.joinToString(separator = "\n", postfix = "\n")
            File(dir, SetJournal.REPS).writeText(RepMarkCsv.HEADER + "\n" + rows)
        }
        return store.orphans().single()
    }

    // ---- a fake DAO that records what it was handed ---------------------------

    private class FakeSessionDao(
        val sessions: MutableList<SessionEntity> = mutableListOf(),
        val sets: MutableList<SetRecordEntity> = mutableListOf(),
        val failInsert: Boolean = false,
    ) : SessionDao {
        val updated = mutableListOf<SessionEntity>()
        private var nextId = 100L

        override suspend fun insertSession(session: SessionEntity): Long {
            val id = nextId++
            sessions += session.copy(id = id)
            return id
        }

        override suspend fun updateSession(session: SessionEntity) {
            updated += session
            sessions.replaceAll { if (it.id == session.id) session else it }
        }

        override suspend fun insertSet(set: SetRecordEntity): Long {
            check(!failInsert) { "the disk refused" }
            val id = nextId++
            sets += set.copy(id = id)
            return id
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = nextId++

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

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> =
            sessions.filter { it.startedAtMs in fromMs..toMs }

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun row(id: Long, sessionId: Long, orderIdx: Int, started: Long, ended: Long) = SetRecordEntity(
        id = id,
        sessionId = sessionId,
        orderIdx = orderIdx,
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        loadKg = loadKg,
        actualReps = 6,
        startedAtMs = started,
        endedAtMs = ended,
        analysisJson = "{}",
    )

    /** Session 7, holding two sets, the last ended a minute before the capture began. */
    private fun ownSession(endedAtMs: Long?) = FakeSessionDao(
        sessions = mutableListOf(SessionEntity(id = 7, startedAtMs = startedAtMs - 600_000L, endedAtMs = endedAtMs)),
        sets =
        mutableListOf(
            row(1, 7, 0, startedAtMs - 500_000L, startedAtMs - 450_000L),
            row(2, 7, 1, startedAtMs - 120_000L, startedAtMs - 60_000L),
        ),
    )

    private val now = startedAtMs + 300_000L

    private fun TestScope.recovery(dao: FakeSessionDao) =
        JournalRecovery(SessionRepository(dao, FakeExerciseDao()), store(), nowMs = { now })

    private fun ready(prepared: PreparedRecovery): CompletedSet = assertIs<PreparedRecovery.Ready>(prepared).set

    // ---- the row the capture becomes ---------------------------------------------

    /**
     * The normal analyzer path, read back from disk: the analysis falls back to
     * role a exactly as `armedCaptureOf` decides it for a live set, and is
     * computed with the journaled cue track and the journaled work start.
     */
    @Test
    fun `a recovered set is analysed the way its own write would have analysed it`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        val expected =
            SetAnalyzer.analyze(
                roleA,
                squat.liftDirection(),
                loadKg,
                SetTargets(cadenceGuided = true),
                cues = CueCsv.decode(cuesText),
                workStartedAtMs = window.workStartedAtMs,
                analysedUnitFellBack = true,
            )
        assertEquals(expected, set.analysis)
        assertEquals(roleA, set.imuSamples)
        assertEquals(SensorRole.A, set.sensors?.analysed)
        assertEquals(true, set.sensors?.analysedFellBack)
        assertEquals(window, set.prepWindow)
    }

    /**
     * Marked recovered, and nothing the lifter would have given at set end is
     * invented: no stated count beside an analysable stream (the sensor counts
     * reps), no live count, no rating, no plan figures.
     */
    @Test
    fun `a recovered set is marked recovered and states nothing nobody gave`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        assertTrue(set.recovered)
        assertNull(set.manualReps)
        assertNull(set.liveReps)
        assertNull(set.rpe)
        assertNull(set.plannedReps)
        assertNull(set.plannedLoadKg)
        assertNull(set.tempo)
        assertEquals(loadKg, set.loadKg)
        assertEquals(startedAtMs, set.startedAtMs)
    }

    /** No app ended the set; its end is the last instant any stream reached the disk. */
    @Test
    fun `a recovered set ends at the last journaled instant`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        val last = maxOf(roleA.maxOf { it.timestampMs }, CueCsv.decode(cuesText).maxOf { it.timestampMs })
        assertEquals(last, set.endedAtMs)
    }

    /**
     * A kill mid-append leaves a final line with no newline, and a cut inside
     * its last field still parses -- as a sample with a wrong yaw. Only
     * complete lines are read.
     */
    @Test
    fun `a line the kill cut off is not read as a sample`() = runTest {
        val cut = roleAText + "1788251999999,-0.9165,-0.4018,-0.0097,-0.3052,-0.3052,-0.4883,-90.7251,66.0992,-85.9"
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(roleA = cut)))
        assertEquals(roleA, set.imuSamples)
    }

    /** With no stream to analyse, the journaled marks are the only count anybody made, and say so. */
    @Test
    fun `with no analysable stream the counted marks are the stated count`() = runTest {
        val marks = listOf(1L, 2L, 3L, 4L, 5L).map { startedAtMs + it * 5_000L }
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(roleA = null, marks = marks)))
        assertEquals(5, set.manualReps)
        assertTrue(set.analysis.reps.isEmpty())
        assertEquals(marks, set.repMarks)
    }

    // ---- what is refused ---------------------------------------------------------

    @Test
    fun `a capture recorded before the facts existed is refused, not filled in`() = runTest {
        val orphan = journaled(header = header(facts = null))
        val recovery = recovery(FakeSessionDao())
        assertEquals(RecoveryRefusal.NOT_JOURNALED, recovery.refusal(orphan))
        assertEquals(RecoveryOutcome.Refused(RecoveryRefusal.NOT_JOURNALED), recovery.recover(orphan, false))
    }

    @Test
    fun `a prepped set whose work start never reached the disk is refused`() = runTest {
        val orphan = journaled(prep = false)
        assertEquals(RecoveryRefusal.WORK_START_UNKNOWN, recovery(FakeSessionDao()).refusal(orphan))
    }

    /** Counted whole by the listing scan and still not a stream: refused at the decode, not guessed around. */
    @Test
    fun `a stream that will not parse is refused`() = runTest {
        val broken = roleAText.replaceFirst("\n", "\nnot,a,sample\n")
        val orphan = journaled(roleA = broken)
        assertEquals(
            PreparedRecovery.Refused(RecoveryRefusal.STREAM_UNREADABLE),
            recovery(FakeSessionDao()).prepare(orphan),
        )
    }

    // ---- where it goes, and when the capture is removed -------------------------

    @Test
    fun `nothing is written while a recording write is outstanding`() = runTest {
        val dao = ownSession(endedAtMs = null)
        val orphan = journaled()
        assertEquals(RecoveryOutcome.Waiting, recovery(dao).recover(orphan, writeOutstanding = true))
        assertEquals(2, dao.sets.size)
        assertTrue(orphan.directory.exists(), "the capture went with nothing written")
    }

    /**
     * THE ISSUE'S RULE. Session 7 is closed: its heart-rate summary was written
     * over the sets it held, and it is not re-opened or rewritten. The set gets
     * a session of its own, begun when the set began and closed when it ended.
     */
    @Test
    fun `a capture whose session is closed goes into a session of its own, closed at its end`() = runTest {
        val dao = ownSession(endedAtMs = startedAtMs - 30_000L)
        val orphan = journaled()
        val outcome = assertIs<RecoveryOutcome.Stored>(recovery(dao).recover(orphan, writeOutstanding = false))
        assertTrue(outcome.newSession)
        val written = dao.sets.single { it.recovered }
        assertEquals(outcome.sessionId, written.sessionId)
        assertEquals(0, written.orderIdx)
        val opened = dao.sessions.single { it.id == outcome.sessionId }
        assertEquals(startedAtMs, opened.startedAtMs)
        assertEquals(written.endedAtMs, opened.endedAtMs)
        assertEquals(emptyList(), dao.updated.filter { it.id == 7L }, "the closed session was rewritten")
        assertFalse(orphan.directory.exists(), "the capture outlived its row")
    }

    /** Session 7 is open and is the one Record would offer to continue: the set is one of its sets. */
    @Test
    fun `a capture whose session is open and current rejoins it at the next free position`() = runTest {
        val dao = ownSession(endedAtMs = null)
        val orphan = journaled()
        val outcome = assertIs<RecoveryOutcome.Stored>(recovery(dao).recover(orphan, writeOutstanding = false))
        assertEquals(RecoveryOutcome.Stored(7L, outcome.setId, newSession = false), outcome)
        val written = dao.sets.single { it.recovered }
        assertEquals(7L, written.sessionId)
        assertEquals(2, written.orderIdx)
        assertEquals(1, dao.sessions.size, "a second session was opened for one workout")
        assertEquals(emptyList(), dao.updated, "the open session was rewritten")
        assertFalse(orphan.directory.exists(), "the capture outlived its row")
    }

    /** The row landed and the process died before the journal went: the set is not written twice. */
    @Test
    fun `a capture whose set is already stored is not written again, and goes`() = runTest {
        val dao = ownSession(endedAtMs = null)
        dao.sets += row(3, 7, 2, startedAtMs, startedAtMs + 50_000L)
        val orphan = journaled()
        assertEquals(RecoveryOutcome.AlreadyStored, recovery(dao).recover(orphan, writeOutstanding = false))
        assertEquals(3, dao.sets.size)
        assertFalse(orphan.directory.exists(), "the capture of a stored set was kept")
    }

    /** The journal is removed only after the row write lands; a write that fails leaves the only copy. */
    @Test
    fun `a write that fails keeps the capture`() = runTest {
        val dao =
            FakeSessionDao(
                sessions = mutableListOf(SessionEntity(id = 7, startedAtMs = startedAtMs - 600_000L, endedAtMs = 1L)),
                failInsert = true,
            )
        val orphan = journaled()
        assertFailsWith<IllegalStateException> { recovery(dao).recover(orphan, writeOutstanding = false) }
        assertTrue(orphan.directory.exists(), "the only copy of the set went with the write that failed")
    }
}
