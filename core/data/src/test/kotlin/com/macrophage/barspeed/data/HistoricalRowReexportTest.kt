package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.SessionExport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rows written by OLDER builds re-export through today's two writers without
 * throwing, and without silently losing the parts that still decode (#271).
 *
 * WHAT THIS IS NOT. It is not evidence that the field crash #271 opened with is
 * fixed, and it does not claim to be. The phone's own records traced every
 * v0.1.51 crash to an `OutOfMemoryError` in the interrupted-set listing, which
 * v0.1.52 replaced, and the emulator chain v0.1.41 -> v0.1.51 exported four
 * sessions recorded on older builds, and one on v0.1.51, three ways each --
 * fifteen exports -- without error. What has never run anywhere is a session holding
 * sets recorded on v0.1.50 or earlier exported on the CURRENT build -- the
 * ask field-42's comment closes on. That stays `[Field]`. These pins are the
 * regression floor beneath it: every row shape below is what an older build's
 * own types would have encoded, read off the tag rather than invented, and
 * each one goes through `SessionExporter.buildExport` (summary and detailed)
 * and `RawExporter.buildZip`.
 *
 * WHAT THESE DO NOT REACH. Three unguarded seams #271's own source reading
 * named stay uncovered, because no stored shape is known to reach them: the
 * cue stream's `CueCsv.decode(Gzip.decompress(...))` in `buildExport`, the
 * prep stream's `PrepWindowCsv.decode` in the raw-zip loop, and two streams of
 * one kind and one role on one set, which would write one zip entry name
 * twice. A green run here says nothing about any of the three.
 *
 * THE SHAPES, each taken from the tag named:
 *
 * - v0.1.0's analysis blob: `eccS` 0.0 and both pauses non-null, the 1.0
 *   semantics #38 describes, with a tempo-compliance block carrying the
 *   `eccentricS`/`concentricS` wire names; no geometry, no sensors, no
 *   `rpeScale`, no `failedByLifter`; a 10-column IMU stream (no
 *   `sample_idx`), an HR stream and a cue stream, roles all null (pre-v11);
 *   a dash tempo `3-1-1-0`, the form the v0.1.41 bench typed.
 * - v0.1.38 to v0.1.48's geometry: `sources` carries neither `sensorOnStack`
 *   (defaulted from v0.1.49) nor `bodyweight` (from v0.1.51); a failed set
 *   with `failedByLifter` null and a tempo `Tempo.parseOrNull` rejects.
 * - v0.1.44 to v0.1.46's `sensorsJson`: the retired `plannedCount` beside
 *   `count`, roles as enum NAMES, two IMU streams roled `a` and `b`.
 * - v0.1.44 to v0.1.46's one-stream `sensorsJson` (`plannedCount` 2, `count`
 *   1) on a hold corrected short of its target.
 *
 * GREEN WHEN WRITTEN. These characterise the current tree; they are not
 * differentials, and nothing in `src/main` changes with them. Nothing here
 * executes Room, SQLite or Android: what is pinned is the two writers'
 * handling of the rows `FakeSessionDao` hands them, never what Room migrated.
 */
class HistoricalRowReexportTest {
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

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

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

    // ---- stored text, as the older builds wrote it -------------------------

    /** v0.1.0's `RepAnalysis`: every field non-null, `eccS` 0.0 when unmeasured. */
    private fun oldRep(index: Int): String {
        val timings = """"index":$index,"eccS":0.0,"bottomPauseS":0.0,"conS":0.8,"topPauseS":0.0"""
        val speeds = """"meanConVelMps":0.52,"peakConVelMps":0.91,"meanEccVelMps":0.0,"peakEccVelMps":0.0"""
        return """{$timings,$speeds,"romM":0.41,"peakPowerW":null}"""
    }

    /** v0.1.0's `SetAnalysis`, with its tempo compliance under the historical wire names. */
    private val v010Analysis =
        """{"reps":[${oldRep(0)},${oldRep(1)}],"sampleRateHz":99.6,"velocityLossPct":67.7,""" +
            """"tempoCompliance":{"prescribed":{"eccentricS":3.0,"bottomPauseS":1.0,"concentricS":1.0,""" +
            """"topPauseS":0.0},"toleranceS":0.5,"phases":[{"phase":"eccentric","prescribedS":3.0,""" +
            """"actualMeanS":0.0,"worstDeviationS":3.0,"repsWithinTolerance":0,"repsEvaluated":2}],""" +
            """"repsFullyCompliant":0,"repsEvaluated":2},"verdicts":["Slow down the eccentric."]}"""

    private val emptyAnalysis =
        """{"reps":[],"sampleRateHz":99.4,"velocityLossPct":null,"tempoCompliance":null,"verdicts":[]}"""

    /** v0.1.38-v0.1.48's `ResolvedGeometry`: no `sources.sensorOnStack`, no `sources.bodyweight`. */
    private val v041Geometry =
        """{"startsWith":"ECCENTRIC","concentricUp":true,"horizontal":false,"sensorOnStack":false,""" +
            """"sensorInverted":false,"travelRatio":1.0,"kind":"DYNAMIC","bodyweight":false,""" +
            """"sources":{"startsWith":"SEEDED","concentric":"SEEDED","plane":"SEEDED","kind":"SEEDED",""" +
            """"travelRatio":"DEFAULT"}}"""

    /** v0.1.44-v0.1.46's dual `RecordedSensors`: the retired planned count, roles as enum names. */
    private val v044DualSensors = """{"plannedCount":2,"count":2,"expected":["A","B"],"analysed":"A"}"""

    /** v0.1.44-v0.1.46's shortfall shape: two planned, one recorded, no word. */
    private val v044SingleSensors = """{"plannedCount":2,"count":1}"""

    /** The 10-column IMU header every build before `sample_idx` wrote. */
    private fun tenColumnImu(startMs: Long, frames: Int): String = buildString {
        append("timestamp_ms,ax_g,ay_g,az_g,wx_dps,wy_dps,wz_dps,roll_deg,pitch_deg,yaw_deg\n")
        for (i in 0 until frames) {
            val az = if (i % 20 < 10) 1.05 else 0.95
            append("${startMs + i * 10L},0.010254,-0.998047,$az,")
            append("-11.7188,0.0000,0.0000,-88.5223,-0.5548,145.5359\n")
        }
    }

    private fun stream(id: Long, setId: Long, kind: String, text: String, rate: Double? = null, role: String? = null) =
        RawStreamEntity(
            id = id,
            setId = setId,
            kind = kind,
            csvGzip = Gzip.compress(text),
            sampleRateHz = rate,
            role = role,
        )

    // ---- the rows ----------------------------------------------------------

    private val v010Squat =
        SetRecordEntity(
            id = 1L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = 100.0,
            actualReps = 2,
            plannedReps = 5,
            rpe = 6,
            tempo = "3-1-1-0",
            startedAtMs = 1_000L,
            endedAtMs = 31_000L,
            analysisJson = v010Analysis,
        )

    private val v041Bench =
        SetRecordEntity(
            id = 2L,
            sessionId = 1L,
            orderIdx = 1,
            exerciseId = "bench_press",
            exerciseName = "Bench Press",
            loadKg = 80.0,
            actualReps = 3,
            plannedReps = 5,
            failed = true,
            limiter = "grip",
            tempo = "slow",
            startedAtMs = 100_000L,
            endedAtMs = 130_000L,
            analysisJson = emptyAnalysis,
            geometryJson = v041Geometry,
        )

    private val v044Dual =
        SetRecordEntity(
            id = 3L,
            sessionId = 1L,
            orderIdx = 2,
            exerciseId = "barbell_row",
            exerciseName = "Barbell Row",
            loadKg = 60.0,
            actualReps = 0,
            plannedReps = 8,
            startedAtMs = 200_000L,
            endedAtMs = 240_000L,
            analysisJson = emptyAnalysis,
            geometryJson = v041Geometry,
            sensorsJson = v044DualSensors,
        )

    private val v045Hold =
        SetRecordEntity(
            id = 4L,
            sessionId = 1L,
            orderIdx = 3,
            exerciseId = "plank",
            exerciseName = "Plank",
            loadKg = 0.0,
            actualReps = 0,
            actualDurationS = 15,
            plannedDurationS = 20,
            rpe = 7,
            failed = true,
            startedAtMs = 300_000L,
            endedAtMs = 316_000L,
            analysisJson = emptyAnalysis,
            sensorsJson = v044SingleSensors,
        )

    private val streams =
        mapOf(
            1L to listOf(
                stream(11L, 1L, RawStreamEntity.KIND_IMU, tenColumnImu(1_000L, 300), rate = 99.6),
                stream(
                    12L,
                    1L,
                    RawStreamEntity.KIND_HRM,
                    "timestamp_ms,hr_bpm,rr_ms\n1500,118,508.0|512.0\n2500,121,\n",
                ),
                stream(13L, 1L, RawStreamEntity.KIND_CUES, "timestamp_ms,cue\n1725,Down\n4725,Up\n"),
            ),
            3L to listOf(
                stream(31L, 3L, RawStreamEntity.KIND_IMU, tenColumnImu(200_000L, 200), rate = 99.4, role = "a"),
                stream(32L, 3L, RawStreamEntity.KIND_IMU, tenColumnImu(200_003L, 200), role = "b"),
            ),
            4L to listOf(
                stream(41L, 4L, RawStreamEntity.KIND_IMU, tenColumnImu(300_000L, 150), rate = 99.1),
            ),
        )

    private fun repository(): SessionRepository {
        val session = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 400_000L)
        val rows = listOf(v010Squat, v041Bench, v044Dual, v045Hold)
        return SessionRepository(FakeSessionDao(session, rows, streams), FakeExerciseDao())
    }

    private fun SessionExport.set(exercise: String) = exercises.single { it.exercise == exercise }.sets.single()

    // ---- the pins ----------------------------------------------------------

    /** Both JSON forms are produced, and every set is in each of them. */
    @Test
    fun `every older row shape re-exports as summary and detailed JSON`() = runTest {
        val exporter = SessionExporter(repository())
        for (detail in listOf(false, true)) {
            val export = assertNotNull(exporter.buildExport(1L, includeRepDetail = detail), "detail=$detail")
            assertEquals(
                listOf("back_squat", "bench_press", "barbell_row", "plank"),
                export.exercises.map { it.exercise },
                "detail=$detail lost a set",
            )
            assertNotNull(exporter.exportJson(1L, includeRepDetail = detail), "detail=$detail did not encode")
        }
    }

    /**
     * v0.1.0's analysis blob still decodes: its two reps reach the detailed
     * export, so an old set does not silently publish as one with no reps.
     */
    @Test
    fun `a 1_0-era analysis blob still publishes its reps`() = runTest {
        val export = SessionExporter(repository()).buildExport(1L, includeRepDetail = true)!!
        val squat = export.set("back_squat")
        assertEquals(2, squat.repMetrics?.size, "the old analysis blob did not decode")
        assertNull(squat.geometry, "a row with no stored geometry was given one")
        assertNull(squat.rpeScale, "a row with no stored rpeScale was given one")
        assertNull(squat.failedByLifter)
        assertEquals("3-1-1-0", squat.prescription.tempoPrescribed)
    }

    /**
     * What a 1.0 set publishes today, figure by figure (#38). Each rep's
     * `ecc_s` is copied out of the stored blob, so the 0.0 an unmeasured 1.0
     * eccentric was stored as is still published as 0.0. The set's
     * `velocityLoss_pct` is NOT: it is re-derived from the stored reps at
     * export (#83, 1.9), so the blob's 67.7 is never read -- the two stored
     * reps are equally fast, so the last is the fastest and no loss is
     * published at all. And the row predates database 21, so it names no
     * contract for either figure.
     */
    @Test
    fun `a 1_0-era set publishes its stored eccentrics but re-derives its loss`() = runTest {
        val squat = SessionExporter(repository()).buildExport(1L, includeRepDetail = true)!!.set("back_squat")
        assertEquals(listOf(0.0, 0.0), squat.repMetrics?.map { it.eccS }, "a stored rep figure was not copied out")
        assertNull(squat.velocityLossPct, "the stored velocityLossPct was published")
        assertEquals("terminalRepIsFastest", squat.velocityLossBasis)
        assertNull(squat.analysisSchemaVersion, "a pre-v21 row was given a contract")
    }

    /**
     * The v0.1.38-v0.1.48 geometry decodes despite the two `sources` keys it
     * never wrote, and they publish as `default` -- the geometry is not
     * dropped from the set.
     */
    @Test
    fun `a geometry written before the two later source keys still publishes`() = runTest {
        val bench = SessionExporter(repository()).buildExport(1L, includeRepDetail = false)!!.set("bench_press")
        val geometry = assertNotNull(bench.geometry, "the old geometry was dropped")
        assertEquals("default", geometry.source.sensorOnStack)
        assertEquals("default", geometry.source.bodyweight)
        assertNull(bench.velocityLossRegime, "an unparseable tempo produced a regime")
        assertTrue(bench.failed)
        assertNull(bench.failedByLifter, "a pre-v15 failure was given an author")
    }

    /** The retired `plannedCount` shapes still decode, one and two streams alike. */
    @Test
    fun `sensors written under the retired planned count still publish`() = runTest {
        val export = SessionExporter(repository()).buildExport(1L, includeRepDetail = true)!!
        val dual = assertNotNull(export.set("barbell_row").sensors, "the two-stream declaration was dropped")
        assertEquals(2, dual.count)
        assertEquals(listOf("a", "b"), dual.expected)
        val single = assertNotNull(export.set("plank").sensors, "the one-stream declaration was dropped")
        assertEquals(1, single.count)
        assertNull(single.shortfall, "a reason was synthesised from the retired count")
        assertEquals(15, export.set("plank").durationS)
    }

    /**
     * The raw archive is produced over the same rows: one entry per stored
     * stream, the two roled IMU streams under distinct names, and both
     * documents present and parseable.
     */
    @Test
    fun `every older row shape goes into the raw archive`() = runTest {
        val repository = repository()
        val bytes =
            assertNotNull(RawExporter(repository, SessionExporter(repository), appVersion = "0.2.0").buildZip(1L))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        assertEquals(streams.values.sumOf { it.size } + 2, entries.size, "entries: ${entries.keys}")
        assertTrue("set03_barbell_row_imu-a.csv" in entries, "${entries.keys}")
        assertTrue("set03_barbell_row_imu-b.csv" in entries, "${entries.keys}")
        val meta = Json.parseToJsonElement(entries.getValue("meta.json")).jsonObject
        assertEquals(4, meta.getValue("sets").jsonArray.size)
        val document = Json.parseToJsonElement(entries.getValue("session.json")).jsonObject
        assertEquals(4, document.getValue("exercises").jsonArray.size)
    }
}
