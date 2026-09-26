package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.DualShortfall
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
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
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What `session.json` says about each unit's DELIVERED frame rate, issue #321.
 *
 * DIFFERENTIALS. At the commit that adds this file the exporter publishes
 * neither key, so every case expecting one fails. The cases expecting absence
 * pass at both commits and are here so the fix cannot publish where it must
 * not. The fix is the commit after it.
 *
 * WHY IT MATTERS TO THE LIFTER. On field-42 one unit's link delivered about 44
 * frames a second against its partner's 99, on every committed capture of
 * that session, and nothing the lifter or a coach reads said so. The set was
 * analysed as if the stream were whole.
 *
 * THE STREAMS are synthetic, built to the two shapes the committed captures
 * carry (`DeliveredRateFieldTest` in `:core:dsp` reads the real ones). Role a
 * is field-42's slow link inside the working window, four frames every 90 ms,
 * with a healthy prep before it and a healthy re-rack after it. Role b is a
 * healthy link throughout, three frames every 30 ms, offset 15 ms so no stamp
 * lands on a window bound. The window is work start 9,000 to a `Done` at
 * 39,000, 30 s.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it. The
 * fakes are this file's own, as every test file in this module keeps its own.
 */
class SessionExportDeliveredRateTest {
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

    /** [bursts] notifications [spacingMs] apart from [startMs], [perBurst] frames each. */
    private fun bursty(startMs: Long, bursts: Int, perBurst: Int, spacingMs: Long): List<ImuSample> =
        (0 until bursts).flatMap { burst ->
            List(perBurst) { ImuSample(startMs + burst * spacingMs, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        }

    /** Healthy prep to 8,985; 1,336 frames 90 ms apart in the window; healthy re-rack from 39,015. */
    private val slowInWindow =
        bursty(15L, 300, 3, 30L) + bursty(9_000L, 334, 4, 90L) + bursty(39_015L, 100, 3, 30L)

    /** Three frames every 30 ms from 15 to 42,975: 3,000 frames inside the window. */
    private val healthy = bursty(15L, 1433, 3, 30L)

    private fun imuStream(id: Long, role: String?, samples: List<ImuSample>) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples)),
        sampleRateHz = 99.0,
        role = role,
    )

    private val prep = RawStreamEntity(
        id = 8L,
        setId = 5L,
        kind = RawStreamEntity.KIND_PREP,
        csvGzip = Gzip.compress(PrepWindowCsv.encode(PrepWindow(15L, 9_000L))),
    )

    private val cues = RawStreamEntity(
        id = 9L,
        setId = 5L,
        kind = RawStreamEntity.KIND_CUES,
        csvGzip = Gzip.compress(CueCsv.encode(listOf(VoiceCue(9_000L, "Up"), VoiceCue(39_000L, "Done")))),
    )

    private val dualStreams = listOf(imuStream(1L, "a", slowInWindow), imuStream(2L, "b", healthy), prep, cues)

    private val dual =
        RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)

    private fun row(sensors: RecordedSensors, tempo: String? = "3010") = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "assisted_pull_up",
        exerciseName = "Assisted Pull-Up",
        loadKg = 22.6,
        actualReps = 8,
        repsManual = true,
        plannedReps = 8,
        startedAtMs = 15L,
        endedAtMs = 43_000L,
        // A guided set, because the window's end depends on it: `Done`
        // closes the window on a set a cadence ran on and not on one the
        // lifter counted, and `RepsSourcePolicy.guideCounted` derives which
        // from this frozen tempo on a row with no stored geometry (#285).
        tempo = tempo,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = json.encodeToString(RecordedSensors.serializer(), sensors),
    )

    private fun repository(streams: List<RawStreamEntity>, sensors: RecordedSensors, tempo: String?) =
        SessionRepository(FakeSessionDao(listOf(row(sensors, tempo)), mapOf(5L to streams)), FakeExerciseDao())

    private fun sensorsOf(document: String): JsonObject = Json.parseToJsonElement(document)
        .jsonObject.getValue("exercises").jsonArray.single()
        .jsonObject.getValue("sets").jsonArray.single()
        .jsonObject.getValue("sensors").jsonObject

    private suspend fun sensorsObject(
        streams: List<RawStreamEntity> = dualStreams,
        sensors: RecordedSensors = dual,
        tempo: String? = "3010",
        detail: Boolean = true,
    ): JsonObject {
        val exporter = SessionExporter(repository(streams, sensors, tempo), dispatcher = Dispatchers.Default)
        return sensorsOf(exporter.exportJson(1L, includeRepDetail = detail)!!)
    }

    private fun JsonObject.rates(): Map<String, Double>? =
        get("deliveredRate_hz")?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toDouble() }

    private fun JsonObject.spacings(): Map<String, Long>? =
        get("burstSpacing_ms")?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toLong() }

    /**
     * field-42's shape, published: role a's link delivered 44.5 frames a second
     * over the window, in notifications 90 ms apart, and role b's 100.0, 30 ms
     * apart.
     *
     * 1,336 frames over 30 s is 44.53, published to one decimal. The healthy
     * prep and re-rack either side are not counted: a whole-capture figure
     * would have diluted the slow window with them.
     */
    @Test
    fun `a dual set publishes each unit's delivered rate and spacing`() = runTest {
        val sensors = sensorsObject()
        assertEquals(mapOf("a" to 44.5, "b" to 100.0), sensors.rates(), "the delivered rates were not published")
        assertEquals(mapOf("a" to 90L, "b" to 30L), sensors.spacings(), "the burst spacings were not published")
    }

    /**
     * The summary export carries them too. It is the export a reader most
     * often holds without the archive beside it, and a unit that delivered
     * less than half its frames is not detail.
     */
    @Test
    fun `the summary export carries the delivered rates too`() = runTest {
        assertEquals(mapOf("a" to 44.5, "b" to 100.0), sensorsObject(detail = false).rates())
    }

    /**
     * The window ends where `rollExcursion_deg`'s does, and a `Done` on a set
     * no cadence ran on does not end it (#285).
     *
     * With no tempo the `Done` is the rep-count milestone, so the window runs
     * to role a's last row at 41,985. That takes in its 300 healthy re-rack
     * frames: 1,636 frames over 32,985 ms, 49.6. The raw archive bounds its
     * roll window the same way, and a second answer to "when did this set
     * end" inside one archive is the drift `RollExcursion` exists to prevent.
     */
    @Test
    fun `a Done on a set no cadence ran on does not close the window`() = runTest {
        assertEquals(49.6, sensorsObject(tempo = null).rates()?.get("a"), "the milestone closed the window")
    }

    /**
     * A role that streamed nothing has no entry, and the role that did keeps
     * its own.
     */
    @Test
    fun `only a role that streamed is published`() = runTest {
        val sensors = sensorsObject(streams = listOf(imuStream(2L, "b", healthy), prep, cues))
        assertEquals(mapOf("b" to 100.0), sensors.rates())
        assertEquals(mapOf("b" to 30L), sensors.spacings())
    }

    /**
     * A stream under a role the set did not arm has no entry, though it
     * streamed.
     *
     * The published description says only roles in `present` appear, and
     * `present` is the armed roles that streamed. A stream carrying an unarmed
     * role is not expected from the record path, so this is a synthetic shape.
     * It pins the filter the description states rather than a case seen in a
     * capture.
     */
    @Test
    fun `a stream under a role the set did not arm is not published`() = runTest {
        val sensors =
            sensorsObject(
                sensors = RecordedSensors(count = 1, expected = listOf(SensorRole.A), analysed = SensorRole.A),
            )
        assertEquals(mapOf("a" to 44.5), sensors.rates(), "an unarmed role's stream was published")
        assertEquals(mapOf("a" to 90L), sensors.spacings(), "an unarmed role's spacing was published")
    }

    /**
     * A set whose single stream carries no role publishes neither key. There
     * is no role to key the figure by, and the raw archive's set-level
     * `sampleRate_hz` is that stream's rate.
     */
    @Test
    fun `a set whose stream carries no role publishes neither key`() = runTest {
        val sensors =
            sensorsObject(
                streams = listOf(imuStream(1L, null, healthy), prep, cues),
                sensors = RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_UNASSIGNED),
            )
        assertNull(sensors["deliveredRate_hz"], "a rate was keyed by a role the stream does not carry")
        assertNull(sensors["burstSpacing_ms"], "a spacing was keyed by a role the stream does not carry")
    }

    /**
     * The copy of `session.json` inside the raw archive says what the
     * standalone document says. Both are built by `SessionExporter`, and the
     * archive's copy is where the field ingest reads it.
     */
    @Test
    fun `the archive's session json carries the same figures`() = runTest {
        val repository = repository(dualStreams, dual, "3010")
        val bytes = RawExporter(repository, SessionExporter(repository), appVersion = "0.1.56").buildZip(1L)!!
        var document: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val text = zin.readBytes().decodeToString()
                if (entry.name == "session.json") document = text
            }
        }
        val sensors = sensorsOf(assertNotNull(document, "the archive holds no session.json"))
        assertEquals(mapOf("a" to 44.5, "b" to 100.0), sensors.rates())
        assertEquals(mapOf("a" to 90L, "b" to 30L), sensors.spacings())
    }
}
