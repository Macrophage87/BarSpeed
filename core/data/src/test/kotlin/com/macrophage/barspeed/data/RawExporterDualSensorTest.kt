package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.VelocityEstimator
import com.macrophage.barspeed.model.DualShortfall
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The raw archive of a set recorded with two accelerometers (#156).
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it,
 * and the first one fails hardest: `buildZip` names each entry
 * `set%02d_<exercise>_<kind>.csv` from the kind alone, so two `imu` rows on
 * one set produce two entries with the SAME name -- and `buildSetDescriptor`
 * receives only the last IMU text the loop saw, so the manifest would describe
 * one of two streams while looking complete. That near miss is the reason this
 * file exists before the writer does.
 *
 * A separate file from [RawExporterTest] for the reason every other split in
 * this module has: that class is long and detekt's LargeClass is not among the
 * rules this repo disables.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class RawExporterDualSensorTest {
    private class FakeSessionDao(
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        private val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L)

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

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

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val noReps = SetAnalysis(emptyList(), 0.0, null, null, emptyList())

    /**
     * Two captures of deliberately different length and cadence, and different
     * roll sweeps.
     *
     * Every figure the manifest publishes per stream -- sample count, measured
     * rate, roll excursion -- therefore differs between them, so a descriptor
     * that took one stream's figure and labelled it with the other's role
     * cannot pass. This is the wrong-pair guard, built into the fixture rather
     * than asserted about it.
     */
    private fun samples(count: Int, stepMs: Long, rollStep: Double) = (0 until count).map { i ->
        ImuSample(1_000L + i * stepMs, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, i * rollStep, -20.0, 30.0)
    }

    private val analysedSamples = samples(count = 100, stepMs = 10L, rollStep = 0.5)
    private val secondarySamples = samples(count = 60, stepMs = 20L, rollStep = 0.1)

    private fun imuStream(id: Long, role: String?, samples: List<ImuSample>, storedRate: Double?) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples)),
        sampleRateHz = storedRate,
        role = role,
    )

    private fun rowWithSensorsJson(sensorsJson: String) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = sensorsJson,
    )

    /**
     * The same manifest path fed a stored declaration VERBATIM, for the reason
     * `SessionExportSensorsTest.setObjectFromStoredJson` exists: it is the only
     * way to stand a row in front of the current exporter whose shape the
     * current data class does not have.
     */
    private suspend fun descriptorFromStoredJson(sensorsJson: String, streams: List<RawStreamEntity>): JsonObject {
        val dao = FakeSessionDao(listOf(rowWithSensorsJson(sensorsJson)), mapOf(5L to streams))
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.44").buildZip(1L)!!
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return Json.parseToJsonElement(out.getValue("meta.json"))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private fun row(sensors: RecordedSensors?) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = sensors?.let { json.encodeToString(RecordedSensors.serializer(), it) },
    )

    private suspend fun entriesOf(sensors: RecordedSensors?, streams: List<RawStreamEntity>): Map<String, String> {
        val dao = FakeSessionDao(listOf(row(sensors)), mapOf(5L to streams))
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.44").buildZip(1L)!!
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return out
    }

    private suspend fun descriptor(sensors: RecordedSensors?, streams: List<RawStreamEntity>): JsonObject =
        Json.parseToJsonElement(entriesOf(sensors, streams).getValue("meta.json"))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject

    private val dual =
        RecordedSensors(
            count = 2,
            expected = listOf(SensorRole.A, SensorRole.B),
            analysed = SensorRole.A,
        )

    private fun dualStreams() = listOf(
        imuStream(1L, "a", analysedSamples, storedRate = 98.5),
        imuStream(2L, "b", secondarySamples, storedRate = null),
    )

    private fun JsonObject.num(key: String): Double? = get(key)?.jsonPrimitive?.content?.toDouble()

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    // ---- both streams reach the archive --------------------------------------

    /**
     * Two captures, two files, each named with the role that produced it.
     *
     * The hardest failure in this file at the commit that introduces it: today
     * the entry name is built from the kind alone, so both streams are written
     * as `set01_bench_press_imu.csv` and the archive either refuses the
     * duplicate entry outright or keeps one of the two. A stream whose
     * provenance is not in its name is unanalysable, which is the whole
     * requirement.
     */
    @Test
    fun `a dual set's captures are two files, each named with its role`() = runTest {
        val entries = entriesOf(dual, dualStreams())

        assertEquals(
            setOf(
                "set01_bench_press_imu-a.csv",
                "set01_bench_press_imu-b.csv",
                "meta.json",
                "session.json",
            ),
            entries.keys,
        )
        assertEquals(
            analysedSamples.size,
            ImuCsv.decode(entries.getValue("set01_bench_press_imu-a.csv")).size,
        )
        assertEquals(
            secondarySamples.size,
            ImuCsv.decode(entries.getValue("set01_bench_press_imu-b.csv")).size,
        )
    }

    /**
     * A one-sensor set's CSV keeps the name it has always had.
     *
     * The role is appended only when there is one, so an archive of a
     * single-sensor session is byte-identical to what earlier versions wrote.
     * The control this file is read against.
     */
    @Test
    fun `a one-sensor set's capture keeps its unadorned filename`() = runTest {
        val entries = entriesOf(null, listOf(imuStream(1L, null, analysedSamples, storedRate = 98.5)))

        assertTrue("set01_bench_press_imu.csv" in entries.keys, "the one-sensor filename moved")
    }

    // ---- the manifest --------------------------------------------------------

    /**
     * Each role gets its own manifest entry, with figures measured from ITS
     * OWN stream.
     *
     * The fixture's two captures differ in length, cadence and roll sweep on
     * purpose, so a descriptor that copied one stream's rate onto the other's
     * role cannot pass. A rate must be measured against the stream it labels.
     */
    @Test
    fun `the manifest maps each role to its own file, sample count and measured rate`() = runTest {
        val set = descriptor(dual, dualStreams())

        val sensors = set.getValue("sensors").jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), sensors.map { it.text("role") })
        assertEquals(
            listOf("set01_bench_press_imu-a.csv", "set01_bench_press_imu-b.csv"),
            sensors.map { it.text("file") },
        )
        assertEquals(listOf(100, 60), sensors.map { it.getValue("samples").jsonPrimitive.int })
        val expectedRates =
            listOf(analysedSamples, secondarySamples).map { stream ->
                VelocityEstimator.measuredSampleRateOrNull(
                    stream.size,
                    (stream.last().timestampMs - stream.first().timestampMs) / 1000.0,
                )
            }
        assertEquals(expectedRates, sensors.map { it.num("sampleRate_hz") })
    }

    /**
     * DIFFERENTIAL, issue #198. The manifest states the armed count and which
     * role was analysed, and no longer states a planned one.
     *
     * `the manifest declares the planned count, the armed count and the
     * analysed role` stood here. `sensorsPlanned` had exactly one source, the
     * plan's declaration, and that declaration no longer decides anything, so
     * the key would have to keep publishing a default that reads as a coach's
     * intention. It goes.
     *
     * Flat keys here and a nested object in `session.json`, which is what the
     * two documents already do with geometry and with the time zone; the names
     * differ so that one key never means a number in one artifact and an
     * object in the other.
     */
    @Test
    fun `the manifest declares the armed count and the analysed role, and no planned one`() = runTest {
        val set = descriptor(dual, dualStreams())

        assertNull(set["sensorsPlanned"], "the manifest still publishes a planned count nobody planned")
        assertEquals(2.0, set.num("sensorsArmed"))
        assertEquals(listOf("a", "b"), set.getValue("sensorRolesExpected").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("a", set.text("analysedRole"))
        assertNull(set["sensorsShortfall"], "nothing was in the way of a set that armed both")
    }

    /**
     * DIFFERENTIAL, issue #207. The manifest says when the analysed role is
     * not the role the set armed.
     *
     * Both documents or neither: the raw archive and `session.json` are read
     * by different consumers, and the archive is the one a reader opens to
     * find the CSV the figures came from. A reader holding only `meta.json`
     * would otherwise see an `analysedRole` naming the only stream in the
     * directory and have no way to tell that it was the second choice.
     *
     * Written only when it is true, which is the same rule `session.json`
     * follows -- its exporter drops a false default -- so the two documents
     * express the ordinary set identically, by omission.
     */
    @Test
    fun `the manifest says when the analysed role is not the one the set armed`() = runTest {
        val fellBack = descriptor(
            dual.copy(analysed = SensorRole.B, analysedFellBack = true),
            listOf(imuStream(2L, "b", secondarySamples, storedRate = 98.5)),
        )
        assertEquals("b", fellBack.text("analysedRole"))
        assertEquals(
            true,
            fellBack["analysedFellBack"]?.jsonPrimitive?.content?.toBoolean(),
            "the manifest never says the analysed role moved",
        )

        assertNull(
            descriptor(dual, dualStreams())["analysedFellBack"],
            "an ordinary dual set's manifest carries the key anyway",
        )
    }

    /**
     * DIFFERENTIAL, issue #198. A set that recorded one stream because two
     * paired units could not be told apart says so in the manifest too.
     *
     * The raw archive and `session.json` are read by different consumers and
     * one saying less than the other about the same set is how a reader comes
     * to believe the archive is complete. The word is the export's, not a
     * second spelling of it.
     */
    @Test
    fun `the manifest names the gap when two units could not be told apart`() = runTest {
        val set =
            descriptor(
                RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_COLLIDE),
                listOf(imuStream(1L, null, analysedSamples, storedRate = 98.5)),
            )

        assertEquals("rolesCollide", set.text("sensorsShortfall"))
        assertEquals(1.0, set.num("sensorsArmed"))
    }

    /**
     * A role whose unit captured nothing is in `sensorRolesExpected` and has no
     * entry in `sensors`.
     *
     * The roles MISSING are the set difference between those two, deliberately
     * not a third key: a duplicate statement is one that can disagree with its
     * own inputs.
     */
    @Test
    fun `a role that captured nothing is expected and has no stream entry`() = runTest {
        val set = descriptor(dual, listOf(imuStream(1L, "a", analysedSamples, storedRate = 98.5)))

        assertEquals(listOf("a", "b"), set.getValue("sensorRolesExpected").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("a"),
            set.getValue("sensors").jsonArray.map { it.jsonObject.text("role") },
            "a role with no capture was given a stream entry",
        )
    }

    /**
     * The set-level `sampleRate_hz` and `rollExcursion_deg` keep describing the
     * ANALYSED stream.
     *
     * They are what every earlier reader of this manifest has been reading, and
     * every figure in `session.json` for this set comes from that same stream.
     * On a dual set they duplicate the analysed entry of `sensors`, and that
     * redundancy is accepted deliberately -- changing them is what would break
     * a single-sensor archive. The fixture's secondary stream has a
     * deliberately different rate and roll sweep, so picking the wrong one is
     * visible.
     */
    @Test
    fun `the set's own rate and roll excursion still describe the analysed stream`() = runTest {
        val set = descriptor(dual, dualStreams())

        val analysedRate =
            VelocityEstimator.measuredSampleRateOrNull(
                analysedSamples.size,
                (analysedSamples.last().timestampMs - analysedSamples.first().timestampMs) / 1000.0,
            )
        assertEquals(analysedRate, set.num("sampleRate_hz"))
        val analysedRoll = analysedSamples.maxOf { it.rollDeg } - analysedSamples.minOf { it.rollDeg }
        assertEquals(Math.round(analysedRoll * 10.0) / 10.0, set.num("rollExcursion_deg"))
    }

    /**
     * With the analysed unit absent, the set's own rate is withheld rather than
     * taken from the survivor.
     *
     * The wrong pair in its sharpest form: the surviving stream has a rate, and
     * publishing it under a key that every reader takes to describe the
     * analysed capture would attribute one sensor's cadence to another.
     * Omission is the manifest's own idiom for an unknown.
     */
    @Test
    fun `an absent analysed stream withholds the set's rate rather than borrowing one`() = runTest {
        val set = descriptor(dual, listOf(imuStream(2L, "b", secondarySamples, storedRate = null)))

        assertNull(set["sampleRate_hz"], "the surviving stream's rate was published as the set's")
        assertEquals(
            listOf("b"),
            set.getValue("sensors").jsonArray.map { it.jsonObject.text("role") },
            "the surviving capture is still described, under its own role",
        )
    }

    /**
     * A one-sensor set's descriptor grows none of these keys.
     *
     * `RawExporterTest`'s exact key set is the primary pin; this is the same
     * question asked of the fixture this file uses, so the control travels with
     * the differentials rather than in another file.
     */
    @Test
    fun `a one-sensor set's descriptor carries no sensor keys at all`() = runTest {
        val set = descriptor(null, listOf(imuStream(1L, null, analysedSamples, storedRate = 98.5)))

        for (key in listOf("sensors", "sensorsPlanned", "sensorsArmed", "sensorRolesExpected", "analysedRole")) {
            assertNull(set[key], "a one-sensor descriptor grew $key")
        }
    }

    /**
     * The archive's analysis document agrees with its manifest about which
     * roles arrived.
     *
     * Two documents in one zip stating the same fact is a place they can
     * disagree, and the pair is what a consumer joins on: `meta.json` maps a
     * role to a FILE, `session.json` says which roles the set was armed for.
     */
    @Test
    fun `the manifest and the analysis document agree about the roles present`() = runTest {
        val entries = entriesOf(dual, dualStreams())

        val manifestRoles =
            Json.parseToJsonElement(entries.getValue("meta.json"))
                .jsonObject.getValue("sets").jsonArray.single()
                .jsonObject.getValue("sensors").jsonArray.map { it.jsonObject.text("role") }
        val analysis =
            Json.parseToJsonElement(entries.getValue("session.json"))
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray.single().jsonObject
        val declared = assertNotNull(analysis["sensors"]).jsonObject
        assertEquals(
            manifestRoles,
            declared.getValue("present").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * DIFFERENTIAL, issue #213. The archive's own manifest names the silent
     * unit and what the app could see of its link.
     *
     * It moves with `session.json` rather than after it, for the reason 1.17's
     * first change already gave for `analysedFellBack`: the two documents ride
     * in one zip, and a reader who opens the archive and reads the manifest
     * must not get a different account of one set from the reader who opens
     * `session.json`. Written only when something was silent, so an ordinary
     * dual set's descriptor is byte-for-byte what it was.
     */
    @Test
    fun `the manifest names an armed unit that delivered nothing`() = runTest {
        val stored =
            """{"count":2,"expected":["A","B"],"analysed":"A","silent":{"B":"NOT_LINKED"}}"""
        val descriptor =
            descriptorFromStoredJson(stored, listOf(imuStream(1L, "a", analysedSamples, storedRate = 98.5)))

        val silent =
            assertNotNull(descriptor["sensorsSilent"], "the manifest says nothing about the unit that went silent")
        assertEquals(
            mapOf("b" to "notLinked"),
            silent.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
        )
    }

    /**
     * DIFFERENTIAL, issue #213. A dual set whose units both delivered carries
     * no such key.
     */
    @Test
    fun `a manifest for a set with nothing silent carries no silence key`() = runTest {
        assertNull(
            descriptor(dual, dualStreams())["sensorsSilent"],
            "a healthy dual set's descriptor grew a statement about nothing",
        )
    }
}
