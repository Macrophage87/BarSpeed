package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.LiftDirection
import com.macrophage.barspeed.dsp.MovementPlane
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the app published for a set whose analysed unit spent the set in a bag,
 * and what the stream it discarded was worth (issue #207).
 *
 * ## The fixture and where every fact about it comes from
 *
 * `field-backsquat-4011-6rep-s36-set02-imu-a.csv` and its `-cues.csv` are set
 * 02 of field session 36, copied byte for byte out of that session's raw
 * export -- `set02_back_squat_imu-a.csv`, md5
 * 1f159d8a4f21b9708fb31546130bce86, and `set02_back_squat_cues.csv`, md5
 * b0c50418997f396130e871f16b18a4e4. Nothing was re-encoded, resampled or
 * trimmed. Both digests are of the ARCHIVE file and of the blob git stores,
 * which are LF; `core.autocrlf=true` with no tracked `.gitattributes` means a
 * Windows working copy is CRLF and hashes differently, so do not read either
 * digest as a check on a checked-out file.
 *
 * Every declaration below is read from that session's own `meta.json` rather
 * than from a filename or from memory: `appVersion` 0.1.47, sensor WitMotion
 * WT901BLECL, exercise `back_squat`, `load_kg` 52.163122551154075,
 * `tempoPrescribed` "4011", `startsWith` eccentric, `concentric` up, `plane`
 * vertical, `sensorOnStack` false, `sensorInverted` false, `travelRatio` 1.0,
 * `kind` dynamic, `reps` 6 with `repsManual` true. The same descriptor records
 * `sensorsArmed` 2, `sensorRolesExpected` ["b", "a"] and `analysedRole` "b",
 * while its `sensors` array lists role "a" alone and its `files` list carries
 * `set02_back_squat_imu-a.csv` and no `imu-b.csv`. That pair of facts is the
 * defect in one line: the role the app analysed is not the role that streamed.
 *
 * It lives in this module rather than beside `core/dsp`'s field corpus because
 * what it is here to exercise is the EXPORT -- which role the published
 * document names and whether its `summary` is populated -- and because
 * `core/dsp`'s corpus tests enumerate their own resource directory and pin
 * corpus-wide rep-counting totals over it. This capture under-counts, and
 * those totals belong to issues #94 and #138 rather than to this one.
 *
 * ## What this file pins at c0
 *
 * The shipped path, reproduced end to end: an armed role that captured nothing
 * feeding an analyzer that is never called, a placeholder analysis, and an
 * export naming `analysedRole` "b" beside a `present` of ["a"] with an empty
 * `summary`. Beside it, what the discarded stream was worth.
 *
 * It deliberately does NOT pin kinematics beyond "there is a summary". The
 * capture resolves four detections against the metronome's six calls, and that
 * under-count is #94/#138's subject. Restoring a summary here restores *a*
 * summary, not a trustworthy one.
 *
 * ## What this file adds at c2
 *
 * DIFFERENTIALS: the same capture recorded through the fallback, publishing a
 * summary from the role that streamed and saying that it moved. They fail at
 * the commit that introduces them.
 *
 * `as shipped, ...` stays and stops being a statement about what this build
 * records: the exporter re-decides nothing, so a row an earlier build already
 * wrote keeps publishing exactly what it says, and that is what that case now
 * pins. Nothing here repairs field-36 -- its analyses are frozen in its rows,
 * and re-pointing an export at another stream would publish figures under a
 * role that did not produce them.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * these fakes stand in for it, so what is verified is the repository's and the
 * exporter's own mapping and nothing about what the database did with it.
 */
class AnalysedRoleFallbackTest {
    /**
     * A DAO that hands back what was written to it, so one test can drive
     * `recordSet` and then export the row it produced.
     *
     * The other sensor fakes in this module either record inserts or serve a
     * fixed row; this issue is about a disagreement between what the record
     * path stores and what the export path reads, and a fake that does one
     * half cannot show it.
     */
    private class RoundTripDao : SessionDao {
        private val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L)
        val sets = mutableListOf<SetRecordEntity>()
        val streams = mutableListOf<RawStreamEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long {
            sets += set.copy(id = 5L)
            return 5L
        }

        override suspend fun insertRawStream(stream: RawStreamEntity): Long {
            streams += stream.copy(id = streams.size + 1L)
            return streams.size.toLong()
        }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = sets

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(sets)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams.filter { it.setId == setId }

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

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    /** Set 02's geometry, every field read from field-36's `meta.json`. */
    private val squat = LiftDirection(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
        sensorInverted = false,
        travelRatio = 1.0,
    )

    /** `load_kg` from the same descriptor, unrounded. */
    private val loadKg = 52.163122551154075

    /** `sensorRolesExpected` from the same descriptor, in its published order. */
    private val armedRoles = listOf(SensorRole.B, SensorRole.A)

    /** What the app stored when it began the set: `analysedRole` "b". */
    private val armed =
        RecordedSensors(count = 2, expected = armedRoles, analysed = SensorRole.B)

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes()
            .decodeToString()

    /** Role a's capture: the stream that arrived. */
    private fun roleA(): List<ImuSample> = ImuCsv.decode(resource("field-backsquat-4011-6rep-s36-set02-imu-a.csv"))

    /** The set's own cue track, on the same epoch-ms clock as the samples. */
    private fun cues(): List<VoiceCue> = CueCsv.decode(resource("field-backsquat-4011-6rep-s36-set02-cues.csv"))

    /**
     * What the record path does with a set's samples, mirrored rather than
     * executed.
     *
     * `RecordViewModel.runSetWrite` lives in `:app`, where no test on the CI
     * path reaches `RecordViewModel`, so this reproduces its two decisions:
     * analyse only from
     * eight samples up, and store a placeholder analysis otherwise. Set 02 was
     * counted by hand -- `repsManual` true -- so the placeholder it would have
     * stored is the manual one.
     */
    private fun analysisOf(samples: List<ImuSample>): SetAnalysis = if (samples.size >= 8) {
        SetAnalyzer.analyze(samples, squat, loadKg = loadKg, cues = cues())
    } else {
        SetAnalysis(emptyList(), 0.0, null, null, listOf("Reps counted manually — no bar sensor."))
    }

    private fun completedSet(analysed: List<ImuSample>, sensors: RecordedSensors, secondary: SecondaryCapture) =
        CompletedSet(
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = loadKg,
            plannedLoadKg = loadKg,
            plannedReps = 6,
            manualReps = 6,
            tempo = "4011",
            targetMeanConVelMps = null,
            velocityLossStopPct = null,
            plannedRestS = null,
            plannedPrepS = 10,
            prepS = 10,
            startedAtMs = 1_788_251_947_376L,
            endedAtMs = 1_788_251_999_643L,
            analysis = analysisOf(analysed),
            imuSamples = analysed,
            hrSamples = emptyList(),
            voiceCues = cues(),
            sensors = sensors,
            secondary = secondary,
        )

    /**
     * The set as the shipped build recorded and then published it: pointed at
     * role b, which produced nothing, with role a's capture riding along as the
     * secondary stream.
     */
    private suspend fun publishedAsShipped(): JsonObject =
        publish(completedSet(emptyList(), armed, SecondaryCapture(SensorRole.A, roleA())))

    private suspend fun publish(set: CompletedSet): JsonObject {
        val dao = RoundTripDao()
        val repository = SessionRepository(dao, StubExerciseDao())
        repository.recordSet(sessionId = 1L, orderIdx = 0, set = set)
        val text = SessionExporter(repository, dispatcher = Dispatchers.Default).exportJson(1L, true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private fun JsonObject.roles(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the fixture is the set field-36's meta_json describes`() {
        val samples = roleA()
        assertEquals(5188, samples.size, "rows, against this set's own meta.json sensors[0].samples")
        assertEquals(
            99.36781609195401,
            SetAnalyzer.analyze(samples, squat, loadKg = loadKg, cues = cues()).sampleRateHz,
            "measured rate, against this set's own meta.json sensors[0].sampleRate_hz",
        )
        assertEquals(6, cues().count { it.cue == "Down" }, "metronome Down-cues, corroborating meta.json's reps 6")
    }

    @Test
    fun `the stream the app discarded resolves the summary the app published as empty`() {
        val analysis = SetAnalyzer.analyze(roleA(), squat, loadKg = loadKg, cues = cues())
        // Every figure in the published `summary` object is an average or an
        // extremum over this rep list, so an empty list is exactly what an
        // empty object is.
        assertTrue(analysis.reps.isNotEmpty(), "role a resolved no reps, so there would be no summary to restore")
        assertEquals(4, analysis.reps.size, "detections; the metronome called 6 -- that under-count is #94/#138")
        val meanConVel = analysis.reps.map { it.meanConVelMps }.average()
        assertTrue(meanConVel > 0.0, "mean concentric velocity is $meanConVel m/s, which is not a summary")
    }

    @Test
    fun `the role the app was pointed at is one the analyzer refuses outright`() {
        // Role b spent the set in a bag, so its buffer was empty. The analyzer
        // does not answer "no reps" for that -- it throws, and the only thing
        // between the record path and this throw is the eight-sample gate that
        // stores a placeholder instead. So the summary this set published as
        // {} was never computed from any stream at all.
        val thrown = assertFailsWith<IllegalArgumentException> {
            SetAnalyzer.analyze(emptyList(), squat, loadKg = loadKg, cues = cues())
        }
        assertEquals("Not enough samples (0)", thrown.message, "what the analyzer says about an absent stream")
    }

    /**
     * The record path's own composition, mirrored rather than executed.
     *
     * `armedCaptureOf` in `RecordViewModel.kt` builds the same four decisions,
     * in the same order, from a copy of this code rather than from this code:
     * the armed roles keyed to their buffers, the streamed roles by
     * [SensorCapturePolicy.present], the decision by
     * [SensorCapturePolicy.analysedStream], and the partner derived from the
     * corrected declaration. What is verified HERE is the composition, not
     * that `:app` performs it.
     *
     * That other half is `ArmedCaptureTest`, in `app/src/test/`, added in
     * round 4 of #207: it executes `armedCaptureOf` itself. The sentence this
     * KDoc used to carry -- that `armedCaptureOf` lives in `:app` where no
     * test on the CI path reaches `RecordViewModel`, so the record half is
     * compile- and lint-gated only -- was true when it was written and is not
     * true now. It is deleted rather than reworded.
     *
     * Nothing detects DRIFT between the two, and that has not changed: the
     * mirror already differs where `armedCaptureOf` falls back to the analysed
     * buffer on a null role and this returns an empty list, which no dual
     * declaration can reach.
     */
    private fun asRecorded(byRole: Map<SensorRole, List<ImuSample>>): CompletedSet {
        val streamed = SensorCapturePolicy.present(armedRoles, byRole.filterValues { it.isNotEmpty() }.keys)
        val decision = SensorCapturePolicy.analysedStream(armed.analysed, streamed)
        val sensors = armed.copy(analysed = decision.role, analysedFellBack = decision.fellBack)
        return completedSet(
            analysed = decision.role?.let { byRole[it] }.orEmpty(),
            sensors = sensors,
            secondary = SecondaryCapture(
                checkNotNull(sensors.secondaryRole) { "a dual declaration with no partner role" },
                byRole[sensors.secondaryRole].orEmpty(),
            ),
        )
    }

    @Test
    fun `as shipped, the analysed role is one that never streamed and the summary is empty`() = runTest {
        val set = publishedAsShipped()
        val sensors = set.getValue("sensors").jsonObject

        assertEquals("b", sensors.getValue("analysedRole").jsonPrimitive.content, "the role the DSP was pointed at")
        assertEquals(listOf("b", "a"), sensors.roles("expected"), "the roles the set armed")
        assertEquals(listOf("a"), sensors.roles("present"), "the roles whose stream reached the archive")
        assertEquals(
            emptyMap(),
            set.getValue("summary").jsonObject,
            "field-36 published summary {} on 13 of 14 sets; this is that document",
        )
    }

    @Test
    fun `a set whose armed role never streamed is analysed from the role that did`() = runTest {
        val set = publish(asRecorded(mapOf(SensorRole.B to emptyList(), SensorRole.A to roleA())))
        val sensors = set.getValue("sensors").jsonObject

        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content, "the role the figures came from")
        assertEquals(listOf("b", "a"), sensors.roles("expected"), "the roles the set armed are unchanged")
        assertEquals(listOf("a"), sensors.roles("present"), "the roles whose stream reached the archive")
        assertTrue(
            sensors.getValue("analysedFellBack").jsonPrimitive.content.toBoolean(),
            "the document never says the analysed role is not the one the set armed",
        )
    }

    @Test
    fun `the set field-36 published as empty publishes its summary`() = runTest {
        val set = publish(asRecorded(mapOf(SensorRole.B to emptyList(), SensorRole.A to roleA())))
        val summary = set.getValue("summary").jsonObject

        // Presence, not values. This capture under-counts -- four detections
        // against six called reps -- so the figures are #94/#138's problem and
        // pinning them here would read as a claim that they are trustworthy.
        assertTrue(summary.isNotEmpty(), "the summary is still empty, which is what field-36 published")
        listOf("meanConVel_mps", "peakConVel_mps", "meanRom_m", "peakPower_w").forEach { key ->
            assertTrue(key in summary, "the restored summary has no $key: ${summary.keys}")
        }
        assertEquals(4, set.getValue("repMetrics").jsonArray.size, "detections; the metronome called 6 (#94/#138)")
    }

    @Test
    fun `a set whose armed role did stream says it did not move`() = runTest {
        // The other half of the flag, and the reason it is a key rather than a
        // comparison: with the fallback in place the analysed role is present
        // in both cases, so `analysedRole in present` no longer separates
        // "analysed the preferred unit" from "analysed the only one there".
        val set = publish(asRecorded(mapOf(SensorRole.B to roleA(), SensorRole.A to emptyList())))
        val sensors = set.getValue("sensors").jsonObject

        assertEquals("b", sensors.getValue("analysedRole").jsonPrimitive.content, "the armed role streamed")
        assertEquals(listOf("b"), sensors.roles("present"))
        assertTrue(
            "analysedFellBack" !in sensors,
            "a set that did not fall back published the key anyway: $sensors",
        )
    }

    @Test
    fun `a set where nothing streamed keeps naming the role it armed`() = runTest {
        // No fallback, because there is nothing to fall back TO. Moving the
        // name here would say a unit was analysed when none was, and the
        // summary is empty because there was no stream rather than because the
        // app looked at the wrong one.
        val set = publish(asRecorded(mapOf(SensorRole.B to emptyList(), SensorRole.A to emptyList())))
        val sensors = set.getValue("sensors").jsonObject

        assertEquals("b", sensors.getValue("analysedRole").jsonPrimitive.content, "the role the set armed")
        assertEquals(emptyList(), sensors.roles("present"), "nothing reached the archive")
        assertTrue("analysedFellBack" !in sensors, "nothing streamed, so nothing was fallen back to: $sensors")
        assertEquals(emptyMap(), set.getValue("summary").jsonObject, "a summary from no stream at all")
    }
}
