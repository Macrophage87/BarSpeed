package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SyntheticSets
import com.macrophage.barspeed.dsp.VelocityEstimator
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.GeometrySources
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.StartPhase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The raw-data zip and its `meta.json` manifest.
 *
 * The zip is the artifact everything else is recoverable from: the DSP's output
 * can always be recomputed from the gzipped CSVs, so what matters here is that
 * the manifest describes those CSVs truthfully. `meta.json` has no published
 * schema -- `docs/schemas/` holds only the plan and session-export documents --
 * so nothing but these tests states what it contains, and a reader has only
 * `ImuCsv`'s KDoc to interpret it by, which is not shipped in the zip.
 *
 * The manifest is built by string concatenation rather than serialized, so
 * "is it still valid JSON" is a real question and is asserted rather than
 * assumed: every assertion here goes through a parser.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces and
 * the fake stands in for them, so what is verified is the exporter's own
 * output and nothing about what the database did with it.
 */
class RawExporterTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

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

    private val json = Json { ignoreUnknownKeys = true }

    /** A still, level sensor at 100 Hz for [seconds] -- what a plank actually streams. */
    private fun stillSamples(seconds: Double): List<ImuSample> = SyntheticSets.generate(
        reps = emptyList(),
        sampleRateHz = 100.0,
        leadInS = seconds / 2.0,
        leadOutS = seconds / 2.0,
    )

    /**
     * What the app stores for a timed set: no reps, no velocity loss, no
     * compliance, and a sample rate of zero because the timed branch never
     * runs the estimator.
     */
    private fun timedAnalysis() = SetAnalysis(emptyList(), 0.0, null, null, listOf("Held 45 s."))

    private fun analysedAt(rateHz: Double) = SetAnalysis(emptyList(), rateHz, null, null, emptyList())

    /**
     * A plan-declared seated leg curl: the drive goes DOWN, off a cable stack
     * through a 2:1 pulley. Every value differs from every other, so a
     * descriptor that crossed two of them cannot pass.
     */
    private val legCurl =
        ResolvedGeometry(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            horizontal = false,
            sensorOnStack = true,
            sensorInverted = true,
            travelRatio = 2.0,
            kind = ExerciseKind.DYNAMIC,
            bodyweight = false,
            sources =
            GeometrySources(
                startsWith = GeometrySource.DECLARED,
                concentric = GeometrySource.DECLARED,
                plane = GeometrySource.DEFAULT,
                kind = GeometrySource.INFERRED,
                travelRatio = GeometrySource.DECLARED,
            ),
        )

    private fun row(
        id: Long,
        exerciseId: String = "plank",
        actualReps: Int = 0,
        durationS: Int? = 45,
        analysis: SetAnalysis = timedAnalysis(),
        orderIdx: Int = 0,
        geometry: ResolvedGeometry? = null,
    ) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = orderIdx,
        exerciseId = exerciseId,
        exerciseName = exerciseId,
        loadKg = 20.0,
        actualReps = actualReps,
        actualDurationS = durationS,
        startedAtMs = 1_000L,
        endedAtMs = 46_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
        geometryJson = geometry?.let { json.encodeToString(ResolvedGeometry.serializer(), it) },
    )

    private fun imuStream(setId: Long, samples: List<ImuSample>, storedRate: Double?) = RawStreamEntity(
        id = 1L,
        setId = setId,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples)),
        sampleRateHz = storedRate,
    )

    private fun hrStream(setId: Long) = RawStreamEntity(
        id = 2L,
        setId = setId,
        kind = RawStreamEntity.KIND_HRM,
        csvGzip = Gzip.compress(HrCsv.encode(listOf(HrSample(1_000L, 120, listOf(500.0))))),
    )

    private suspend fun zipOf(
        rows: List<SetRecordEntity>,
        streams: Map<Long, List<RawStreamEntity>>,
    ): Map<String, String> {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 46_000L),
                rows = rows,
                streams = streams,
            )
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.37").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return entries
    }

    private suspend fun meta(rows: List<SetRecordEntity>, streams: Map<Long, List<RawStreamEntity>>): JsonObject =
        Json.parseToJsonElement(zipOf(rows, streams).getValue("meta.json")).jsonObject

    private fun JsonObject.set(index: Int): JsonObject = getValue("sets").jsonArray[index].jsonObject

    private fun JsonObject.num(key: String): Double? = get(key)?.jsonPrimitive?.content?.toDouble()

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    // ---- the manifest as a document ----------------------------------------

    /**
     * `meta.json` is assembled with a StringBuilder, so a field added without a
     * comma, or a set list joined wrongly, produces a file no reader can open
     * at all. Nothing else checks this.
     */
    @Test
    fun `the manifest is valid JSON and names the files it describes`() = runTest {
        val samples = stillSamples(45.0)
        val entries =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, samples, storedRate = 100.0), hrStream(5L))),
            )
        assertEquals(
            setOf("set01_plank_imu.csv", "set01_plank_hrm.csv", "meta.json", "session.json"),
            entries.keys,
        )
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertEquals("1970-01-01T00:00:01Z", manifest.text("epoch"))
        assertEquals("0.1.37", manifest.text("appVersion"))
        assertEquals("WitMotion WT901BLECL", manifest.text("sensorModel"))
        assertEquals("session.json", manifest.text("analysisFile"))
        assertEquals(ImuCsv.HEADER, manifest.text("csvHeaderImu"))
        assertEquals(HrCsv.HEADER, manifest.text("csvHeaderHrm"))
        assertEquals(CueCsv.HEADER, manifest.text("csvHeaderCues"))
        assertEquals(
            listOf("set01_plank_imu.csv", "set01_plank_hrm.csv"),
            manifest.set(0).getValue("files").jsonArray.map { it.jsonPrimitive.content },
        )
        // The zip has to stand alone: the CSV it points at is the real stream.
        //
        // Sample count and timestamps, not whole samples. The canonical format
        // writes accelerations at six decimal places and angles at four, so a
        // decoded sample is equal to the original only to that precision --
        // asserting whole-object equality reds on the fixture's own noise.
        // What has to survive exactly is the pair the sample clock is rebuilt
        // from: how many rows there are, and the span between the first and the
        // last arrival stamp.
        val csv = entries.getValue("set01_plank_imu.csv")
        assertEquals(ImuCsv.HEADER, csv.lineSequence().first())
        val decoded = ImuCsv.decode(csv)
        assertEquals(samples.size, decoded.size)
        assertEquals(samples.first().timestampMs, decoded.first().timestampMs)
        assertEquals(samples.last().timestampMs, decoded.last().timestampMs)
    }

    @Test
    fun `sets are numbered from one in the order they were performed`() = runTest {
        val manifest =
            meta(
                listOf(
                    row(id = 5L, exerciseId = "plank", orderIdx = 0),
                    row(id = 6L, exerciseId = "carry", orderIdx = 1),
                ),
                mapOf(
                    5L to listOf(imuStream(5L, stillSamples(10.0), 100.0)),
                    6L to listOf(imuStream(6L, stillSamples(10.0), 100.0)),
                ),
            )
        assertEquals(2, manifest.getValue("sets").jsonArray.size)
        assertEquals(1.0, manifest.set(0).num("set"))
        assertEquals("plank", manifest.set(0).text("exercise"))
        assertEquals(2.0, manifest.set(1).num("set"))
        assertEquals("carry", manifest.set(1).text("exercise"))
    }

    // ---- sampleRate_hz, as it stands ---------------------------------------

    /**
     * A set the DSP actually analysed publishes the rate that analysis ran at.
     *
     * This is the case the fix must leave alone, and the reason it can: the
     * stored figure and a figure derived from the stream's own timestamps are
     * the same double, because both are intervals-over-span across the same
     * samples. Pinned in [com.macrophage.barspeed.dsp.SampleRateTest] on the
     * DSP side; pinned here as what reaches the manifest.
     *
     * The stored rate is therefore computed from the fixture rather than
     * chosen. Writing a round 100.0 beside a stream whose actual span gives
     * 100.00000000000001 describes a set this app cannot produce -- the stored
     * figure is never an independent number, it is this same arithmetic over
     * this same stream -- and a pin built on an impossible pairing tests
     * nothing about the real one. Asserting the exact double, not a band, so
     * the two sources have to agree to the last bit.
     */
    @Test
    fun `an analysed set publishes the rate it was analysed at`() = runTest {
        val samples = stillSamples(20.0)
        val analysedRate =
            VelocityEstimator.measureSampleRate(
                samples.size,
                (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
            )
        assertTrue(analysedRate in 99.0..101.0, "the fixture must stream ~100 Hz, got $analysedRate")
        val manifest =
            meta(
                listOf(
                    row(
                        id = 5L,
                        exerciseId = "back_squat",
                        durationS = null,
                        analysis = analysedAt(analysedRate),
                    ),
                ),
                mapOf(5L to listOf(imuStream(5L, samples, storedRate = analysedRate))),
            )
        assertEquals(analysedRate, manifest.set(0).num("sampleRate_hz"))
    }

    /**
     * A set with no IMU stream states no rate at all, and that is already
     * right: a sensorless manual set writes no stream row, so the key is
     * simply absent rather than present and meaningless.
     */
    @Test
    fun `a set with no imu stream omits the rate entirely`() = runTest {
        val manifest = meta(listOf(row(id = 5L)), mapOf(5L to listOf(hrStream(5L))))
        assertNull(manifest.set(0)["sampleRate_hz"])
        assertEquals(
            listOf("set01_plank_hrm.csv"),
            manifest.set(0).getValue("files").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * Absence is expressed by omission throughout this manifest -- `num` and
     * `str` skip nulls, `flag` skips false -- so there is no null literal
     * anywhere in the document and a reader may treat a missing key as "not
     * stated" rather than as any particular value.
     */
    @Test
    fun `the manifest never writes a null literal`() = runTest {
        val text =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, stillSamples(45.0), storedRate = 100.0))),
            ).getValue("meta.json")
        assertTrue("null" !in text, "expected absence to be expressed by omission, got:\n$text")
        assertNull(manifestSet(text)["rpe"])
        assertNull(manifestSet(text)["side"])
        assertNull(manifestSet(text)["plannedReps"])
        assertNull(manifestSet(text)["failed"])
        assertNull(manifestSet(text)["warmup"])
    }

    private fun manifestSet(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject.getValue("sets").jsonArray[0].jsonObject

    // ---- rollExcursion_deg, as it stands -----------------------------------

    /**
     * Attitude excursion decides which analysis is even valid on a set, so it
     * is measured from the stream rather than stored. A rotating stream reports
     * the full range it swept.
     */
    @Test
    fun `roll excursion is the range the stream actually swept`() = runTest {
        val rotating =
            listOf(-30.0, 0.0, 45.0, 120.0, 10.0).map { roll ->
                ImuSample(0L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, roll, 0.0, 0.0)
            }
        val manifest =
            meta(listOf(row(id = 5L)), mapOf(5L to listOf(imuStream(5L, rotating, storedRate = 100.0))))
        assertEquals(150.0, manifest.set(0).num("rollExcursion_deg"))
    }

    @Test
    fun `a set with no imu stream states no roll excursion`() = runTest {
        val manifest = meta(listOf(row(id = 5L)), mapOf(5L to listOf(hrStream(5L))))
        assertNull(manifest.set(0)["rollExcursion_deg"])
    }

    /**
     * A stream whose CSV will not parse must not take the whole export down
     * with it.
     *
     * Gzip inflating is not the only way this fails: `ImuCsv.decode` parses
     * every row and throws on a malformed one, so a single corrupt line in one
     * set's capture would otherwise cost the lifter the export of the entire
     * session -- including the sets that are perfectly intact. The bytes still
     * reach the zip, because the file is written from the inflated text without
     * being parsed, so the capture stays recoverable by hand.
     */
    @Test
    fun `a stream that will not parse still exports, without the figures read from it`() = runTest {
        val corrupt =
            RawStreamEntity(
                id = 3L,
                setId = 5L,
                kind = RawStreamEntity.KIND_IMU,
                csvGzip = Gzip.compress(ImuCsv.HEADER + "\n1000,0.1,not-a-number\n"),
                sampleRateHz = 98.5,
            )
        val entries = zipOf(listOf(row(id = 5L)), mapOf(5L to listOf(corrupt)))
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertTrue("set01_plank_imu.csv" in entries.keys, "the raw bytes must still be exported")
        assertNull(manifest.set(0)["rollExcursion_deg"], "nothing was read, so nothing is claimed")
    }

    // ---- issue 31: a rate the stream can state, or no rate at all ----------

    /**
     * A plank recorded with the sensor on has a real IMU stream and no
     * analysis, and the manifest must describe the stream it shipped.
     *
     * The capture is unconditional: the collector runs for every set and the
     * buffer fills at 100 Hz whether or not the set is one the segmenter will
     * look at. The analysis is not -- a timed set takes the first branch and
     * stores a placeholder whose sample rate is zero -- and that placeholder is
     * what the raw stream row copies and what the manifest publishes. So a
     * 45-second hold ships 4,500 real samples beside "sampleRate_hz": 0.0.
     *
     * Zero is the one value that cannot be true here. It is also precisely the
     * number ImuCsv's header tells a reader to divide sample_idx by, so a coach
     * either divides by zero or throws away a good stream. The rate is sitting
     * in the file the key describes: timestamp_ms is written on every row.
     */
    @Test
    fun `a timed set publishes the rate its own stream shows`() = runTest {
        val samples = stillSamples(45.0)
        val manifest =
            meta(
                listOf(row(id = 5L, durationS = 45, analysis = timedAnalysis())),
                mapOf(5L to listOf(imuStream(5L, samples, storedRate = 0.0))),
            )
        assertEquals(4_500, samples.size)
        val rate = manifest.set(0).num("sampleRate_hz")
        assertNotNull(rate, "a set that shipped 4,500 samples must state their rate")
        assertTrue(rate in 99.0..101.0, "expected ~100 Hz from the stream itself, got $rate")
    }

    /**
     * The other half of the same fix, and the reason it is not a `> 0` guard on
     * the stored value: what cannot be measured must be omitted, not replaced
     * with a plausible number.
     *
     * One sample has no span, so there is no rate to state. The manifest has to
     * say nothing -- absence is how every other unknown in this document is
     * expressed -- rather than publish either the stored 0.0 or the 100 Hz that
     * the integrator's own estimator would have invented.
     */
    @Test
    fun `a stream too short to state a rate publishes none`() = runTest {
        val manifest =
            meta(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, stillSamples(45.0).take(1), storedRate = 0.0))),
            )
        assertNull(manifest.set(0)["sampleRate_hz"])
    }

    /**
     * The same, for a stream whose samples all arrived under one timestamp.
     * Span zero, nothing to measure, nothing to say.
     */
    @Test
    fun `a stream with no span publishes no rate`() = runTest {
        val oneInstant =
            List(200) { ImuSample(7_000L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        val manifest =
            meta(listOf(row(id = 5L)), mapOf(5L to listOf(imuStream(5L, oneInstant, storedRate = 0.0))))
        assertNull(manifest.set(0)["sampleRate_hz"])
    }

    /**
     * The structural version, over every set in a session: whatever else the
     * manifest says, it never publishes a rate of zero.
     *
     * Written across a mixed session -- a timed hold with a stream, a
     * one-sample stream, and a sensorless set with no stream at all -- because
     * the defect only appears on the sets the segmenter never looked at, and a
     * single-set assertion is how it survived this long.
     */
    @Test
    fun `no set in the manifest publishes a rate of zero`() = runTest {
        val manifest =
            meta(
                listOf(
                    row(id = 5L, exerciseId = "plank", orderIdx = 0),
                    row(id = 6L, exerciseId = "carry", orderIdx = 1),
                    row(id = 7L, exerciseId = "situp", orderIdx = 2, durationS = null),
                ),
                mapOf(
                    5L to listOf(imuStream(5L, stillSamples(45.0), storedRate = 0.0)),
                    6L to listOf(imuStream(6L, stillSamples(45.0).take(1), storedRate = 0.0)),
                    7L to listOf(hrStream(7L)),
                ),
            )
        for (i in 0..2) {
            val rate = manifest.set(i).num("sampleRate_hz")
            assertTrue(rate == null || rate > 0.0, "set ${i + 1} published sampleRate_hz=$rate")
        }
    }

    /**
     * The near neighbour, folded in on the coordinator's ruling and named as
     * beyond issue 31's scope.
     *
     * `rollExcursion_deg` is read off the same stream by the same helper and
     * carries the same fault in a smaller costume: one sample has a maximum
     * roll equal to its minimum, so the range comes out 0.0 and the manifest
     * states that the set did not rotate. It did not measure whether the set
     * rotated. Attitude excursion is what a reader uses to decide whether the
     * integration on this set is trustworthy at all, so a fabricated 0.0 reads
     * as the most reassuring answer available.
     */
    @Test
    fun `a single-sample stream states no roll excursion`() = runTest {
        val manifest =
            meta(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, stillSamples(45.0).take(1), storedRate = 0.0))),
            )
        assertNull(manifest.set(0)["rollExcursion_deg"])
    }

    // ---- issue 73: the zip has to stand on its own -------------------------

    /**
     * The manifest states which way the lift moved and how the sensor was
     * mounted.
     *
     * This file's whole purpose is that the zip stands alone: an analysis that
     * opens only the CSVs must still be able to tell left from right. Such a
     * reader has no phase labels at all -- it has raw device-frame samples and
     * this descriptor -- so direction matters more here than anywhere else in
     * the export, not less. On a leg curl or a pushdown the down stroke is the
     * concentric, and nothing in the accelerometer trace says so.
     */
    @Test
    fun `the manifest states the direction and mounting each set was measured with`() = runTest {
        val manifest = meta(listOf(row(id = 5L, geometry = legCurl)), emptyMap())
        val set = manifest.set(0)
        assertEquals("concentric", set.text("startsWith"))
        assertEquals("down", set.text("concentric"))
        assertEquals("vertical", set.text("plane"))
        assertEquals(2.0, set.num("travelRatio"))
        assertEquals("dynamic", set.text("kind"))
    }

    /**
     * A stated false is written; an unstated geometry writes nothing.
     *
     * `flag()` omits a false, which is right for `warmup` and `failed` where
     * absence reads correctly as "not flagged", and wrong here: a reader that
     * cannot see `sensorOnStack` has to decide whether the sensor was on a
     * stack, and "the app did not say" and "the app said no" are different
     * answers. Written as one test over both states so it cannot pass by the
     * keys being absent in both.
     */
    @Test
    fun `a stated geometry false is written, while an unstated geometry writes nothing`() = runTest {
        val stated = meta(listOf(row(id = 5L, geometry = legCurl.copy(sensorInverted = false))), emptyMap()).set(0)
        assertEquals(false, stated["sensorInverted"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, stated["sensorOnStack"]?.jsonPrimitive?.content?.toBoolean())

        val unstated = meta(listOf(row(id = 5L, geometry = null)), emptyMap()).set(0)
        for (key in listOf("startsWith", "concentric", "plane", "sensorOnStack", "sensorInverted", "travelRatio")) {
            assertNull(unstated[key], "an unstated geometry wrote $key")
        }
    }
}
