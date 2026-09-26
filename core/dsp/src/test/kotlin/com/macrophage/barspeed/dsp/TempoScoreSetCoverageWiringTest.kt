package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoScoreTone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tempo chip over a whole set, through [tempoScore] on [SetAnalysis] --
 * the function both screens call -- and through the STORED form, because the
 * history screen reads the analysis back out of the set's `analysisJson`
 * (#329).
 *
 * Target 30X0 on a drive-up lift: a 3.00 s eccentric and an explosive up
 * stroke, so the eccentric is the only scored phase and a rep whose
 * eccentric nothing measured resolved no scored phase at all. Tolerance
 * 0.5 s. Reps are built by hand so the population is exact.
 *
 * RED WHEN WRITTEN, except the tests whose KDoc opens GUARD.
 */
class TempoScoreSetCoverageWiringTest {
    private val explosiveUp = Tempo.parse("30X0")

    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 0.6,
        topPauseS = null,
        meanConVelMps = 0.8,
        peakConVelMps = 1.1,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    private fun stored(reps: List<RepAnalysis>): SetAnalysis {
        val analysis =
            SetAnalysis(reps, 50.0, null, SetAnalyzer.complianceFor(explosiveUp, 0.5, reps), emptyList())
        return Json.decodeFromString(SetAnalysis.serializer(), Json.encodeToString(SetAnalysis.serializer(), analysis))
    }

    /** GUARD, green before and after: a set with no prescribed tempo draws no chip. */
    @Test
    fun `a set with no tempo compliance draws no chip`() {
        val reps = (0..3).map { rep(it, 3.0) }
        assertNull(SetAnalysis(reps, 50.0, null, null, emptyList()).tempoScore())
    }

    /** GUARD, green before and after: every rep resolved the only scored phase, on tempo. */
    @Test
    fun `an explosive-drive set measured on every rep ticks, read back from storage`() {
        val a = stored((0..7).map { rep(it, 3.0) })
        val c = assertNotNull(a.tempoCompliance)
        assertEquals(listOf(TempoComplianceResult.PHASE_ECCENTRIC), c.phases.filter { it.scored }.map { it.phase })
        assertEquals(8, c.repsEvaluated, "the fixture's graded count moved")
        val score = assertNotNull(a.tempoScore())
        assertEquals("Tempo 8/8 ✓", score.text)
        assertEquals(TempoScoreTone.ON_TEMPO, score.tone)
        assertNull(score.ungradedNote)
    }

    /**
     * Eight reps, every other one with a measured eccentric, all four in
     * tolerance. The chip reached through the screens' own function withholds
     * the tick and says how many reps went unmeasured; a hand-off that passed
     * the graded count as the set's would leave the label's tests green and
     * this one red.
     */
    @Test
    fun `reps that resolved no scored phase are counted through the screens' function`() {
        val a = stored((0..7).map { rep(it, if (it % 2 == 0) 3.0 else null) })
        val c = assertNotNull(a.tempoCompliance)
        assertEquals(4, c.repsEvaluated, "the fixture's graded count moved")
        assertEquals(4, c.repsFullyCompliant, "the fixture's compliance moved")
        val score = assertNotNull(a.tempoScore())
        assertEquals("Tempo 4/4", score.text)
        assertEquals(TempoScoreTone.PARTIAL, score.tone)
        assertEquals("Eccentric: 4 of 8 reps measured · 4 not measured.", score.ungradedNote)
    }

    /**
     * One rule, one count: the chip's note and the eccentric caption the rest
     * screen draws on the same card state the same gap for the same set.
     */
    @Test
    fun `the chip's note and the eccentric caption count the same gap`() {
        val reps = (0..7).map { rep(it, if (it % 2 == 0) 3.0 else null) }
        assertEquals(
            "All 4 measured reps on tempo · 4 not measured.",
            CoachingRules.eccentricTempoInsight(reps, 3.0, 0.5),
        )
        assertEquals(
            "Eccentric: 4 of 8 reps measured · 4 not measured.",
            assertNotNull(stored(reps).tempoScore()).ungradedNote,
        )
    }

    /**
     * GUARD, green when written: added after the fix, in review round 1.
     *
     * The same gap, one number, on a set with a MISS. Eight reps, eccentrics
     * measured on reps 0, 2, 4 and 6 -- one of them 4.0 s against the 3.00 s
     * target, a full second outside the 0.5 s tolerance. The chip is off tempo
     * and still says four reps went unmeasured, and the tempo verdict line
     * (#328) the same card draws counts those same four. The caption test
     * above cannot reach this: on a miss the caption names the worst rep and
     * states no coverage at all.
     */
    @Test
    fun `the chip's note and the tempo verdict line count the same gap on a missed set`() {
        val reps =
            (0..7).map {
                rep(
                    it,
                    when {
                        it == 2 -> 4.0
                        it % 2 == 0 -> 3.0
                        else -> null
                    },
                )
            }
        val a = stored(reps)
        val c = assertNotNull(a.tempoCompliance)
        assertEquals(4, c.repsEvaluated, "the fixture's graded count moved")
        assertEquals(3, c.repsFullyCompliant, "the fixture's compliance moved")
        val score = assertNotNull(a.tempoScore())
        assertEquals("Tempo 3/4", score.text)
        assertEquals(TempoScoreTone.OFF_TEMPO, score.tone)
        assertEquals("Eccentric: 4 of 8 reps measured · 4 not measured.", score.ungradedNote)
        val verdicts = CoachingRules.verdicts(a.reps, a.velocityLossPct, c, SetTargets())
        assertTrue(
            verdicts.any { it.startsWith("Tempo (eccentric): 3 of 4 measured reps on tempo · 4 not measured;") },
            "verdicts: $verdicts",
        )
    }
}
