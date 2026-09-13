package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.GeometrySources
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.StartPhase
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Both export writers say WHOSE COUNT a set's `reps` figure is (#286).
 *
 * BOTH, for [RpeScalePublishedTest]'s reason: the session document is
 * serialised by kotlinx and the archive's manifest is assembled as text in a
 * different function, so a key wired into one of them publishes half a record
 * and which half depends on which file the coach opened.
 *
 * ## What a reader loses without it
 *
 * `reps` is one integer with four possible authors -- the sensor live, the
 * lifter's taps, the cadence guide's schedule, and the batch segmenter after
 * the set -- and before this key only `repsManual` separated them, true both
 * for a tally the lifter kept and for a correction of a count something else
 * made.
 *
 * ## The derivation, exercised through the real exporter
 *
 * The word is not stored. Each row here varies only what the derivation reads
 * -- `liveReps`, `repsManual`, the timed marker, the frozen `tempo` and the
 * frozen geometry's kind -- and asserts the word that comes out of the real
 * exporter rather than out of `RepsSourcePolicy` directly. `RepsSourcePolicyTest`
 * in `:core:model` covers the rule over its whole table; what is checked here is
 * the WIRING, including which column the exporter reads for "this set is
 * measured in seconds" and where it reads the kind that says whether a cadence
 * ran at all.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class RepsSourcePublishedTest {
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

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

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

    private val json = Json { ignoreUnknownKeys = true }

    /** One rep, so the set publishes an ordinary summary and nothing is empty. */
    private val oneRep =
        SetAnalysis(
            reps =
            listOf(
                RepAnalysis(
                    index = 1,
                    eccS = 2.0,
                    bottomPauseS = null,
                    conS = 1.0,
                    topPauseS = null,
                    meanConVelMps = 0.42,
                    peakConVelMps = 0.61,
                    meanEccVelMps = -0.3,
                    peakEccVelMps = -0.5,
                    romM = 0.55,
                    peakPowerW = null,
                ),
            ),
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = null,
            verdicts = emptyList(),
        )

    /**
     * The frozen geometry of a set of this kind, as the row stores it.
     *
     * Only [ResolvedGeometry.kind] is read by anything this file asserts; the
     * rest is a plausible barbell set so that the geometry block the exporter
     * publishes beside the counter word is a real one.
     */
    private fun geometryJson(kind: ExerciseKind) = json.encodeToString(
        ResolvedGeometry.serializer(),
        ResolvedGeometry(
            startsWith = StartPhase.ECCENTRIC,
            concentricUp = true,
            horizontal = false,
            sensorOnStack = false,
            sensorInverted = false,
            travelRatio = 1.0,
            kind = kind,
            bodyweight = false,
            sources =
            GeometrySources(
                startsWith = GeometrySource.SEEDED,
                concentric = GeometrySource.SEEDED,
                plane = GeometrySource.SEEDED,
                kind = GeometrySource.SEEDED,
                travelRatio = GeometrySource.SEEDED,
            ),
        ),
    )

    private fun row(
        liveReps: Int? = null,
        repsManual: Boolean = false,
        tempo: String? = null,
        actualDurationS: Int? = null,
        actualReps: Int = 5,
        geometryKind: ExerciseKind? = null,
    ) = SetRecordEntity(
        id = 8L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "deadlift",
        exerciseName = "Deadlift",
        loadKg = 120.0,
        actualReps = actualReps,
        repsManual = repsManual,
        liveReps = liveReps,
        plannedReps = 5,
        tempo = tempo,
        actualDurationS = actualDurationS,
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), oneRep),
        geometryJson = geometryKind?.let(::geometryJson),
    )

    private fun repositoryFor(row: SetRecordEntity): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_342_000_000L, endedAtMs = 1_788_343_100_000L)
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
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.53").buildZip(1L)!!
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

    private fun JsonObject.word(): String? = this["repsSource"]?.jsonPrimitive?.content

    private fun JsonObject.live(): Int? = this["liveReps"]?.jsonPrimitive?.content?.toInt()

    /** The case the issue exists for: a straight-reps set the sensor counted. */
    @Test
    fun `a sensor-counted set publishes the sensor as its counter in both writers`() = runTest {
        val row = row(liveReps = 5)
        assertEquals("sensor", setObject(row).word())
        assertEquals("sensor", manifestSet(row).word())
        assertEquals(5, setObject(row).live())
        assertEquals(5, manifestSet(row).live())
    }

    /**
     * A correction says so, and what it corrected is published beside it.
     *
     * This is the pair the first deadlift session is read for: the hand count
     * scored against what the sensor called, on a set where the lifter
     * disagreed.
     */
    @Test
    fun `a corrected sensor count publishes corrected and keeps the sensor's figure`() = runTest {
        val row = row(liveReps = 5, repsManual = true, actualReps = 6)
        assertEquals("corrected", setObject(row).word())
        assertEquals("corrected", manifestSet(row).word())
        assertEquals(5, setObject(row).live(), "the sensor's own figure is not published beside the correction")
        assertEquals(6, setObject(row).getValue("reps").jsonPrimitive.content.toInt())
    }

    /** The lifter's own tally. */
    @Test
    fun `a hand-counted set publishes manual and no live figure`() = runTest {
        val row = row(repsManual = true)
        assertEquals("manual", setObject(row).word())
        assertEquals("manual", manifestSet(row).word())
        assertNull(setObject(row).live(), "a set with no live counter published a live count")
        assertNull(manifestSet(row).live(), "a set with no live counter published a live count")
    }

    /**
     * The guide's count is not the lifter's, and the word says which.
     *
     * Without this word a guided set would read `manual`, crediting the lifter
     * with a tally they never kept: the metronome calls each rep on its own
     * schedule whether or not the lifter followed it.
     */
    @Test
    fun `a guided set publishes the metronome as its counter`() = runTest {
        val row = row(repsManual = true, tempo = "3010", geometryKind = ExerciseKind.DYNAMIC)
        assertEquals("metronome", setObject(row).word())
        assertEquals("metronome", manifestSet(row).word())
    }

    /**
     * A row with NO stored geometry reads its tempo as the guide, which is the
     * one collapse the derivation cannot avoid.
     *
     * Such a row does not say what kind of exercise it was, so the tempo is all
     * there is to read -- and a tempo'd row almost always was guided. This is
     * the fixture the pin above used until the geometry was added to it, and it
     * is kept as its own case rather than folded back in: the two rows publish
     * the same word for different reasons, and only one of them is a
     * measurement.
     */
    @Test
    fun `a tempo on a row with no stored geometry still publishes the metronome`() = runTest {
        val row = row(repsManual = true, tempo = "3010")
        assertNull(row.geometryJson, "the fixture is not the no-geometry shape")
        assertEquals("metronome", setObject(row).word())
        assertEquals("metronome", manifestSet(row).word())
    }

    /**
     * AN EXPLOSIVE LIFT'S TEMPO IS NOT A CADENCE, so the count is the lifter's.
     *
     * `LeadInPolicy.prepCase` gives an explosive lift no cadence whatever tempo
     * is written on it -- it is judged on peak velocity and is deliberately
     * unpaced -- so `CountingPolicy.counterFor` hands the set to the lifter,
     * the `+1 REP` taps ARE its count, and `repsManual` is true because a
     * person stated the figure. Reading the frozen tempo alone publishes
     * `metronome` for a set no guide ever counted, and a coach reading it
     * credits a schedule that never ran and discounts the one count on the row
     * that a person actually made.
     *
     * The kind is on the row: `geometryJson` carries `ResolvedGeometry.kind`,
     * and `setExport` already decodes it for `velocityLossRegime`.
     */
    @Test
    fun `an explosive lift carrying a tempo publishes the lifter as its counter`() = runTest {
        val row = row(repsManual = true, tempo = "3010", geometryKind = ExerciseKind.EXPLOSIVE)
        assertEquals("manual", setObject(row).word(), "an unpaced explosive set published the guide's word")
        assertEquals("manual", manifestSet(row).word(), "an unpaced explosive set published the guide's word")
    }

    /**
     * The same set with the sensor on is the sensor's, which is the near
     * neighbour: the fix above must not reach a shape the live count settles
     * one branch earlier.
     */
    @Test
    fun `an explosive lift the sensor counted is still the sensor's`() = runTest {
        val row = row(liveReps = 3, tempo = "3010", geometryKind = ExerciseKind.EXPLOSIVE)
        assertEquals("sensor", setObject(row).word())
        assertEquals("sensor", manifestSet(row).word())
    }

    /**
     * A row written before the live count existed publishes `analysis`, which
     * is what its figure was.
     */
    @Test
    fun `a row with neither figure publishes the batch segmenter as its counter`() = runTest {
        val row = row()
        assertEquals("analysis", setObject(row).word())
        assertEquals("analysis", manifestSet(row).word())
    }

    /**
     * A timed set publishes NO word, in both writers.
     *
     * Nothing counted reps on a hold, and the marker the exporter reads for
     * that is the `actualDurationS` column rather than the published duration:
     * a set abandoned in its prep publishes no duration and is still a timed
     * set.
     */
    @Test
    fun `a timed set publishes no counter at all`() = runTest {
        val row = row(actualDurationS = 47, actualReps = 0)
        assertNull(setObject(row).word(), "a hold published a rep counter")
        assertNull(manifestSet(row).word(), "a hold published a rep counter")
    }

    /**
     * A timed set that ended in its prep still publishes no word.
     *
     * The near neighbour of the rule above: `AbandonedSetPolicy` withholds the
     * duration on such a set, so an exporter that read the PUBLISHED duration
     * as the timed marker would call it a rep set and name a counter.
     */
    @Test
    fun `a timed set abandoned in its prep still publishes no counter`() = runTest {
        val row = row(actualDurationS = 0, actualReps = 0).copy(workBegan = false, prepS = 5)
        assertNull(setObject(row)["duration_s"], "the fixture is not the abandoned-in-prep shape")
        assertNull(setObject(row).word(), "an abandoned hold published a rep counter")
        assertNull(manifestSet(row).word(), "an abandoned hold published a rep counter")
    }

    /** Every word the derivation can produce is one the published schema allows. */
    @Test
    fun `every word this exporter can publish is in the published vocabulary`() = runTest {
        val words =
            listOf(
                row(liveReps = 5),
                row(liveReps = 5, repsManual = true, actualReps = 6),
                row(repsManual = true),
                row(repsManual = true, tempo = "3010"),
                row(),
            ).map { setObject(it).word() }
        assertEquals(listOf("sensor", "corrected", "manual", "metronome", "analysis"), words)
        assertTrue(
            words.all { it in SessionExport.VALID_REPS_SOURCES },
            "the exporter publishes a word the schema does not allow: $words",
        )
    }

    /**
     * The near neighbour: `reps` and `repsManual` go on being published exactly
     * as they were. These keys explain that integer rather than replacing it,
     * and a reader that ignores them must read the document as before.
     */
    @Test
    fun `the recorded count and the manual flag are unchanged beside the new keys`() = runTest {
        val set = setObject(row(liveReps = 5, repsManual = true, actualReps = 6))
        assertEquals(6, set.getValue("reps").jsonPrimitive.content.toInt(), "reps moved")
        assertEquals(true, set.getValue("repsManual").jsonPrimitive.content.toBoolean(), "repsManual moved")
    }
}
