package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HrSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The session `heartRate.hrvRmssd_ms` of a session that was never finished,
 * issue #62 half (b).
 *
 * DIFFERENTIAL. The first case is written to fail while the exporter reads
 * the session row's HRV column and nothing else: only the close writes that
 * column, so a session the lifter left without finishing publishes no HRV
 * although each set's stored `hrm` stream and `rest_before_hrm` window carry
 * the R-R intervals. The other two are pins on what must NOT change, written
 * to pass under that exporter for the right reason already.
 *
 * Every stream is synthetic: the field captures the rule was measured on are
 * not committed. Each stream advances one interval per notification, so the
 * per-set `hr` blocks are published and the #83 gate does not withhold the
 * session block.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it.
 */
class SessionExportUnclosedHrvTest {
    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        var streamReads = 0

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> {
            streamReads++
            return streams[setId].orEmpty()
        }

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

    private fun setRow(id: Long, orderIdx: Int, hrAvgBpm: Int, hrMaxBpm: Int) = SetRecordEntity(
        id = id,
        sessionId = 1L,
        orderIdx = orderIdx,
        exerciseId = "bench_press",
        exerciseName = "Bench Press",
        loadKg = 80.0,
        actualReps = 5,
        repsManual = true,
        plannedReps = 5,
        startedAtMs = 1_000L + orderIdx * 120_000L,
        endedAtMs = 61_000L + orderIdx * 120_000L,
        analysisJson = json.encodeToString(SetAnalysis.serializer(), noReps),
        hrAvgBpm = hrAvgBpm,
        hrMaxBpm = hrMaxBpm,
    )

    /** One notification per beat, each arriving one interval after the last. */
    private fun beats(startMs: Long, vararg rr: Double): List<HrSample> {
        var t = startMs
        return rr.map { interval ->
            t += interval.toLong()
            HrSample(timestampMs = t, bpm = 75, rrIntervalsMs = listOf(interval))
        }
    }

    private fun stream(setId: Long, kind: String, samples: List<HrSample>) =
        RawStreamEntity(setId = setId, kind = kind, csvGzip = Gzip.compress(HrCsv.encode(samples)))

    /** A set's rest window and its own stream, as the set write stores them. */
    private fun windows(setId: Long, rest: List<HrSample>, set: List<HrSample>) =
        listOf(stream(setId, RawStreamEntity.KIND_REST_BEFORE_HRM, rest), stream(setId, RawStreamEntity.KIND_HRM, set))

    // Two sets whose windows, joined in session order with the copied tail
    // counted once, are the beats 800 to 910 in steps of 10: eleven
    // differences of 10, an RMSSD of exactly 10. The second rest window opens
    // with a copy of the last three notifications of the first set, as #178
    // stores it.
    private val restOne = beats(0L, 800.0, 810.0, 820.0)
    private val setOne = beats(restOne.last().timestampMs, 830.0, 840.0, 850.0, 860.0)
    private val restTwo = setOne.subList(1, 4) + beats(setOne.last().timestampMs, 870.0, 880.0)
    private val setTwo = beats(restTwo.last().timestampMs, 890.0, 900.0, 910.0)

    private val rows =
        listOf(setRow(5L, 0, hrAvgBpm = 120, hrMaxBpm = 150), setRow(6L, 1, hrAvgBpm = 140, hrMaxBpm = 165))

    private val stored = mapOf(5L to windows(5L, restOne, setOne), 6L to windows(6L, restTwo, setTwo))

    private suspend fun document(session: SessionEntity, streams: Map<Long, List<RawStreamEntity>>): JsonObject {
        val dao = FakeSessionDao(session, rows, streams)
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!).jsonObject
    }

    private fun heartRate(document: JsonObject): JsonObject =
        assertNotNull(document["heartRate"], "no session heartRate block: $document").jsonObject

    /**
     * The case half (b) was filed for. DIFFERENTIAL: while the exporter reads
     * only the row's column, the block carries avgBpm and maxBpm and no HRV.
     */
    @Test
    fun `an unclosed session with stored hrm and rest windows publishes the HRV its streams give`() = runTest {
        val hr = heartRate(document(SessionEntity(id = 1L, startedAtMs = 1_000L), stored))

        val hrv = assertNotNull(hr["hrvRmssd_ms"], "an unclosed session with stored streams published no HRV: $hr")
        assertEquals(10.0, hrv.jsonPrimitive.double, 1e-9)
        assertEquals(130, hr.getValue("avgBpm").jsonPrimitive.content.toInt())
        assertEquals(165, hr.getValue("maxBpm").jsonPrimitive.content.toInt())
    }

    /**
     * A closed session publishes the HRV its close stored, not one computed
     * from its streams, which here would give 10. NOT a differential: an
     * exporter that reads only the stored column passes it. It is the pin
     * that the fix derives ONLY where no close ran.
     */
    @Test
    fun `a closed session publishes the HRV its close stored, not one from its streams`() = runTest {
        val closed =
            SessionEntity(
                id = 1L,
                startedAtMs = 1_000L,
                endedAtMs = 300_000L,
                hrAvgBpm = 130,
                hrMaxBpm = 165,
                hrvRmssdMs = 14.8,
            )

        assertEquals(14.8, heartRate(document(closed, stored)).getValue("hrvRmssd_ms").jsonPrimitive.double)
    }

    /**
     * Streams too short for the close's minimum of ten successive
     * differences publish no HRV key -- never 0 -- and the two heart rates
     * are still published. NOT a differential: an exporter that reads only
     * the stored column publishes no HRV for an unclosed session either.
     */
    @Test
    fun `an unclosed session whose streams hold too few beats publishes no HRV key`() = runTest {
        val short = mapOf(5L to windows(5L, restOne, setOne), 6L to emptyList())

        val hr = heartRate(document(SessionEntity(id = 1L, startedAtMs = 1_000L), short))

        assertEquals(setOf("avgBpm", "maxBpm"), hr.keys, "a figure was published from seven beats")
    }
}
