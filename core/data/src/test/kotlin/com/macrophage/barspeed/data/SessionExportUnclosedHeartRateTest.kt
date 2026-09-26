package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The session-level `heartRate` block of a session that was never finished,
 * issue #62.
 *
 * DIFFERENTIALS. Two of these three fail at the commit that introduces them:
 * the exporter reads the session row's two frozen columns and nothing else,
 * and those are written only by `endSession`, so a session the lifter left
 * without finishing publishes no block although every set row carries its own
 * figures. The commit after this one is what makes them pass. The third is a
 * pin on what must NOT change -- a closed session publishes what its close
 * stored -- and passes here for the right reason already.
 *
 * A separate file from [SessionExporterTest], in the shape of this module's
 * other per-issue export tests; the fakes are this file's own.
 *
 * Nothing here executes Room, SQLite or Android. What is verified is the
 * exporter's own mapping and nothing about what the database did with it.
 */
class SessionExportUnclosedHeartRateTest {
    private class FakeSessionDao(private val session: SessionEntity, private val rows: List<SetRecordEntity>) :
        SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

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

    /** A session row with no end time and no summary: what an abandoned session leaves. */
    private fun unclosed() = SessionEntity(id = 1L, startedAtMs = 1_000L)

    private fun setRow(id: Long, orderIdx: Int, hrAvgBpm: Int?, hrMaxBpm: Int?) = SetRecordEntity(
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

    private suspend fun document(session: SessionEntity, rows: List<SetRecordEntity>): JsonObject {
        val dao = FakeSessionDao(session, rows)
        val exporter = SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = Dispatchers.Default)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!).jsonObject
    }

    private fun heartRate(document: JsonObject): JsonObject =
        assertNotNull(document["heartRate"], "no session heartRate block: $document").jsonObject

    /**
     * The case #62 was filed for. DIFFERENTIAL: fails at this commit, where
     * the block is built from the row's null columns and omitted.
     *
     * The block carries exactly the two heart rates [SessionHeartRate]
     * derives from the set rows. These rows store no heart-rate stream, so
     * no HRV can be computed for them and the block carries none; an HRV
     * derived from stored streams is `SessionExportUnclosedHrvTest`'s (#62
     * half (b)).
     */
    @Test
    fun `an unclosed session with per-set heart rate publishes the summary its close would have`() = runTest {
        val document =
            document(
                unclosed(),
                listOf(
                    setRow(5L, orderIdx = 0, hrAvgBpm = 120, hrMaxBpm = 150),
                    setRow(6L, orderIdx = 1, hrAvgBpm = 140, hrMaxBpm = 165),
                ),
            )
        val hr = heartRate(document)

        assertEquals(setOf("avgBpm", "maxBpm"), hr.keys, "a block derived with no stored stream carries an HRV")
        assertEquals(130, hr.getValue("avgBpm").jsonPrimitive.int)
        assertEquals(165, hr.getValue("maxBpm").jsonPrimitive.int)
    }

    /**
     * The derived mean is truncated as the close truncates it. DIFFERENTIAL:
     * fails at this commit, where no block is published at all.
     *
     * 120, 121 and 121 average to 120.67. A reader that rounded to the
     * nearest beat, under any tie rule, would publish 121 for a session the
     * close, had it run, would have stored as 120. A mean of exactly .5 would not do: half-even
     * rounding takes 120.5 to 120 and passes.
     */
    @Test
    fun `an unclosed session's derived average is truncated as the close truncates it`() = runTest {
        val document =
            document(
                unclosed(),
                listOf(
                    setRow(5L, orderIdx = 0, hrAvgBpm = 120, hrMaxBpm = 150),
                    setRow(6L, orderIdx = 1, hrAvgBpm = 121, hrMaxBpm = 151),
                    setRow(7L, orderIdx = 2, hrAvgBpm = 121, hrMaxBpm = 152),
                ),
            )

        assertEquals(120, heartRate(document).getValue("avgBpm").jsonPrimitive.int)
    }

    /**
     * A closed session publishes the pair its close stored, not a reading of
     * the rows it holds now. NOT a differential: it passes at this commit,
     * because the exporter reads only the stored columns. It is the pin that
     * the fix derives ONLY where no close ran -- a fix that re-derived every
     * session would publish 140 and 195 here.
     */
    @Test
    fun `a closed session publishes the summary its close stored, not a re-derivation`() = runTest {
        val closed = SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 300_000L, hrAvgBpm = 130, hrMaxBpm = 165)
        val document =
            document(
                closed,
                listOf(
                    setRow(5L, orderIdx = 0, hrAvgBpm = 100, hrMaxBpm = 110),
                    setRow(6L, orderIdx = 1, hrAvgBpm = 180, hrMaxBpm = 195),
                ),
            )
        val hr = heartRate(document)

        assertEquals(130, hr.getValue("avgBpm").jsonPrimitive.int)
        assertEquals(165, hr.getValue("maxBpm").jsonPrimitive.int)
    }
}
