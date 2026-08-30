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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `session.json` says about a set recorded with two accelerometers
 * (#156).
 *
 * DIFFERENTIALS. Every assertion about a `sensors` key fails at the commit
 * that introduces it: the exporter reads no declaration and writes no such
 * key. The commit after this one is what makes them pass.
 *
 * A separate file from [SessionExporterTest] because that class is already at
 * detekt's LargeClass limit, the same reason [SessionExportPrepTest] and
 * [SessionExportRepMarksTest] are separate. The fakes are this file's own, as
 * every test file in this module keeps its own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it.
 */
class SessionExportSensorsTest {
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

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    private fun samples(count: Int, stepMs: Long) = (0 until count).map { i ->
        ImuSample(1_000L + i * stepMs, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0 + i, -20.0, 30.0)
    }

    private fun imuStream(id: Long, role: String?, count: Int, stepMs: Long = 10L) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples(count, stepMs))),
        sampleRateHz = if (role == null || role == "a") 98.5 else null,
        role = role,
    )

    private fun row(sensors: RecordedSensors?) = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        plannedReps = 5,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        sensorsJson = sensors?.let { json.encodeToString(RecordedSensors.serializer(), it) },
    )

    private suspend fun setObject(
        sensors: RecordedSensors?,
        streams: List<RawStreamEntity>,
        includeRepDetail: Boolean = true,
    ): JsonObject {
        val dao = FakeSessionDao(listOf(row(sensors)), mapOf(5L to streams))
        val exporter =
            SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private val dual =
        RecordedSensors(
            plannedCount = 2,
            count = 2,
            expected = listOf(SensorRole.A, SensorRole.B),
            analysed = SensorRole.A,
        )

    private fun JsonObject.roles(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    // ---- the declaration ----------------------------------------------------

    /**
     * A dual set publishes all four statements, and `present` is the one that
     * is observed rather than declared.
     *
     * The summary document lists no filenames at all, so without `present` a
     * reader holding only `session.json` cannot tell a captured role from a
     * missing one -- and inferring it is exactly the guess this key exists to
     * remove.
     */
    @Test
    fun `a dual set publishes its counts, the roles it armed and the roles that arrived`() = runTest {
        val set =
            setObject(dual, listOf(imuStream(1L, "a", count = 100), imuStream(2L, "b", count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals(2, sensors.getValue("plannedCount").jsonPrimitive.int)
        assertEquals(2, sensors.getValue("count").jsonPrimitive.int)
        assertEquals(listOf("a", "b"), sensors.roles("expected"))
        assertEquals(listOf("a", "b"), sensors.roles("present"))
        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content)
    }

    /**
     * An armed role whose unit captured nothing is in `expected` and not in
     * `present`.
     *
     * The whole point of storing the ask. Without both lists this set is
     * indistinguishable from one that was only ever armed for a single sensor,
     * and the second unit's failure disappears from the corpus.
     */
    @Test
    fun `a role that captured nothing is expected and not present`() = runTest {
        val set = setObject(dual, listOf(imuStream(1L, "a", count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals(listOf("a", "b"), sensors.roles("expected"))
        assertEquals(listOf("a"), sensors.roles("present"))
    }

    /**
     * When it is the ANALYSED unit that dropped out, the export says so rather
     * than quietly renaming the survivor.
     *
     * `analysedRole` is a fact about which sensor the app was pointed at, not
     * about which one produced data, so it keeps naming `a`. A reader then
     * sees an analysed role absent from `present` and knows the summary
     * figures are empty rather than wrong -- which is the state that would
     * otherwise be unsayable.
     */
    @Test
    fun `an analysed role that captured nothing is still the analysed role`() = runTest {
        val set = setObject(dual, listOf(imuStream(2L, "b", count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content)
        assertEquals(listOf("b"), sensors.roles("present"))
    }

    /**
     * A set that asked for two and armed one publishes the ask, with no roles.
     *
     * `count` is 1 and `expected` is empty, and the two disagreeing is the
     * information: reading the count off the list would publish a set that
     * armed nothing.
     */
    @Test
    fun `a shortfall publishes the ask with an empty role list`() = runTest {
        val set = setObject(RecordedSensors(plannedCount = 2, count = 1), listOf(imuStream(1L, null, count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals(2, sensors.getValue("plannedCount").jsonPrimitive.int)
        assertEquals(1, sensors.getValue("count").jsonPrimitive.int)
        assertEquals(emptyList(), sensors.roles("expected"))
        assertEquals(emptyList(), sensors.roles("present"))
        assertNull(sensors["analysedRole"], "a set with no roles named an analysed one")
    }

    /**
     * The lifter's in-app adjustment is visible against what the plan asked
     * for.
     *
     * The #151 pair, designed in from the start: a plan declaring one sensor
     * and a lifter arming two is a real and common case on a barbell day, and
     * a document publishing only the outcome cannot be used to author the next
     * plan.
     */
    @Test
    fun `an adjustment away from the plan is visible as the pair`() = runTest {
        val adjusted = dual.copy(plannedCount = 1)
        val set = setObject(adjusted, listOf(imuStream(1L, "a", count = 100), imuStream(2L, "b", count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals(1, sensors.getValue("plannedCount").jsonPrimitive.int)
        assertEquals(2, sensors.getValue("count").jsonPrimitive.int)
    }

    // ---- what must not move --------------------------------------------------

    /**
     * A one-sensor set publishes no `sensors` key at all.
     *
     * This is what keeps such an export what earlier versions wrote, and it is
     * the control the rest of this file is read against. It is here, red-free
     * at this commit, because the mutation that matters -- emitting the block
     * unconditionally -- has to have something to red.
     */
    @Test
    fun `a one-sensor set publishes no sensors key`() = runTest {
        val set = setObject(sensors = null, streams = listOf(imuStream(1L, null, count = 100)))

        assertNull(set["sensors"], "a one-sensor export grew a key")
    }

    /**
     * The declaration is published in the summary export too, not only the
     * detailed one.
     *
     * Same reasoning as `repMetricsComplete`: how many sensors a set was
     * recorded with qualifies every figure the summary publishes, so a caveat
     * that appears only when per-rep detail was requested leaves the
     * summary-only reader holding the numbers with the warning removed.
     */
    @Test
    fun `the declaration is not gated on the detailed export`() = runTest {
        val set =
            setObject(
                dual,
                listOf(imuStream(1L, "a", count = 100), imuStream(2L, "b", count = 100)),
                includeRepDetail = false,
            )

        assertTrue("sensors" in set.keys, "the summary export dropped the sensor declaration")
    }
}
