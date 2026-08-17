package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SyntheticSets
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
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

    private fun row(
        id: Long,
        exerciseId: String = "plank",
        actualReps: Int = 0,
        durationS: Int? = 45,
        analysis: SetAnalysis = timedAnalysis(),
        orderIdx: Int = 0,
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
     */
    @Test
    fun `an analysed set publishes the rate it was analysed at`() = runTest {
        val samples = stillSamples(20.0)
        val manifest =
            meta(
                listOf(row(id = 5L, exerciseId = "back_squat", durationS = null, analysis = analysedAt(100.0))),
                mapOf(5L to listOf(imuStream(5L, samples, storedRate = 100.0))),
            )
        assertEquals(100.0, manifest.set(0).num("sampleRate_hz"))
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
}
