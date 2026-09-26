package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.TempoComplianceResult
import com.macrophage.barspeed.model.Tempo
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
 * The session document's `tempoCompliance` says how many reps
 * `actualEccConRatio` was taken over (#88, export 1.23).
 *
 * The count is taken where the ratio is -- `SetAnalyzer.complianceFor`, in the
 * same pass and over the same reps -- frozen into the stored analysis with it,
 * and copied out here. `EccConRatioCoverageTest` in `:core:dsp` pins the count;
 * this file pins the WIRING, from a stored analysis to the published block,
 * and the absence on a set analysed before the count existed.
 *
 * RED WHEN WRITTEN: the block publishes the ratio and no count. The second
 * test is a guard, green before and after.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportEccConCoverageTest {
    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
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

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

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

    private val json = Json { ignoreUnknownKeys = true }

    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.4,
        peakConVelMps = 0.6,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    /** Eight reps, the even four of which resolved an eccentric. */
    private val reps = (1..8).map { rep(it, if (it % 2 == 0) 3.0 else null) }

    private fun row(compliance: TempoComplianceResult) = SetRecordEntity(
        id = 6L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "overhead_press",
        exerciseName = "Overhead Press",
        loadKg = 40.0,
        actualReps = 8,
        repsManual = true,
        plannedReps = 8,
        tempo = "3010",
        workBegan = true,
        startedAtMs = 1_788_342_174_823L,
        endedAtMs = 1_788_342_220_675L,
        analysisJson =
        json.encodeToString(
            SetAnalysis.serializer(),
            SetAnalysis(
                reps = reps,
                sampleRateHz = 99.4,
                velocityLossPct = null,
                tempoCompliance = compliance,
                verdicts = emptyList(),
            ),
        ),
    )

    /** The tempo block of the one set of the session document. */
    private suspend fun tempoBlock(row: SetRecordEntity): JsonObject {
        val session = SessionEntity(id = 1L, startedAtMs = 1_788_342_000_000L, endedAtMs = 1_788_343_100_000L)
        val repository = SessionRepository(FakeSessionDao(session = session, rows = listOf(row)), FakeExerciseDao())
        val exporter = SessionExporter(repository, dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = false)!!
        return Json.parseToJsonElement(text)
            .jsonObject.getValue("exercises").jsonArray.single()
            .jsonObject.getValue("sets").jsonArray.single()
            .jsonObject.getValue("tempoCompliance").jsonObject
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.content?.toInt()

    /** The analyzer's own result, stored and exported: the ratio covers four of eight. */
    @Test
    fun `the tempo block says the ratio was taken over four of the eight reps`() = runTest {
        val block = tempoBlock(row(SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, reps)))
        assertEquals("3.0", block["actualEccConRatio"]?.jsonPrimitive?.content, "the fixture's ratio moved")
        assertEquals(8, block.int("of"), "the fixture's `of` moved")
        assertEquals(4, block.int("actualEccConRatioReps"), "the ratio is published with no count of its reps")
    }

    /**
     * GUARD. A set analysed before the count existed carries a ratio and no
     * count in its stored analysis, and publishes exactly that: the ratio it
     * always published, and no key -- never a count derived at export under a
     * rule the stored ratio may not have been taken by.
     */
    @Test
    fun `a set analysed before the count publishes its ratio and no count`() = runTest {
        val before =
            TempoComplianceResult(
                prescribed = Tempo.parse("3010"),
                toleranceS = 0.5,
                phases = emptyList(),
                repsFullyCompliant = 3,
                repsEvaluated = 8,
                prescribedEccConRatio = 3.0,
                actualEccConRatio = 2.8,
            )
        val block = tempoBlock(row(before))
        assertEquals("2.8", block["actualEccConRatio"]?.jsonPrimitive?.content, "the stored ratio moved")
        assertNull(block["actualEccConRatioReps"], "a count was invented for a set that never stored one")
    }
}
