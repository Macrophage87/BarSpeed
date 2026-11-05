package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.PhaseComplianceResult
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.TempoComplianceResult
import com.macrophage.barspeed.model.Tempo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `tempoCompliance.byPhase` takes the set's grade apart by phase (#66 (c),
 * export 1.26).
 *
 * Field-47 set 14 (v0.1.57) published `withinTolerance` 7 of 14 with the
 * eccentric in `scoredPhases`; none of the seven carried an `ecc_s`, and the
 * two eccentrics that were measured were both out of tolerance. The ratio
 * folds the phases together over whatever each rep resolved, so it read as
 * half the lowerings on count. The stored analysis has always carried each
 * phase's own two counts; this is the export publishing them.
 *
 * DIFFERENTIALS. "field-47's set" and "a pause" FAIL at the commit that
 * writes them, because `Exporters` does not populate `byPhase`. "a set that
 * scored nothing" passes on both sides and is what stops a fix publishing an
 * empty list where `scoredPhases` already says nothing was graded.
 *
 * "a v0_1_35 row" and "a pre-v0_1_31 row" (L1 review round 1, A-F1/B-B1)
 * feed the exporter a stored analysis in the shape an older build wrote,
 * and FAIL while `byPhase` is built from the stored `scored` flag alone: the
 * first publishes an eccentric "0 of 0", the second lists both pauses. Each
 * shape is the current serializer's output with the `phases` entries
 * rewritten, not a row read from a real database.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class SessionExportTempoByPhaseTest {
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

    private fun rep(index: Int, eccS: Double?, conS: Double) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = conS,
        topPauseS = null,
        meanConVelMps = 0.4,
        peakConVelMps = 0.6,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    private fun phase(name: String, prescribedS: Double?, within: Int, of: Int, scored: Boolean) =
        PhaseComplianceResult(
            phase = name,
            prescribedS = prescribedS,
            actualMeanS = null,
            worstDeviationS = 0.0,
            repsWithinTolerance = within,
            repsEvaluated = of,
            scored = scored,
        )

    private fun analysisJson(reps: List<RepAnalysis>, compliance: TempoComplianceResult) = json.encodeToString(
        SetAnalysis.serializer(),
        SetAnalysis(
            reps = reps,
            sampleRateHz = 99.4,
            velocityLossPct = null,
            tempoCompliance = compliance,
            verdicts = emptyList(),
        ),
    )

    /**
     * The stored analysis of [reps]/[compliance] with each `tempoCompliance.phases`
     * entry rewritten by [phaseShape] -- how a row written by an older build
     * reads, decoded by the current serializer exactly as the exporter decodes it.
     */
    private fun storedAs(
        reps: List<RepAnalysis>,
        compliance: TempoComplianceResult,
        phaseShape: (JsonObject) -> JsonObject,
    ): SetRecordEntity {
        val tree = Json.parseToJsonElement(analysisJson(reps, compliance)).jsonObject
        val tc = tree.getValue("tempoCompliance").jsonObject
        val phases = JsonArray(tc.getValue("phases").jsonArray.map { phaseShape(it.jsonObject) })
        val stored = JsonObject(tree + ("tempoCompliance" to JsonObject(tc + ("phases" to phases))))
        return row(reps, compliance).copy(analysisJson = stored.toString())
    }

    private fun row(reps: List<RepAnalysis>, compliance: TempoComplianceResult) = SetRecordEntity(
        id = 14L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "overhead_press",
        exerciseName = "Overhead Press",
        loadKg = 40.0,
        actualReps = reps.size,
        tempo = compliance.prescribed.notation(),
        workBegan = true,
        startedAtMs = 1_791_000_000_000L,
        endedAtMs = 1_791_000_060_000L,
        analysisJson = analysisJson(reps, compliance),
    )

    private suspend fun tempoCompliance(row: SetRecordEntity): JsonObject {
        val session = SessionEntity(id = 1L, startedAtMs = 1_790_999_000_000L, endedAtMs = 1_791_000_100_000L)
        val repository = SessionRepository(FakeSessionDao(session = session, rows = listOf(row)), FakeExerciseDao())
        val exporter = SessionExporter(repository, dispatcher = Dispatchers.Default)
        val text = exporter.exportJson(1L, includeRepDetail = true)!!
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray.single().jsonObject
            .getValue("sets").jsonArray.single().jsonObject.getValue("tempoCompliance").jsonObject
    }

    private fun JsonObject.tallies(): List<Triple<String, Int, Int>> = getValue("byPhase").jsonArray.map {
        val e = it.jsonObject
        Triple(
            e.getValue("phase").jsonPrimitive.content,
            e.getValue("withinTolerance").jsonPrimitive.int,
            e.getValue("of").jsonPrimitive.int,
        )
    }

    @Test
    fun `field-47's set publishes its eccentric 0 of 2 beside its drive 7 of 14`() = runTest {
        // Fourteen reps; the 3rd and 9th resolved an eccentric, both long.
        val reps = (1..14).map { rep(it, eccS = if (it == 3 || it == 9) 4.2 else null, conS = 1.0) }
        val compliance = TempoComplianceResult(
            prescribed = Tempo.parse("3010"),
            toleranceS = 0.5,
            phases = listOf(
                phase("eccentric", 3.0, within = 0, of = 2, scored = true),
                phase("bottomPause", 0.0, within = 0, of = 0, scored = false),
                phase("concentric", 1.0, within = 7, of = 14, scored = true),
                phase("topPause", 0.0, within = 0, of = 0, scored = false),
            ),
            repsFullyCompliant = 7,
            repsEvaluated = 14,
        )
        val tc = tempoCompliance(row(reps, compliance))
        assertEquals(7, tc.getValue("withinTolerance").jsonPrimitive.int, "the set-level ratio moved")
        assertEquals(14, tc.getValue("of").jsonPrimitive.int, "the set-level ratio moved")
        assertEquals(listOf(Triple("eccentric", 0, 2), Triple("concentric", 7, 14)), tc.tallies())
    }

    @Test
    fun `a pause is never listed, whatever it was measured at`() = runTest {
        val reps = (1..4).map { rep(it, eccS = 3.0, conS = 1.0) }
        val compliance = TempoComplianceResult(
            prescribed = Tempo.parse("3110"),
            toleranceS = 0.5,
            phases = listOf(
                phase("eccentric", 3.0, within = 4, of = 4, scored = true),
                phase("bottomPause", 1.0, within = 0, of = 4, scored = false),
                phase("concentric", 1.0, within = 3, of = 4, scored = true),
                phase("topPause", 0.0, within = 0, of = 0, scored = false),
            ),
            repsFullyCompliant = 3,
            repsEvaluated = 4,
        )
        assertEquals(
            listOf(Triple("eccentric", 4, 4), Triple("concentric", 3, 4)),
            tempoCompliance(row(reps, compliance)).tallies(),
        )
    }

    @Test
    fun `a set that scored nothing publishes no byPhase`() = runTest {
        val reps = (1..3).map { rep(it, eccS = null, conS = 1.0) }
        val compliance = TempoComplianceResult(
            prescribed = Tempo.parse("30X0"),
            toleranceS = 0.5,
            phases = listOf(
                phase("eccentric", 3.0, within = 0, of = 0, scored = false),
                phase("concentric", null, within = 0, of = 0, scored = false),
            ),
            repsFullyCompliant = 0,
            repsEvaluated = 0,
        )
        val tc = tempoCompliance(row(reps, compliance))
        assertEquals(0, tc.getValue("scoredPhases").jsonArray.size, "the fixture scored a phase")
        assertFalse("byPhase" in tc, "a set that scored nothing published a byPhase: $tc")
    }

    private fun assertPublishable(tallies: List<Triple<String, Int, Int>>) {
        assertTrue(tallies.none { it.first !in setOf("eccentric", "concentric") }, "a pause was listed: $tallies")
        assertTrue(tallies.none { it.third == 0 }, "an entry was published with of 0: $tallies")
    }

    @Test
    fun `a v0_1_35 row's unresolved eccentric publishes no of-0 entry`() = runTest {
        // v0.1.31 to v0.1.35 wrote scored = scorable, with no check that any
        // rep resolved the phase: a prescribed eccentric nothing measured is
        // stored scored, 0 of 0.
        val reps = (1..6).map { rep(it, eccS = null, conS = 1.0) }
        val compliance = TempoComplianceResult(
            prescribed = Tempo.parse("3010"),
            toleranceS = 0.5,
            phases = listOf(
                phase("eccentric", 3.0, within = 0, of = 0, scored = true),
                phase("bottomPause", 0.0, within = 0, of = 0, scored = false),
                phase("concentric", 1.0, within = 5, of = 6, scored = true),
                phase("topPause", 0.0, within = 0, of = 0, scored = false),
            ),
            repsFullyCompliant = 5,
            repsEvaluated = 6,
        )
        val stored = storedAs(reps, compliance) { p ->
            val movement = p.getValue("phase").jsonPrimitive.content in setOf("eccentric", "concentric")
            JsonObject(p + ("scored" to JsonPrimitive(movement)))
        }
        val tallies = tempoCompliance(stored).tallies()
        assertPublishable(tallies)
        assertEquals(listOf(Triple("concentric", 5, 6)), tallies)
    }

    @Test
    fun `a pre-v0_1_31 row's pauses are not listed`() = runTest {
        // Before v0.1.31 there was no scored key: every phase of the stored
        // grade decodes as scored, and that build graded the prescribed
        // pauses over every rep.
        val reps = (1..4).map { rep(it, eccS = 3.0, conS = 1.0) }
        val compliance = TempoComplianceResult(
            prescribed = Tempo.parse("3010"),
            toleranceS = 0.5,
            phases = listOf(
                phase("eccentric", 3.0, within = 2, of = 4, scored = false),
                phase("bottomPause", 0.0, within = 1, of = 4, scored = false),
                phase("concentric", 1.0, within = 3, of = 4, scored = false),
                phase("topPause", 0.0, within = 4, of = 4, scored = false),
            ),
            repsFullyCompliant = 1,
            repsEvaluated = 4,
        )
        val stored = storedAs(reps, compliance) { p -> JsonObject(p - "scored") }
        assertFalse("\"scored\"" in stored.analysisJson, "the fixture still carries a scored key")
        val tallies = tempoCompliance(stored).tallies()
        assertPublishable(tallies)
        assertEquals(listOf(Triple("eccentric", 2, 4), Triple("concentric", 3, 4)), tallies)
    }
}
