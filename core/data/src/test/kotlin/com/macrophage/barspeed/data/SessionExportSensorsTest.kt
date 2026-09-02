package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.DualShortfall
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

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

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

    private fun rowWithSensorsJson(sensorsJson: String?) = SetRecordEntity(
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
        sensorsJson = sensorsJson,
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

    /**
     * The same export path fed a stored `sensorsJson` VERBATIM rather than a
     * [RecordedSensors] this build can construct.
     *
     * The only way to stand a row an OLDER build wrote in front of the current
     * decoder: `plannedCount` is not a field of this build's data class, so it
     * cannot be encoded from Kotlin at all and has to be handed over as text.
     */
    private suspend fun setObjectFromStoredJson(sensorsJson: String, streams: List<RawStreamEntity>): JsonObject {
        val dao = FakeSessionDao(listOf(rowWithSensorsJson(sensorsJson)), mapOf(5L to streams))
        val exporter =
            SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
    }

    private val dual =
        RecordedSensors(
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
        assertEquals(2, sensors.getValue("count").jsonPrimitive.int)
        assertEquals(listOf("a", "b"), sensors.roles("expected"))
        assertEquals(listOf("a", "b"), sensors.roles("present"))
        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content)
        assertNull(sensors["shortfall"], "nothing was in the way of a set that armed both")
        assertNull(
            sensors["plannedCount"],
            "the export still publishes a planned count nobody planned",
        )
    }

    /**
     * An armed role whose unit captured nothing is in `expected` and not in
     * `present`.
     *
     * The whole point of storing both lists. Without them this set is
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
     * A stored declaration naming a role that captured nothing is republished
     * as it stands, rather than quietly renamed to the survivor.
     *
     * THE EXPORTER RE-DECIDES NOTHING, and that is what this pins. Since #207
     * the record path analyses a role that streamed, so this shape is a row an
     * OLDER build wrote -- or one where nothing streamed at all -- and its
     * figures were computed from the role it names. Re-pointing the document
     * at role `b` here would publish that row's numbers under a sensor that
     * did not produce them.
     *
     * What such a row means to a reader is stated in the published
     * `analysedRole` description rather than derived from this comparison:
     * with the fallback in place an analysed role IS in `present` whether or
     * not it moved, and `analysedFellBack` is the key that separates them.
     */
    @Test
    fun `an analysed role that captured nothing is still the analysed role`() = runTest {
        val set = setObject(dual, listOf(imuStream(2L, "b", count = 100)))

        val sensors = set.getValue("sensors").jsonObject
        assertEquals("a", sensors.getValue("analysedRole").jsonPrimitive.content)
        assertEquals(listOf("b"), sensors.roles("present"))
    }

    /**
     * DIFFERENTIAL, issue #207. A set whose figures came from a unit it was
     * not pointed at publishes that fact, and an ordinary one publishes
     * nothing.
     *
     * The key a reader cannot derive. Before the fallback, an analysed role
     * missing from `present` was the marker; after it, the analysed role is
     * present in both cases and the comparison separates nothing. What is left
     * to say -- "these figures came from the unit the app was pointed at", so
     * they are comparable with the rest of a corpus recorded the same way --
     * has to be said outright.
     *
     * Absence rather than a written false on the ordinary set, which is the
     * rule `failed` and `warmup` already follow: the exporter drops a default,
     * and omission reads correctly where false is the unremarkable normal.
     */
    @Test
    fun `a set analysed from a unit it was not pointed at publishes that it moved`() = runTest {
        val fellBack =
            setObject(
                dual.copy(analysed = SensorRole.B, analysedFellBack = true),
                listOf(imuStream(2L, "b", count = 100)),
            ).getValue("sensors").jsonObject

        assertEquals("b", fellBack.getValue("analysedRole").jsonPrimitive.content)
        assertEquals(listOf("b"), fellBack.roles("present"))
        assertTrue(
            fellBack.getValue("analysedFellBack").jsonPrimitive.content.toBoolean(),
            "the export never says the analysed role is not the one the set armed",
        )

        val ordinary =
            setObject(dual, listOf(imuStream(1L, "a", count = 100))).getValue("sensors").jsonObject
        assertTrue(
            "analysedFellBack" !in ordinary,
            "an ordinary dual set published the key anyway: $ordinary",
        )
    }

    /**
     * DIFFERENTIAL, issue #198. A set that recorded one stream because two
     * PAIRED units could not be told apart publishes WHICH gap it was.
     *
     * `a shortfall publishes the ask with an empty role list` stood here and
     * published `plannedCount: 2, count: 1`, which is the ask. There is no ask
     * left. A reader has to be able to separate two facts about a session --
     * there was one sensor, and there were two and one was unusable -- and
     * with the pair gone the reason is the only thing that can carry the
     * second.
     *
     * `count` is 1 and `expected` is empty, and the two disagreeing is still
     * the information: reading the count off the list would publish a set that
     * armed nothing.
     */
    @Test
    fun `a set that could not tell two units apart publishes which gap it was`() = runTest {
        val set =
            setObject(
                RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_UNASSIGNED),
                listOf(imuStream(1L, null, count = 100)),
            )

        val sensors = set.getValue("sensors").jsonObject
        assertEquals("rolesUnassigned", sensors.getValue("shortfall").jsonPrimitive.content)
        assertEquals(1, sensors.getValue("count").jsonPrimitive.int)
        assertEquals(emptyList(), sensors.roles("expected"))
        assertEquals(emptyList(), sensors.roles("present"))
        assertNull(sensors["analysedRole"], "a set with no roles named an analysed one")
    }

    /**
     * DIFFERENTIAL, issue #198. The two surviving gaps publish as two
     * different words.
     *
     * Collapsing them would lose what the coach is supposed to go and do:
     * label a unit, or fix two units carrying one label. They are separate
     * sentences on both screens and they stay separate here.
     */
    @Test
    fun `a collision publishes as its own word rather than as the other gap`() = runTest {
        val set =
            setObject(
                RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_COLLIDE),
                listOf(imuStream(1L, null, count = 100)),
            )

        assertEquals(
            "rolesCollide",
            set.getValue("sensors").jsonObject.getValue("shortfall").jsonPrimitive.content,
        )
    }

    /**
     * CHARACTERIZATION, not a differential: this is what the build ALREADY
     * does with a row a released build wrote, and it is pinned here because
     * nothing on this branch said so.
     *
     * Until #198, `SensorCapturePolicy.recorded(plannedCount, roster)` wrote
     * `plannedCount = 2, count = 1, expected = []` for every non-dual set under
     * a plan -- or a lifter adjustment -- declaring two sensors. Those rows are
     * on installed phones from v0.1.44 onward. `plannedCount` is an unknown key
     * to this build and `SessionRepository`'s `Json { ignoreUnknownKeys = true }`
     * skips it, so the row now decodes to `count = 1`, an empty `expected` and
     * NO shortfall, and re-exports as a block the published contract reads as
     * "nothing was in the way".
     *
     * The reason such a set recorded one stream is therefore NOT recoverable
     * from a 1.15 export of it. The row's own JSON on the device is untouched
     * and still carries the key; only the document drops it. That is stated in
     * the published `shortfall` description rather than fixed, because
     * synthesising a shortfall from a count nobody wrote would publish a reason
     * this build did not observe -- see the same file's `a one-sensor set
     * publishes no sensors key` for the shape the reader is left with.
     */
    @Test
    fun `a row written under the retired planned count re-exports with no reason`() = runTest {
        val set =
            setObjectFromStoredJson(
                """{"plannedCount":2,"count":1,"expected":[]}""",
                listOf(imuStream(1L, null, count = 100)),
            )

        val sensors = set.getValue("sensors").jsonObject
        assertEquals(1, sensors.getValue("count").jsonPrimitive.int)
        assertEquals(emptyList(), sensors.roles("expected"))
        assertEquals(emptyList(), sensors.roles("present"))
        assertNull(sensors["shortfall"], "a reason was invented for a row that stored none")
        assertNull(sensors["plannedCount"], "the retired key survived into a 1.15 document")
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
