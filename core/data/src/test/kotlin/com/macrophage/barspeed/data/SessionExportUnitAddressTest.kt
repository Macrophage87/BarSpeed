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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `session.json` says about WHICH PHYSICAL UNIT carried each role on a
 * set recorded with two accelerometers, issue #260.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that writes it, and
 * the file does not even COMPILE at it: the `sensorRoleByAddress` snapshot the
 * exporter is handed below is the parameter the fix adds. That is stated rather
 * than hidden -- it is a weaker red than an AssertionError, and the four
 * contract differentials in the commit before this one are the assertion-level
 * half. The commit after this one is the fix.
 *
 * WHY IT MATTERS TO THE LIFTER: a reader holding the archive cannot say which
 * of two identical magnet-mounted WT901 units role `a` was, so the whole mount
 * table of a dual-unit session rests on the owner remembering -- *"I'm not
 * really sure. Will check each time, they're likely to get mixed up a lot."*
 * (owner, 2026-09-05, asked which unit was role a).
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

    private fun imuStream(id: Long, role: String, setId: Long = 5L) = RawStreamEntity(
        id = id,
        setId = setId,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples(100))),
        sampleRateHz = 98.5,
        role = role,
    )

    private fun row(sensors: RecordedSensors, id: Long = 5L, orderIdx: Int = 0) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = orderIdx,
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

    private val unitA = "C0:82:2D:8A:1D:3F"
    private val unitB = "C0:82:2D:8A:0C:7A"
    private val bothLabelled = mapOf(unitA to SensorRole.A, unitB to SensorRole.B)

    private suspend fun sensorsObject(
        sensors: RecordedSensors = dual,
        roleByAddress: Map<String, SensorRole> = bothLabelled,
    ): JsonObject {
        val dao =
            FakeSessionDao(
                listOf(row(sensors)),
                mapOf(5L to listOf(imuStream(1L, "a"), imuStream(2L, "b"))),
            )
        val exporter =
            SessionExporter(
                SessionRepository(dao, FakeExerciseDao()),
                dispatcher = Dispatchers.Default,
                sensorRoleByAddress = { roleByAddress },
            )
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single()
            .jsonObject.getValue("sensors").jsonObject
    }

    /**
     * A dual set names the unit behind each role it armed.
     *
     * The reading key this whole change exists for: with it, an analysis can
     * say "role a = the unit ending 1D:3F" and the owner labels that unit once
     * with a sticker. Without it, the mount of every dual-unit set is whatever
     * the owner remembers.
     */
    @Test
    fun `a dual set names the unit behind each role`() = runTest {
        val sensors = sensorsObject()

        assertEquals(
            mapOf("a" to unitA, "b" to unitB),
            sensors.getValue("unitAddresses").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    /**
     * Only the roles the SET armed are named, whatever the pairing store holds.
     *
     * A set that met two paired units it could not tell apart records one
     * unroled stream -- `count` 1, both role lists empty -- while the store may
     * still hold a label for each unit. Naming either here would attach an
     * address to a capture that carries no role, which is the one thing the
     * role column exists to refuse.
     */
    @Test
    fun `a set that armed no role names no unit`() = runTest {
        val sensors =
            sensorsObject(
                RecordedSensors(count = 1, shortfall = DualShortfall.ROLES_UNASSIGNED),
            )

        assertNull(
            sensors["unitAddresses"],
            "a set whose stream carries no role was given one anyway",
        )
    }

    /**
     * An exporter that knows no pairing publishes no key at all, rather than an
     * empty object.
     *
     * The state of every session exported by a build that could not read the
     * pairing store, and of a lifter who has labelled nothing. Absence is the
     * honest answer and this document expresses every other unknown by
     * omission; an empty object would read as "the app looked and there were no
     * units".
     *
     * The set is exact, so it moved with #321: both roles here stream 100 rows,
     * which is enough for `deliveredRate_hz` and `burstSpacing_ms` to be
     * published beside the declaration. Neither says anything about which unit
     * carried a role.
     */
    @Test
    fun `no pairing known publishes no unit key`() = runTest {
        val sensors = sensorsObject(roleByAddress = emptyMap())

        assertEquals(
            setOf("count", "expected", "present", "analysedRole", "deliveredRate_hz", "burstSpacing_ms"),
            sensors.keys,
            "an empty pairing store still wrote something",
        )
    }

    /**
     * A role two addresses claim is omitted and its partner survives.
     *
     * Reachable and permanent: forgetting the unit labelled A does not clear
     * its label -- `DeviceRegistry.forget` and `SettingsStore.setSensorRole`
     * are different documents -- so pairing a replacement and labelling it A
     * leaves two A addresses in the store for good. The rule is
     * `SensorCapturePolicy.unitAddresses`', pinned in `:core:model`; what is
     * pinned here is that the exporter reads it rather than deciding again.
     */
    @Test
    fun `a role two units claim is not named and the other role still is`() = runTest {
        val sensors = sensorsObject(roleByAddress = bothLabelled + mapOf("C0:82:2D:8A:FF:01" to SensorRole.A))

        assertEquals(
            mapOf("b" to unitB),
            sensors.getValue("unitAddresses").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    /**
     * ONE READING OF THE PAIRING PER DOCUMENT, whatever the store answers next.
     *
     * Round 1 of #260's review found this to be the round's one mechanical gap:
     * `SessionExporter.buildExport` reads `sensorRoleByAddress` once and threads
     * the map down, and nothing failed if that read moved into `setExport`. The
     * supplier below answers a DIFFERENT labelling on its second call -- the two
     * units swapped, which is what a re-label during an export looks like from
     * inside the exporter -- so a per-set read publishes two sets of one session
     * under two identities and this fails. The lifter's exposure is a mount
     * table in which set 1 says role a is the unit ending 1D:3F and set 2 says
     * it is 0C:7A, with no key saying either changed.
     *
     * The counter is asserted as well as the addresses, because the addresses
     * alone would pass a second read that happened to return the same map.
     */
    @Test
    fun `the pairing is read once for the whole document`() = runTest {
        var reads = 0
        val swapped = mapOf(unitA to SensorRole.B, unitB to SensorRole.A)
        val dao =
            FakeSessionDao(
                listOf(row(dual, id = 5L, orderIdx = 0), row(dual, id = 6L, orderIdx = 1)),
                mapOf(
                    5L to listOf(imuStream(1L, "a"), imuStream(2L, "b")),
                    6L to listOf(imuStream(3L, "a", setId = 6L), imuStream(4L, "b", setId = 6L)),
                ),
            )
        val exporter =
            SessionExporter(
                SessionRepository(dao, FakeExerciseDao()),
                dispatcher = Dispatchers.Default,
                sensorRoleByAddress = {
                    reads += 1
                    if (reads == 1) bothLabelled else swapped
                },
            )

        val sets =
            Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!)
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray
        val addresses =
            sets.map { set ->
                set.jsonObject.getValue("sensors").jsonObject
                    .getValue("unitAddresses").jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
            }

        assertEquals(2, sets.size, "the fake did not deliver two sets")
        assertEquals(1, reads, "the pairing store was read more than once for one document")
        assertEquals(
            listOf(mapOf("a" to unitA, "b" to unitB), mapOf("a" to unitA, "b" to unitB)),
            addresses,
            "two sets of one session were labelled by two readings of the pairing",
        )
    }
}
