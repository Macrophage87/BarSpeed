package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differentials for what a set that never entered its work phase publishes,
 * and for naming the author of a failure (#216, #169).
 *
 * EIGHT OF THE TWELVE FAIL WHEN THEY ARE WRITTEN. The columns exist and a row
 * carries them from the commit before this one, but both export writers still
 * read `actualDurationS` and `prepS` straight off the row, and neither has
 * ever had a line for `failedByLifter`. So a set abandoned during its lead-in
 * publishes `duration_s: 0` -- a measurement claim, because the writer drops
 * nulls and prints zeros -- and `prep_s`, the prep the app SET OUT to play,
 * on a set where the prep provably did not finish.
 *
 * THE FIXTURE IS FIELD-37 SET 13'S STORED SHAPE, not an invented one:
 * `actualDurationS` 0, `actualReps` 0, `failed` true, `plannedPrepS` 12,
 * `prepS` 12, no rpe and no limiter, spanning 1788343012005 to 1788343018340.
 * [AbandonedInPrepFixtureTest] carries the provenance and the capture-side
 * pins; this file is about the writers.
 *
 * The FOUR that pass here are kept for what they will catch later. An ordinary
 * completed set must publish exactly what it published before -- that is every
 * set in the archive and the case a rule keyed on the stored zeros rather than
 * on the phase would move; a row that predates database v15 must be untouched,
 * which is the whole historical corpus; a set that did not fail must go on
 * naming no author, since a `false` there would be a derived failure that
 * never happened; and an abandoned set must keep publishing the prescription,
 * or withholding the measured figures would leave a reader unable to say a
 * prep was asked for at all.
 *
 * BOTH WRITERS, for [SessionExportWarmupMarkTest]'s reason: the session
 * document is serialised and the archive's manifest is assembled as text, in a
 * different function, and a change wired into one of them publishes half a
 * record.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportAbandonedSetTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

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

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

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

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private val noReps =
        SetAnalysis(
            reps = emptyList(),
            sampleRateHz = 100.0,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    /** Field-37 set 13's stored shape, with the two capture facts varied. */
    private fun row(
        workBegan: Boolean?,
        failedByLifter: Boolean?,
        failed: Boolean = true,
        durationS: Int? = 0,
        reps: Int = 0,
    ) = SetRecordEntity(
        id = 13L,
        sessionId = 1L,
        orderIdx = 12,
        exerciseId = "rope_dead_hang",
        exerciseName = "Rope Dead Hang",
        loadKg = 37.0513352482438,
        actualReps = reps,
        actualDurationS = durationS,
        plannedDurationS = 20,
        failed = failed,
        failedByLifter = failedByLifter,
        plannedPrepS = 12,
        prepS = 12,
        workBegan = workBegan,
        startedAtMs = 1_788_343_012_005L,
        endedAtMs = 1_788_343_018_340L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
    )

    private fun repositoryFor(row: SetRecordEntity): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_343_000_000L, endedAtMs = 1_788_343_100_000L)
        return SessionRepository(FakeSessionDao(session = session, rows = listOf(row)), FakeExerciseDao())
    }

    /** The one set of the SESSION DOCUMENT. */
    private suspend fun setObject(row: SetRecordEntity): JsonObject {
        val exporter = SessionExporter(repositoryFor(row), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    /** The one set of the RAW ARCHIVE'S manifest. */
    private suspend fun manifestSet(row: SetRecordEntity): JsonObject {
        val repo = repositoryFor(row)
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.50").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return Json.parseToJsonElement(entries.getValue("meta.json"))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    // ---- the differentials -------------------------------------------------

    /**
     * The case the issue exists for: a hold that never started publishes no
     * duration.
     *
     * `duration_s: 0` reads as "a 20-second rope dead hang was attempted and
     * achieved 0 seconds", on a day the same lift held 20 s at RPE 9. The
     * stored 0 is not a measurement and no reader can tell.
     */
    @Test
    fun `a set abandoned in its lead-in publishes no duration`() = runTest {
        assertFalse(
            "duration_s" in setObject(row(workBegan = false, failedByLifter = false)),
            "a duration nobody measured reached the session document",
        )
    }

    /**
     * And no prep, because the stored figure is the prescription and the prep
     * provably did not finish.
     *
     * `prep_s` matches the measured window to within 16 ms on all twelve of
     * field-37's sets whose prep completed, which is what trains a reader to
     * read it as elapsed.
     */
    @Test
    fun `a set abandoned in its lead-in publishes no prep`() = runTest {
        assertFalse(
            "prep_s" in setObject(row(workBegan = false, failedByLifter = false)),
            "a prep that never finished reached the session document",
        )
    }

    /**
     * The prescription still publishes, because it is still true.
     *
     * Without it, withholding `prep_s` would leave a reader unable to say a
     * prep was even prescribed, and the absence would read as "no voice guide
     * ran" -- which is the state the schema already assigns to a missing
     * `plannedPrep_s`.
     */
    @Test
    fun `an abandoned set still publishes the prep the plan prescribed`() = runTest {
        val set = setObject(row(workBegan = false, failedByLifter = false))
        assertEquals(12, set["plannedPrep_s"]?.jsonPrimitive?.content?.toInt(), "the prescription was withheld too")
        assertEquals(20, set["plannedDuration_s"]?.jsonPrimitive?.content?.toInt(), "the target hold was withheld")
    }

    /**
     * The document SAYS the set never started, rather than leaving two
     * absences to be interpreted.
     *
     * Absence alone is ambiguous: a rep set publishes no `duration_s` either,
     * and a set that ran no voice guide publishes no `prep_s`. Without this
     * key the fix would replace a false statement with a silence that reads
     * like an ordinary set.
     */
    @Test
    fun `an abandoned set says so`() = runTest {
        assertEquals(
            true,
            setObject(row(workBegan = false, failedByLifter = false))["abandonedInPrep"]
                ?.jsonPrimitive?.content?.toBoolean(),
            "nothing in the document says the set never started",
        )
    }

    /**
     * The failure on such a set is named as the app's, not the lifter's.
     *
     * A set abandoned in its lead-in is marked failed BY DERIVATION -- END SET
     * EARLY passes no rating at all -- and until this key existed it was
     * indistinguishable from a set the lifter tapped as failed. On field-37
     * that is sets 2, 3, 4, 8 and 10 against set 13.
     */
    @Test
    fun `a derived failure publishes false rather than nothing`() = runTest {
        val set = setObject(row(workBegan = false, failedByLifter = false))
        assertEquals(true, set["failed"]?.jsonPrimitive?.content?.toBoolean(), "the failure itself stopped publishing")
        assertEquals(
            false,
            set["failedByLifter"]?.jsonPrimitive?.content?.toBoolean(),
            "a derived failure is still indistinguishable from a tapped one",
        )
    }

    /** The other direction: a failure the lifter called says so. */
    @Test
    fun `a tapped failure says the lifter called it`() = runTest {
        assertEquals(
            true,
            setObject(row(workBegan = true, failedByLifter = true, durationS = 14))["failedByLifter"]
                ?.jsonPrimitive?.content?.toBoolean(),
            "the lifter's own verdict never reached the wire",
        )
    }

    /**
     * A set that did not fail names no author.
     *
     * `failedByLifter: false` on a set with no `failed` would read as a
     * derived failure that never happened, which is a new false statement in
     * place of the old one.
     */
    @Test
    fun `a set that did not fail publishes no author`() = runTest {
        val set = setObject(row(workBegan = true, failedByLifter = false, failed = false, durationS = 20, reps = 1))
        assertFalse("failed" in set, "a set that did not fail published a failure")
        assertFalse("failedByLifter" in set, "a set that did not fail named an author")
    }

    /**
     * The archive's manifest withholds the same two figures.
     *
     * Two writers, one fact. The manifest is the document a reader who opens
     * the CSVs alone has, and it published `"duration_s": 0, "prep_s": 12` for
     * field-37 set 13.
     */
    @Test
    fun `the raw archive's manifest publishes neither figure for an abandoned set`() = runTest {
        val set = manifestSet(row(workBegan = false, failedByLifter = false))
        assertFalse("duration_s" in set, "the manifest publishes a duration nobody measured")
        assertFalse("prep_s" in set, "the manifest publishes a prep that never finished")
        assertEquals(12, set["plannedPrep_s"]?.jsonPrimitive?.content?.toInt(), "the manifest dropped the prescription")
    }

    /** The manifest says it too, in the same words. */
    @Test
    fun `the raw archive's manifest says the set never started`() = runTest {
        assertEquals(
            true,
            manifestSet(row(workBegan = false, failedByLifter = false))["abandonedInPrep"]
                ?.jsonPrimitive?.content?.toBoolean(),
            "the manifest leaves the abandonment unsaid",
        )
    }

    /** And carries the author of the failure. */
    @Test
    fun `the raw archive's manifest names the author of the failure`() = runTest {
        assertEquals(
            false,
            manifestSet(row(workBegan = false, failedByLifter = false))["failedByLifter"]
                ?.jsonPrimitive?.content?.toBoolean(),
            "the manifest cannot tell a derived failure from a tapped one",
        )
    }

    /**
     * THIS ONE PASSES TODAY and is here to fail later.
     *
     * An ordinary completed timed set is every hold in the archive. It must
     * publish exactly what it published before, and it is the case a rule
     * keyed on the stored zeros -- rather than on the phase -- would move.
     */
    @Test
    fun `a completed set publishes what it always did`() = runTest {
        val set = setObject(row(workBegan = true, failedByLifter = null, failed = false, durationS = 20, reps = 1))
        assertEquals(20, set["duration_s"]?.jsonPrimitive?.content?.toInt(), "a measured hold stopped publishing")
        assertEquals(12, set["prep_s"]?.jsonPrimitive?.content?.toInt(), "a completed prep stopped publishing")
        assertFalse("abandonedInPrep" in set, "a completed set was declared abandoned")
    }

    /**
     * THIS ONE PASSES TODAY TOO, and it is the more important of the pair.
     *
     * A row written before database v15 carries null for both facts, and that
     * is the whole historical corpus. It must be untouched: a rule folding
     * null in with false would strike `duration_s` off every timed set the
     * lifter has ever recorded and declare the archive abandoned.
     */
    @Test
    fun `a row that predates the columns publishes exactly what it always did`() = runTest {
        val set = setObject(row(workBegan = null, failedByLifter = null))
        assertEquals(0, set["duration_s"]?.jsonPrimitive?.content?.toInt(), "a legacy row's duration was withheld")
        assertEquals(12, set["prep_s"]?.jsonPrimitive?.content?.toInt(), "a legacy row's prep was withheld")
        assertFalse("abandonedInPrep" in set, "a legacy row was declared abandoned on no evidence")
        assertFalse("failedByLifter" in set, "a legacy row was given an author it never recorded")
        assertTrue(set["failed"]?.jsonPrimitive?.content?.toBoolean() == true, "a legacy row's failure was dropped")
    }
}
