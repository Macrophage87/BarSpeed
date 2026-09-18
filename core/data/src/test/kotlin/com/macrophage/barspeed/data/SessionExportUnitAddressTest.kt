package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `session.json` says about WHICH PHYSICAL UNIT carried each role on a
 * set recorded with two accelerometers, issue #260.
 *
 * CHARACTERIZATION, at this commit: nothing. The block publishes `count`,
 * `expected`, `present` and `analysedRole`, and a reader holding the archive
 * cannot say which of two identical WT901 units role `a` was -- so the whole
 * mount table of a dual-unit session rests on the owner remembering, and the
 * owner says that memory will be wrong often.
 *
 * These are the BEFORE side of the differential. The commit that flips them is
 * the red; the one after it is the fix.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it. The
 * fakes are this file's own, as every test file in this module keeps its own.
 */
class SessionExportUnitAddressTest {
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

    private fun samples(count: Int) = (0 until count).map { i ->
        ImuSample(1_000L + i * 10L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0 + i, -20.0, 30.0)
    }

    private fun imuStream(id: Long, role: String) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples(100))),
        sampleRateHz = 98.5,
        role = role,
    )

    private fun row(sensors: RecordedSensors) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "lat_pulldown",
        exerciseName = "Lat Pulldown",
        loadKg = 50.0,
        actualReps = 9,
        repsManual = true,
        plannedReps = 9,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = json.encodeToString(RecordedSensors.serializer(), sensors),
    )

    private val dual =
        RecordedSensors(
            count = 2,
            expected = listOf(SensorRole.A, SensorRole.B),
            analysed = SensorRole.A,
        )

    private suspend fun sensorsObject(sensors: RecordedSensors = dual): JsonObject {
        val dao =
            FakeSessionDao(
                listOf(row(sensors)),
                mapOf(5L to listOf(imuStream(1L, "a"), imuStream(2L, "b"))),
            )
        val exporter =
            SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single()
            .jsonObject.getValue("sensors").jsonObject
    }

    /**
     * A dual set publishes four keys and none of them names a unit.
     *
     * The key set is asserted whole rather than one absence at a time: an
     * identity published under some other name would satisfy a single
     * `assertNull` and still be a second place this fact could live.
     */
    @Test
    fun `a dual set publishes no physical unit for either role`() = runTest {
        val sensors = sensorsObject()

        assertEquals(
            setOf("count", "expected", "present", "analysedRole"),
            sensors.keys,
            "the exported sensors block has changed shape, so this pin is not the before side",
        )
        assertNull(sensors["unitAddresses"], "the export already names the units")
    }
}
