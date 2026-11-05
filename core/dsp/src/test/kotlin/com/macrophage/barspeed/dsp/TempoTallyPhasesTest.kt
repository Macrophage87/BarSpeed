package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Tempo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TempoComplianceResult.tallyPhases] -- the phases the export may publish a
 * per-phase count for (#66 (c), export 1.26; L1 review round 1, A-F1/B-B1).
 *
 * The stored analysis keeps the flags of the build that wrote it, and two
 * older builds wrote flags the 1.26 `byPhase` contract cannot carry: before
 * v0.1.31 there is no `scored` key, so a pause decodes as scored; v0.1.31 to
 * v0.1.35 mark a prescribed movement phase scored with `repsEvaluated` 0
 * when no rep resolved it. The pre-v0.1.31 case is decoded by the current
 * serializer from JSON with every `scored` key deleted; the others are
 * constructed directly in the shape each build stored.
 *
 * Nothing here executes Room, SQLite or Android.
 */
class TempoTallyPhasesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun phase(name: String, prescribedS: Double?, within: Int, of: Int, scored: Boolean) =
        PhaseComplianceResult(
            phase = name,
            prescribedS = prescribedS,
            actualMeanS = null,
            worstDeviationS = null,
            repsWithinTolerance = within,
            repsEvaluated = of,
            scored = scored,
        )

    private fun result(tempo: String, vararg phases: PhaseComplianceResult) = TempoComplianceResult(
        prescribed = Tempo.parse(tempo),
        toleranceS = 0.5,
        phases = phases.toList(),
        repsFullyCompliant = 0,
        repsEvaluated = phases.maxOf { it.repsEvaluated },
    )

    /** Encodes [r] and deletes every `scored` key, as a row written before v0.1.31 reads. */
    private fun withoutScoredKeys(r: TempoComplianceResult): TempoComplianceResult {
        val tree = json.encodeToJsonElement(TempoComplianceResult.serializer(), r).jsonObject
        val phases = JsonArray(tree.getValue("phases").jsonArray.map { JsonObject(it.jsonObject - "scored") })
        val stripped = JsonObject(tree + ("phases" to phases))
        assertTrue("\"scored\"" !in stripped.toString(), "the fixture still carries a scored key: $stripped")
        return json.decodeFromJsonElement(TempoComplianceResult.serializer(), stripped)
    }

    private fun List<PhaseComplianceResult>.names() = map { it.phase }

    @Test
    fun `a current grade lists its scored movement phases in order`() {
        val r = result(
            "3010",
            phase("eccentric", 3.0, within = 0, of = 2, scored = true),
            phase("bottomPause", 0.0, within = 0, of = 0, scored = false),
            phase("concentric", 1.0, within = 7, of = 14, scored = true),
            phase("topPause", 0.0, within = 0, of = 0, scored = false),
        )
        assertEquals(listOf("eccentric", "concentric"), r.tallyPhases().names())
        assertEquals(listOf(2, 14), r.tallyPhases().map { it.repsEvaluated }, "the counts are copied")
    }

    @Test
    fun `a phase a v0_1_35 grade marked scored with nothing resolved is not listed`() {
        val r = result(
            "3010",
            phase("eccentric", 3.0, within = 0, of = 0, scored = true),
            phase("bottomPause", 0.0, within = 0, of = 0, scored = false),
            phase("concentric", 1.0, within = 5, of = 6, scored = true),
            phase("topPause", 0.0, within = 0, of = 0, scored = false),
        )
        assertEquals(listOf("concentric"), r.tallyPhases().names())
    }

    @Test
    fun `a pause a pre-v0_1_31 grade decodes as scored is not listed`() {
        // v0.1.30 graded every prescribed phase over every rep, the pauses too.
        val r = withoutScoredKeys(
            result(
                "3010",
                phase("eccentric", 3.0, within = 2, of = 4, scored = false),
                phase("bottomPause", 0.0, within = 1, of = 4, scored = false),
                phase("concentric", 1.0, within = 3, of = 4, scored = false),
                phase("topPause", 0.0, within = 4, of = 4, scored = false),
            ),
        )
        assertTrue(r.phases.all { it.scored }, "the stripped row did not decode as scored: ${r.phases}")
        assertEquals(listOf("eccentric", "concentric"), r.tallyPhases().names())
    }

    @Test
    fun `a movement phase the grade did not score is not listed, whatever its count`() {
        // Hand-built: no shipped build writes scored false beside a nonzero
        // count. It pins that the stored flag is still asked, so the list
        // cannot widen past what the grade says it scored.
        val r = result(
            "3010",
            phase("eccentric", 3.0, within = 1, of = 3, scored = false),
            phase("concentric", 1.0, within = 3, of = 3, scored = true),
        )
        assertEquals(listOf("concentric"), r.tallyPhases().names())
    }

    @Test
    fun `a grade that scored nothing lists nothing`() {
        val r = result(
            "30X0",
            phase("eccentric", 3.0, within = 0, of = 0, scored = false),
            phase("concentric", null, within = 0, of = 0, scored = false),
        )
        assertEquals(emptyList(), r.tallyPhases())
    }
}
