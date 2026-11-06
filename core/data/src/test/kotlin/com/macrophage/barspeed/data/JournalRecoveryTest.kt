package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.NoRepsReason
import com.macrophage.barspeed.dsp.RestStart
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.SetEnd
import com.macrophage.barspeed.dsp.SetTargets
import com.macrophage.barspeed.dsp.StackRollSignature
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.JournalSetFacts
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RecoveryRefusal
import com.macrophage.barspeed.model.RepCounter
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.StackMountSignal
import com.macrophage.barspeed.model.Tempo
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
import kotlin.test.assertNotNull
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
 * The set was guided, so its journal would also have held the guide's rep
 * calls in `reps.csv`. Those are not in the committed fixtures for set 02,
 * and [guideCalls] takes them from the cue track's `Rep N`, `Last rep` and
 * `Done` instants: a FIXTURE CHOICE, grounded on set 01 of the same session,
 * whose committed `-reps.csv` holds exactly those six cue instants and no
 * other.
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

    /** The guide's six calls, as set 01's `-reps.csv` records them: the instants of its count words. */
    private val guideCalls: List<Long> =
        CueCsv.decode(cuesText)
            .filter { it.cue.startsWith("Rep ") || it.cue == "Last rep" || it.cue == "Done" }
            .map { it.timestampMs }

    /** Where the guided set's working window ends, as the analysis bounds it. */
    private val guidedEnd: SetEnd = SetEnd.of(CueCsv.decode(cuesText), cadenceGuided = true)

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
            counter = RepCounter.METRONOME,
            tempo = "4011",
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
        marks: List<Long> = guideCalls,
        cues: String = cuesText,
    ): OrphanedSet {
        val store = store()
        val journal = checkNotNull(store.open(header))
        journal.close()
        val dir = journal.directory
        File(dir, SetJournal.IMU).writeText(ImuCsv.HEADER + "\n")
        roleA?.let { File(dir, SetJournal.secondaryImuFile(SensorRole.A)).writeText(it) }
        File(dir, SetJournal.CUES).writeText(cues)
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
                SetTargets(countedReps = 6, tempo = Tempo.parseOrNull("4011"), cadenceGuided = true),
                cues = CueCsv.decode(cuesText),
                workStartedAtMs = window.workStartedAtMs,
                analysedUnitFellBack = true,
                analysedSignal = StackRollSignature.of(roleA, window.workStartedAtMs, guidedEnd),
            )
        assertEquals(expected, set.analysis)
        assertEquals(roleA, set.imuSamples)
        assertEquals(SensorRole.A, set.sensors?.analysed)
        assertEquals(true, set.sensors?.analysedFellBack)
        assertEquals(window, set.prepWindow)
    }

    /**
     * Marked recovered, and nothing the lifter would have given at set end is
     * invented: no live count, no rating, no plan figures. The guide's count
     * IS stated, because the guide stated it (#100 review round 1, Lens B B1):
     * a guided set's own write records its tally as the count, and so does
     * its recovery. The tempo it ran to is the journaled one.
     */
    @Test
    fun `a recovered set is marked recovered and states nothing nobody gave`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        assertTrue(set.recovered)
        assertEquals(6, set.manualReps)
        assertNull(set.liveReps)
        assertNull(set.rpe)
        assertNull(set.plannedReps)
        assertNull(set.plannedLoadKg)
        assertEquals("4011", set.tempo)
        assertEquals(loadKg, set.loadKg)
        assertEquals(startedAtMs, set.startedAtMs)
    }

    /**
     * The verify step Lens B wrote for B1: a set the LIFTER counted, an
     * analysable stream beside it, keeps the lifter's count as the stated
     * one -- what `RepCountPolicy.recorded` writes for it at set end -- and
     * the analyzer is handed that count, so "Sensor resolved X of Y" names
     * the count the row carries.
     */
    @Test
    fun `a lifter's taps are the stated count beside an analysable stream`() = runTest {
        val tapped = facts.copy(counter = RepCounter.MANUAL, prepCase = PrepCase.NONE, tempo = null)
        val taps = listOf(1L, 2L, 3L, 4L, 5L).map { startedAtMs + 10_000L + it * 6_000L }
        val orphan = journaled(header(facts = tapped), prep = false, marks = taps)
        val set = ready(recovery(FakeSessionDao()).prepare(orphan))
        assertEquals(5, set.manualReps)
        assertTrue(set.analysis.reps.isNotEmpty(), "the stream was not analysed")
        val expected =
            SetAnalyzer.analyze(
                roleA,
                squat.liftDirection(),
                loadKg,
                SetTargets(countedReps = 5),
                cues = CueCsv.decode(cuesText),
                analysedUnitFellBack = true,
            )
        assertEquals(expected, set.analysis)
    }

    /**
     * What the frozen slot declared at START goes onto the row exactly as
     * `endSet` writes it off that slot (#100 review round 1, Lens A B3 and
     * N8): a planned warm-up is put back as a warm-up, not as a working set
     * every working-set reader would count.
     */
    @Test
    fun `a planned warm-up is put back as a warm-up`() = runTest {
        val slotFacts = facts.copy(warmup = true, added = true, plannedSide = "left", plannedPrepS = 10, prepS = 8)
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(header(facts = slotFacts))))
        assertTrue(set.warmup, "a planned warm-up was put back as a working set")
        assertTrue(set.added)
        assertEquals("left", set.plannedSide)
        assertEquals(10, set.plannedPrepS)
        assertEquals(8, set.prepS)
    }

    /**
     * The rest runs from the instant the set's own write would start it
     * (#100 review round 1, Lens A N1): `RestStart.of` over the journaled cue
     * track and marks, not the last sample. On this guided set that is the
     * guide's terminal word.
     */
    @Test
    fun `the rest runs from where the set's own write would start it`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        val expected = RestStart.of(CueCsv.decode(cuesText), true, guideCalls, null, set.endedAtMs)
        assertEquals(expected, set.restStartedAtMs)
        assertTrue(expected < set.endedAtMs, "the fixture cannot tell the two instants apart")
    }

    /**
     * The guide's count is published as the guide's (#100 review round 1,
     * Lens B B1), end to end through the export writer: a recovered guided
     * set publishes `metronome`, not `manual`.
     */
    @Test
    fun `a recovered guided set publishes the guide's count as the guide's`() = runTest {
        val dao = ownSession(endedAtMs = startedAtMs - 30_000L)
        val repository = SessionRepository(dao, FakeExerciseDao())
        val recovery = JournalRecovery(repository, store(), nowMs = { now })
        val outcome = assertIs<RecoveryOutcome.Stored>(recovery.recover(journaled(), writeOutstanding = false))
        val export = assertNotNull(SessionExporter(repository).buildExport(outcome.sessionId, false))
        val set = export.exercises.single().sets.single()
        assertEquals("metronome", set.repsSource)
        assertEquals(6, set.reps)
    }

    /** No app ended the set; its end is the last instant any stream reached the disk. */
    @Test
    fun `a recovered set ends at the last journaled instant`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        val last = maxOf(roleA.maxOf { it.timestampMs }, CueCsv.decode(cuesText).maxOf { it.timestampMs })
        assertEquals(last, set.endedAtMs)
    }

    /**
     * The end is the latest instant of ANY stream, not of the samples alone: a
     * cue spoken after the last sample reached the disk bounds the set too.
     */
    @Test
    fun `a cue spoken after the last sample still bounds the end`() = runTest {
        val late = roleA.maxOf { it.timestampMs } + 5_000L
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(cues = cuesText + "$late,Set ended\n")))
        assertEquals(late, set.endedAtMs)
    }

    /**
     * The analysis moved off the armed unit, and the declaration describes
     * the armed unit's mount: where that mount decides the figures -- a 2:1
     * pulley here -- they are withheld, exactly as the set's own write
     * withholds them (#247). The recovery must hand the analyzer the
     * fell-back flag the capture decided, not a default.
     */
    @Test
    fun `an analysis moved off the armed unit is withheld where the mount decides it`() = runTest {
        val pulley = squat.copy(travelRatio = 2.0)
        val pulleyFacts = facts.copy(exercise = pulley, geometry = SetGeometryPolicy.describe(pulley, null))
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(header = header(facts = pulleyFacts))))
        assertEquals(true, set.sensors?.analysedFellBack)
        assertEquals(listOf(SetAnalyzer.MOUNT_NOT_DECLARED_VERDICT), set.analysis.verdicts)
        assertTrue(set.analysis.reps.isEmpty())
    }

    /**
     * The mirror (#279; #100 review round 2, Lens A B1): a fallback onto a
     * partner whose own roll reads as riding a load is refused under a
     * declaration naming NO mount, exactly as the set's own write refuses it
     * -- `endSet` hands the analyzer `ArmedCapture.analysedSignal`, and so
     * must the recovery. The squat's declaration is mount-free, so #247's
     * refusal above cannot be what fires.
     *
     * The partner is SYNTHETIC, not field data: role a's own instants with
     * every reading held at its first sample's, a unit lying still. That is
     * a shape the 1.26 #279 entry names as reading the same as a stack
     * partner and refused with it; no committed :core:data fixture is a
     * partner clipped to a stack.
     */
    @Test
    fun `a fallback onto a partner read as riding a load is refused as its own write refuses it`() = runTest {
        val first = roleA.first()
        val still = roleA.map { first.copy(timestampMs = it.timestampMs) }
        assertEquals(
            StackMountSignal.ON_STACK,
            StackRollSignature.of(still, window.workStartedAtMs, guidedEnd),
            "the fixture no longer reads as riding a load",
        )
        assertFalse(squat.liftDirection().mountSpecific, "the declaration names a mount")
        val set = ready(recovery(FakeSessionDao()).prepare(journaled(roleA = ImuCsv.encode(still))))
        assertEquals(true, set.sensors?.analysedFellBack)
        assertEquals(NoRepsReason.MOUNT_NOT_DECLARED, set.analysis.noRepsReason)
        assertTrue(set.analysis.reps.isEmpty())
    }

    /**
     * A recovered set carries no silence word (#100 review round 1, Lens A
     * B2). Role b was armed and delivered nothing, which the set's own write
     * would publish as `silent` from the link states read as the set ended;
     * those links ended with the process, so the recovery reads none and
     * publishes none. The export's descriptions say so, because an absent
     * word there would otherwise read as a stream that delivered.
     */
    @Test
    fun `a recovered set carries no silence word`() = runTest {
        val set = ready(recovery(FakeSessionDao()).prepare(journaled()))
        assertEquals(SensorRole.B, set.sensors?.expected?.first(), "the fixture no longer arms b")
        assertEquals(emptyMap(), set.sensors?.silent)
        assertNull(set.sensors?.soleSilent)
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

    /**
     * A sensor-counted set whose analysis found no rep is refused, not
     * written as a 0-rep set (#100 review round 2, Lens A B2). The pulley
     * declaration withholds the fallen-back analysis (MOUNT_NOT_DECLARED)
     * over a full stream. The live count died with the process and a
     * sensor-counted set journals no mark, so nobody counted it, and
     * `"reps": 0` beside `"repsSource": "analysis"` would publish a count of
     * zero nobody made. The listing cannot run the analysis and still
     * offers the set; the answer changes at recover, and the capture stays
     * on the card.
     */
    @Test
    fun `a sensor-counted set whose analysis found no rep is refused and kept`() = runTest {
        val pulley = squat.copy(travelRatio = 2.0)
        val sensorFacts =
            facts.copy(
                exercise = pulley,
                geometry = SetGeometryPolicy.describe(pulley, null),
                counter = RepCounter.SENSOR,
            )
        val orphan = journaled(header = header(facts = sensorFacts), marks = emptyList())
        val dao = ownSession(endedAtMs = null)
        val recovery = recovery(dao)
        assertNull(recovery.refusal(orphan), "the listing refused a set it cannot yet judge")
        assertEquals(
            RecoveryOutcome.Refused(RecoveryRefusal.NOTHING_TO_COUNT),
            recovery.recover(orphan, writeOutstanding = false),
        )
        assertEquals(2, dao.sets.size, "a set nobody counted was written")
        assertTrue(orphan.directory.exists(), "the capture went with nothing written")
    }

    /**
     * A capture refused at recover whose set is already a row answers
     * AlreadyStored and goes (#100 review round 3, Lens A N2). The live
     * sensor-counted set's analysis withheld, its row landed with the live
     * count, and the process died before the journal went. Re-analysed here
     * it finds no rep, but the set is in History: answering NOTHING_TO_COUNT,
     * "No count of its reps survived", would be false and would keep the
     * capture on the card for good.
     */
    @Test
    fun `a capture refused at recover whose set is already stored goes, not refused`() = runTest {
        val pulley = squat.copy(travelRatio = 2.0)
        val sensorFacts =
            facts.copy(
                exercise = pulley,
                geometry = SetGeometryPolicy.describe(pulley, null),
                counter = RepCounter.SENSOR,
            )
        val dao = ownSession(endedAtMs = null)
        dao.sets += row(3, 7, 2, startedAtMs, startedAtMs + 50_000L)
        val orphan = journaled(header = header(facts = sensorFacts), marks = emptyList())
        assertEquals(RecoveryOutcome.AlreadyStored, recovery(dao).recover(orphan, writeOutstanding = false))
        assertEquals(3, dao.sets.size, "a stored set was written again")
        assertFalse(orphan.directory.exists(), "the capture of a stored set was kept")
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
