package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoScoreTone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tempo chip over a real compliance result, through [tempoScore] -- the
 * function both screens call -- and through the STORED form, because the
 * history screen reads the result back out of the set's `analysisJson` and a
 * count that did not survive storage would reach no history card (#230).
 *
 * The reps are built by hand so the population is exact: which reps resolved
 * an eccentric is the whole subject.
 */
class TempoCoverageWiringTest {
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

    private fun stored(c: TempoComplianceResult): TempoComplianceResult = Json.decodeFromString(
        TempoComplianceResult.serializer(),
        Json.encodeToString(TempoComplianceResult.serializer(), c),
    )

    /** GUARD, green before and after: every rep resolved both phases, on tempo. */
    @Test
    fun `a set measured on every rep ticks, read back from storage`() {
        val c = stored(SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, (0..5).map { rep(it, 3.0) }))
        assertEquals(6, c.repsEvaluated, "the fixture's `of` moved")
        val score = assertNotNull(c.tempoScore())
        assertEquals("Tempo 6/6 ✓", score.text)
        assertEquals(TempoScoreTone.ON_TEMPO, score.tone)
        assertNull(score.ungradedNote)
    }

    /**
     * The per-phase count the chip reads is the analyzer's own, and it is on
     * the stored result: eight reps graded, four of them on the eccentric.
     */
    @Test
    fun `the stored result carries how many reps resolved each phase`() {
        val reps = (0..7).map { rep(it, if (it % 2 == 0) 3.0 else null) }
        val c = stored(SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, reps))
        assertEquals(8, c.repsEvaluated)
        assertEquals(4, c.phases.single { it.phase == TempoComplianceResult.PHASE_ECCENTRIC }.repsEvaluated)
        assertEquals(8, c.phases.single { it.phase == "concentric" }.repsEvaluated)
    }

    /**
     * RED WHEN WRITTEN. The same eight reps, every one in tolerance on what it
     * resolved: the chip reached through the screens' own function withholds
     * the tick and says how many reps the eccentric was measured on. This is
     * the pin on the hand-off -- a mapping that passed the set's count as the
     * phase's would leave the label's own tests green and this one red.
     */
    @Test
    fun `a partly measured set does not tick through the screens' function`() {
        val reps = (0..7).map { rep(it, if (it % 2 == 0) 3.0 else null) }
        val c = stored(SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, reps))
        assertEquals(8, c.repsFullyCompliant, "the fixture's compliance moved")
        val score = assertNotNull(c.tempoScore())
        assertEquals("Tempo 8/8", score.text)
        assertEquals(TempoScoreTone.PARTIAL, score.tone)
        assertEquals("Eccentric: 4 of 8 reps measured · 4 not measured.", score.ungradedNote)
    }
}
