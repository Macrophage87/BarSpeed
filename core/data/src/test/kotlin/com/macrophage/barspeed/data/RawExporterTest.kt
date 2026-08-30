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
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
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

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    /**
     * Records whether anything ever asked it to dispatch, then hands the work
     * to a real dispatcher so the coroutine actually completes. The seam issue
     * #29 needed: proof that [RawExporter.buildZip] and [SessionExporter]'s
     * functions honour an injected dispatcher, without Robolectric or an
     * Android `Looper` -- plain `kotlinx-coroutines-core`, the same machinery
     * [runTest] already depends on.
     */
    private class RecordingDispatcher(private val real: CoroutineDispatcher = Dispatchers.Default) :
        CoroutineDispatcher() {
        var dispatched = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            real.dispatch(context, block)
        }
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

    private fun repStream(setId: Long, marks: List<Long>) = RawStreamEntity(
        id = 6L,
        setId = setId,
        kind = RawStreamEntity.KIND_REPS,
        csvGzip = Gzip.compress(RepMarkCsv.encode(marks)),
    )

    private fun restStream(setId: Long, samples: List<HrSample>) = RawStreamEntity(
        id = 4L,
        setId = setId,
        kind = RawStreamEntity.KIND_REST_BEFORE_HRM,
        csvGzip = Gzip.compress(HrCsv.encode(samples)),
    )

    /**
     * [zipCompressionLevel] defaults to null, not to BEST_SPEED, and that
     * distinction is the point: null lets [RawExporter]'s own constructor
     * default decide, so `zipBytes(rows, streams)` with no level argument
     * genuinely exercises production behaviour rather than a copy of
     * BEST_SPEED living a second time in this file, which would keep this
     * helper's "default" case passing even if RawExporter's real default
     * were ever changed to something else.
     */
    private suspend fun zipBytes(
        rows: List<SetRecordEntity>,
        streams: Map<Long, List<RawStreamEntity>>,
        zoneId: String? = null,
        utcOffsetMinutes: Int? = null,
        zipCompressionLevel: Int? = null,
    ): ByteArray {
        val dao =
            FakeSessionDao(
                session =
                SessionEntity(
                    id = 1L,
                    startedAtMs = 1_000L,
                    endedAtMs = 46_000L,
                    zoneId = zoneId,
                    utcOffsetMinutes = utcOffsetMinutes,
                ),
                rows = rows,
                streams = streams,
            )
        val repo = SessionRepository(dao, FakeExerciseDao())
        val exporter =
            if (zipCompressionLevel == null) {
                RawExporter(repo, SessionExporter(repo), appVersion = "0.1.37")
            } else {
                RawExporter(
                    repo,
                    SessionExporter(repo),
                    appVersion = "0.1.37",
                    zipCompressionLevel = zipCompressionLevel,
                )
            }
        return exporter.buildZip(1L)!!
    }

    private suspend fun zipOf(
        rows: List<SetRecordEntity>,
        streams: Map<Long, List<RawStreamEntity>>,
        zoneId: String? = null,
        utcOffsetMinutes: Int? = null,
    ): Map<String, String> {
        val bytes = zipBytes(rows, streams, zoneId, utcOffsetMinutes)
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

    /**
     * What the archive looks like once a rest window is stored, which is the
     * whole visible result of issue #90 part 1.
     *
     * The exporter needs no change to produce this: it names every stream
     * `set%02d_<exercise>_<kind>.csv` and loops over all of them, so the kind
     * string alone decides the filename. That is why the kind carries the
     * DIRECTION -- a coach opening this zip sees
     * `set01_plank_rest_before_hrm.csv` beside `set01_plank_hrm.csv` and can
     * tell which side of the set each covers without decoding either.
     *
     * The manifest's top-level keys do not move; the file appears inside the
     * set's own `files` array. `meta.json` has no published schema, so this
     * test and its neighbours are the only statement of that shape.
     */
    @Test
    fun `a stored rest window appears in the zip and in the manifest`() = runTest {
        val rest = listOf(HrSample(200L, 64, listOf(937.5)), HrSample(700L, 63, listOf(952.4)))
        val entries =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(
                    5L to listOf(
                        imuStream(5L, stillSamples(45.0), storedRate = 100.0),
                        hrStream(5L),
                        restStream(5L, rest),
                    ),
                ),
            )
        assertEquals(
            setOf(
                "set01_plank_imu.csv",
                "set01_plank_hrm.csv",
                "set01_plank_rest_before_hrm.csv",
                "meta.json",
                "session.json",
            ),
            entries.keys,
        )
        assertEquals(
            rest,
            HrCsv.decode(entries.getValue("set01_plank_rest_before_hrm.csv")),
            "the rest capture did not survive the archive intact",
        )
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertEquals(
            listOf("set01_plank_imu.csv", "set01_plank_hrm.csv", "set01_plank_rest_before_hrm.csv"),
            manifest.set(0).getValue("files").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(HrCsv.HEADER, manifest.text("csvHeaderHrm"), "the rest CSV shares the HRM header")
    }

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

    /**
     * The manifest's top-level keys, exactly.
     *
     * The test above asserts every value but would go on passing after a key
     * was added, and this file is the only definition `meta.json` has --
     * `docs/schemas/` holds the plan and the session export and nothing for
     * this document. So an exact key set is the whole of its published shape,
     * and adding to it is a deliberate act rather than a side effect.
     */
    @Test
    fun `the manifest states exactly the top-level keys it states today`() = runTest {
        val manifest = meta(listOf(row(id = 5L)), emptyMap())
        assertEquals(
            setOf(
                "epoch",
                "appVersion",
                "sensorModel",
                "analysisFile",
                "csvHeaderImu",
                "csvHeaderHrm",
                "csvHeaderCues",
                "csvHeaderReps",
                "sets",
            ),
            manifest.keys,
        )
    }

    /**
     * A one-sensor set's descriptor states exactly these keys and no others.
     *
     * Characterization written before a second accelerometer exists (#156).
     * The neighbouring test pins the manifest's TOP-LEVEL keys and would go on
     * passing while every set inside it grew a key; this is the same
     * instrument one level down, and it is the whole of what "a single-sensor
     * archive did not move" can be asserted as. `meta.json` has no published
     * schema, so an exact key set is its only definition.
     *
     * The fixture carries an IMU and an HRM stream, which is what an ordinary
     * recorded set has, and no geometry -- geometry's own presence and absence
     * are pinned by their own tests below and would only blur what this one is
     * about.
     */
    @Test
    fun `a one-sensor set's descriptor states exactly the keys it states today`() = runTest {
        val manifest =
            meta(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, stillSamples(45.0), storedRate = 100.0), hrStream(5L))),
            )
        assertEquals(
            setOf(
                "set",
                "exercise",
                "load_kg",
                "load_lb",
                "reps",
                "duration_s",
                "startedAt_ms",
                "endedAt_ms",
                "sampleRate_hz",
                "rollExcursion_deg",
                "files",
            ),
            manifest.set(0).keys,
            "the per-set descriptor's key set moved for a one-sensor set",
        )
    }

    /**
     * The rep-mark capture reaches the archive, and the manifest says how to
     * read it (#158).
     *
     * The zip half needs no exporter change -- every stream is named
     * `set%02d_<exercise>_<kind>.csv` and the loop writes all of them -- but
     * the manifest publishes a header per format, and a file whose column
     * layout is stated nowhere is a file a reader has to guess at. The three
     * existing `csvHeader*` keys are the whole of that statement; a fourth
     * format arriving without one is the near neighbour this test exists to
     * catch.
     *
     * The instants are asserted through the archive's own bytes, so what is
     * checked is what a coach opening the zip gets rather than what the
     * repository held.
     */
    @Test
    fun `a stored rep-mark capture appears in the zip and is described by the manifest`() = runTest {
        val marks = listOf(1_100L, 4_350L, 8_020L)
        val entries =
            zipOf(listOf(row(id = 5L)), mapOf(5L to listOf(repStream(5L, marks))))
        assertEquals(
            setOf("set01_plank_reps.csv", "meta.json", "session.json"),
            entries.keys,
        )
        assertEquals(marks, RepMarkCsv.decode(entries.getValue("set01_plank_reps.csv")))
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertEquals(RepMarkCsv.HEADER, manifest.text("csvHeaderReps"))
        assertEquals(
            listOf("set01_plank_reps.csv"),
            manifest.set(0).getValue("files").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * The archive's `session.json` carries the marks too, so the zip answers
     * the question both ways.
     *
     * `session.json` is built with `includeRepDetail = true`, so the detailed
     * gate on the key must not withhold it here. This is the artifact the
     * field capture is actually read from -- the CSV is the raw fact and the
     * analysis document is what a differential runs against -- and the two
     * disagreeing would be worse than either alone.
     */
    @Test
    fun `the archive's analysis document carries the marks as well as the CSV`() = runTest {
        val marks = listOf(1_100L, 4_350L, 8_020L)
        val entries = zipOf(listOf(row(id = 5L)), mapOf(5L to listOf(repStream(5L, marks))))
        val set =
            Json.parseToJsonElement(entries.getValue("session.json"))
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertEquals(marks, set.getValue("repMarks").jsonArray.map { it.jsonPrimitive.content.toLong() })
    }

    /**
     * The manifest names the prep beside the set it describes.
     *
     * The archive has to stand on its own -- an analysis that opens only the
     * CSVs never sees session.json -- and the cue track can no longer answer
     * this: `LeadInPlan` fixes the launch phrase to the END of the prep, so
     * `Ready` sits a prescribed `PHRASE_S` seconds before the first movement
     * cue whether the prep was 2 seconds or 20. Without this key the seconds
     * the lifter spent getting set are unrecoverable from the zip.
     *
     * Both halves, and unequal, so a descriptor that wrote one of them twice
     * cannot pass.
     */
    @Test
    fun `the manifest names the prep prescribed and the prep played`() = runTest {
        val manifest = meta(listOf(row(id = 5L).copy(plannedPrepS = 5, prepS = 20)), emptyMap())
        assertEquals(5.0, manifest.set(0).num("plannedPrep_s"), "the manifest lost the prescribed prep")
        assertEquals(20.0, manifest.set(0).num("prep_s"), "the manifest lost the prep that was played")
    }

    /**
     * A set that played no lead-in gets neither key. The manifest expresses
     * every other unknown by omission and `num` already drops a null; what is
     * pinned is that nothing substitutes a 0, which here would read as a real,
     * silent prep rather than as no prep at all.
     */
    @Test
    fun `a set that played no lead-in gets no prep keys in the manifest`() = runTest {
        val set = meta(listOf(row(id = 5L)), emptyMap()).set(0)
        assertTrue("plannedPrep_s" !in set, "a set with no lead-in published a planned prep")
        assertTrue("prep_s" !in set, "a set with no lead-in published a prep")
    }

    /**
     * The manifest states the offset and zone beside the instant it qualifies.
     *
     * The raw zip has to stand on its own -- an analysis that opens only the
     * CSVs has `epoch` and a pile of epoch-millisecond timestamps, and nothing
     * that turns either into a time of day. The keys are flat here and nested
     * in `session.json`, matching what the two documents already do with
     * geometry, and named `timeZoneId` rather than `timeZone` so that one key
     * name never means a string in one artifact and an object in the other.
     */
    @Test
    fun `the manifest states the zone and offset the session was recorded on`() = runTest {
        val manifest =
            Json.parseToJsonElement(
                zipOf(listOf(row(id = 5L)), emptyMap(), "America/New_York", -240).getValue("meta.json"),
            ).jsonObject
        assertEquals("1970-01-01T00:00:01Z", manifest.text("epoch"))
        assertEquals("America/New_York", manifest.text("timeZoneId"))
        assertEquals(-240.0, manifest.num("utcOffsetMinutes"))
    }

    /**
     * Both artifacts in one zip say the same thing about the same session.
     *
     * They are built by different code -- `session.json` is serialized from a
     * data class, `meta.json` is concatenated by hand -- off the same two
     * columns, so a reader holding both must not be able to find them
     * disagreeing. The same instrument as `the geometry is the same in both
     * exports of one set`.
     */
    @Test
    fun `both artifacts report the same zone and offset for one session`() = runTest {
        val entries = zipOf(listOf(row(id = 5L)), emptyMap(), "Pacific/Chatham", 765)
        val manifest = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        val session = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        val zone = session.getValue("timeZone").jsonObject
        assertEquals(manifest.text("timeZoneId"), zone.getValue("id").jsonPrimitive.content)
        assertEquals(
            manifest.num("utcOffsetMinutes")?.toInt(),
            zone.getValue("utcOffsetMinutes").jsonPrimitive.content.toInt(),
        )
    }

    /**
     * A session with no stored zone writes neither key, and no null literal.
     *
     * `meta.json` is hand-concatenated, so an absent value written as `null`
     * is a real possibility rather than a hypothetical -- and the file already
     * carries a test asserting the string never appears. This is the state
     * every session recorded before this change is in, permanently.
     */
    @Test
    fun `a session with no stored zone states neither key`() = runTest {
        val text = zipOf(listOf(row(id = 5L)), emptyMap()).getValue("meta.json")
        val manifest = Json.parseToJsonElement(text).jsonObject
        assertNull(manifest["timeZoneId"])
        assertNull(manifest["utcOffsetMinutes"])
        assertTrue("null" !in text, "the manifest must not write a null literal, got:\n$text")
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

    /**
     * CHARACTERIZATION, before #177 adds anything. A set the lifter did not
     * append carries no `added` key in the manifest, and the reason this is
     * pinned BEFORE the key exists is that it must go on being true after: the
     * flag is written through `flag()`, which omits a false, so absence must
     * keep reading as "prescribed" for every set already archived as well as
     * for every ordinary set recorded from now on.
     *
     * The whole-text assertion is the load-bearing half. A `"added": false`
     * written by `bool()` instead of `flag()` would leave `manifestSet(text)`
     * non-null and would also change every existing archive's byte content for
     * no gain, and the two halves fail differently: the first names the key,
     * the second catches it appearing anywhere in the document at all.
     */
    @Test
    fun `an ordinary set's manifest says nothing about being appended`() = runTest {
        val text =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(imuStream(5L, stillSamples(45.0), storedRate = 100.0))),
            ).getValue("meta.json")
        assertNull(manifestSet(text)["added"])
        assertTrue("added" !in text, "a set nobody appended names the appended flag anyway:\n$text")
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

    // ---- issue #29: buildZip must actually leave the caller's dispatcher ---

    /**
     * The seam issue #29 needed and this repository did not have: proof, not
     * an assertion by reading, that [RawExporter.buildZip] dispatches through
     * whatever it is given rather than running inline on the caller's
     * dispatcher. Before the dispatcher parameter existed, nothing here or in
     * [SessionExporterTest] could have failed this test even if [buildZip]'s
     * body ran entirely on the caller's own dispatcher, because nothing
     * called [RecordingDispatcher.dispatch] to find out.
     */
    @Test
    fun `buildZip dispatches through its injected dispatcher`() = runTest {
        val dao =
            FakeSessionDao(
                session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 46_000L),
                rows = listOf(row(id = 5L)),
                streams = emptyMap(),
            )
        val repo = SessionRepository(dao, FakeExerciseDao())
        val recording = RecordingDispatcher()
        val exporter = SessionExporter(repo, dispatcher = recording)
        RawExporter(repo, exporter, appVersion = "0.1.37", dispatcher = recording).buildZip(1L)
        assertTrue(recording.dispatched, "buildZip never dispatched through the injected dispatcher")
    }

    // ---- issue #29/#90: minBpm reaches session.json through the fold -------

    /**
     * The other end of the fold, discharged directly rather than trusted by
     * inspection: `session.json`'s `hr.minBpm`, inside the same zip whose
     * per-set loop is what computed it, matches what that set's raw HRM
     * stream actually supports -- proof the value crossing from buildZip's
     * loop into SessionExporter through minBpmOverride is the right one, not
     * only that some value arrives.
     *
     * The row carries no stored hrEndOfSetBpm/hrAvgBpm/hrMaxBpm, so a passing
     * assertion also confirms the gate's fourth clause: minBpm alone is
     * enough to publish the block.
     */
    @Test
    fun `session json's minBpm is the fold's value, not a second inflate`() = runTest {
        val entries = zipOf(listOf(row(id = 5L)), mapOf(5L to listOf(hrStream(5L))))
        val session = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        val hr =
            session.getValue("exercises").jsonArray.single().jsonObject
                .getValue("sets").jsonArray.single().jsonObject.getValue("hr").jsonObject
        assertEquals(setOf("minBpm"), hr.keys, "only the fold's value should be present")
        assertEquals(120, hr.getValue("minBpm").jsonPrimitive.content.toInt())
    }

    /**
     * A single set cannot tell "the fold supplied 120" apart from "the fold
     * was silently skipped and SessionExporter inflated the same bytes
     * itself and got 120 anyway" -- both paths read the same underlying
     * stream, so they agree by construction. Two sets with two different
     * streams can: if minBpmBySet were built with the wrong key, or the fold
     * dropped and the fallback mis-keyed some other way, this is where it
     * would show up as one set's figure appearing on the other's.
     */
    @Test
    fun `two sets' minBpm figures are not swapped with each other`() = runTest {
        // Two samples each, deliberately not identical: a swapped field
        // (avgBpm read where minBpm belongs) would pass with hrStream's
        // single-sample fixture, where min, mean and max coincide. 100/140
        // makes min 100 and avg 120 -- different numbers -- so extracting the
        // wrong one from HrTrust.summarize would also be caught here, not
        // only a set's figure landing on the wrong set.
        val hrVaried =
            RawStreamEntity(
                id = 3L,
                setId = 6L,
                kind = RawStreamEntity.KIND_HRM,
                csvGzip =
                Gzip.compress(
                    HrCsv.encode(listOf(HrSample(1_000L, 100, listOf(500.0)), HrSample(2_000L, 140, listOf(500.0)))),
                ),
            )
        val entries =
            zipOf(
                listOf(row(id = 5L, orderIdx = 0), row(id = 6L, orderIdx = 1)),
                mapOf(5L to listOf(hrStream(5L)), 6L to listOf(hrVaried)),
            )
        val session = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        val setExports = session.getValue("exercises").jsonArray.single().jsonObject.getValue("sets").jsonArray
        val minBpms =
            setExports.map { it.jsonObject.getValue("hr").jsonObject.getValue("minBpm").jsonPrimitive.content.toInt() }
        assertEquals(listOf(120, 100), minBpms, "each set's minBpm should be its own trusted minimum")
    }

    /**
     * The fold path's own version of the pin `04b9b79` put on the standalone
     * path: `buildZip`'s loop folds the HRM stream's minimum into
     * `minBpmBySet` as it inflates each set's streams once, and a
     * `rest_before_hrm` stream sitting in the same list must not be the one
     * that lands there. The two kind strings are not merely different --
     * `"rest_before_hrm"` ends in `"hrm"`, so a match written as `contains`
     * or `endsWith` instead of `==` would pass this exact fixture.
     *
     * The impostor is listed LAST, not first. The fold has no `firstOrNull`
     * to fool by position -- it is a straight per-stream map write, so
     * whichever matching stream is processed last is the one that survives
     * in `minBpmBySet`. Putting the real HRM stream first and the impostor
     * last is the ordering that actually exercises a loosened match: with
     * the impostor first this fixture would still read 120 by coincidence,
     * because the real stream, processed second, would overwrite it even
     * under a broadened match -- proving nothing about the match itself.
     */
    @Test
    fun `a rest window stream does not reach the fold's minBpm`() = runTest {
        val restSamples = listOf(HrSample(1_000L, 44, listOf(1_350.0)), HrSample(2_000L, 47, listOf(1_270.0)))
        val entries =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(hrStream(5L), restStream(5L, restSamples))),
            )
        val session = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        val hr =
            session.getValue("exercises").jsonArray.single().jsonObject
                .getValue("sets").jsonArray.single().jsonObject.getValue("hr").jsonObject
        assertEquals(
            120,
            hr.getValue("minBpm").jsonPrimitive.content.toInt(),
            "a rest-window stream reached the fold's minBpm",
        )
    }

    /**
     * And with no HRM stream in the set at all, a lone `rest_before_hrm`
     * stream does not stand in for one: `minBpmBySet` stays null for that
     * set (never populated, since the fold's `when` never matches
     * `KIND_REST_BEFORE_HRM`), and with every other hr column also unset the
     * gate publishes no `hr` block rather than one built from the wrong
     * stream.
     */
    @Test
    fun `a rest window stream alone does not substitute for a missing HRM stream in the fold`() = runTest {
        val restSamples = listOf(HrSample(1_000L, 44, listOf(1_350.0)))
        val entries =
            zipOf(
                listOf(row(id = 5L)),
                mapOf(5L to listOf(restStream(5L, restSamples))),
            )
        val session = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        val setExport =
            session.getValue("exercises").jsonArray.single().jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertTrue("hr" !in setExport.keys, "a rest-window stream alone produced an hr block")
    }

    // ---- issue #29: the archive deflates at BEST_SPEED -----------------

    /**
     * A Deflater LEVEL is not recorded in the zip format itself -- only the
     * compression METHOD (deflate vs stored) is -- so it cannot be read back
     * out of the archive the way an entry's content can. What is observable
     * is size: BEST_SPEED trades ratio for speed, so for the same
     * compressible content it always produces output equal to or larger
     * than DEFAULT_COMPRESSION. This builds the real archive through
     * [RawExporter.buildZip] and a second one by hand at
     * Deflater.DEFAULT_COMPRESSION over the identical IMU content, and
     * asserts the real one is the larger of the two -- which stops being
     * true the moment BEST_SPEED regresses back to the library default.
     */
    @Test
    fun `the raw zip deflates at BEST_SPEED, not the library default`() = runTest {
        // Two real archives, identical rows and streams, differing only in
        // the injected level -- not a hand-built reference with different
        // entries, which the first version of this test used and which
        // passed even when the production default silently reverted to
        // DEFAULT_COMPRESSION, because the extra meta.json/session.json
        // entries' own overhead outweighed the level difference. Comparing
        // two same-shaped real archives removes that confound.
        val samples = stillSamples(45.0)
        val streamMap = mapOf(5L to listOf(imuStream(5L, samples, storedRate = 100.0)))
        val rows = listOf(row(id = 5L))
        val defaultSize = zipBytes(rows, streamMap, zipCompressionLevel = Deflater.DEFAULT_COMPRESSION).size
        val bestSpeedSize = zipBytes(rows, streamMap, zipCompressionLevel = Deflater.BEST_SPEED).size
        val productionSize = zipBytes(rows, streamMap).size

        assertTrue(
            bestSpeedSize > defaultSize,
            "expected BEST_SPEED ($bestSpeedSize B) to exceed DEFAULT_COMPRESSION ($defaultSize B) " +
                "on this compressible content",
        )
        assertEquals(bestSpeedSize, productionSize, "the production default should be BEST_SPEED")
    }
}
