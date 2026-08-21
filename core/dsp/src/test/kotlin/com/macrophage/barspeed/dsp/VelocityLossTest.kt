package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `velocityLoss_pct` is today, on rep lists small enough to check by hand.
 *
 * These are characterization pins, not a statement of what the figure ought to
 * be: they record the behaviour a change is about to alter so that the
 * alteration shows up in the diff of this file, not only in the diff of the
 * code. Two of them are marked `(pre-fix)` in the repository's usual sense --
 * they are expected to be inverted, not preserved.
 *
 * Only `meanConVelMps` matters to this figure. Every other field on
 * [RepAnalysis] is filled with a fixed, obviously-synthetic value so that a
 * reader is never tempted to read one of these lists as a real set.
 */
class VelocityLossTest {
    private fun reps(vararg meanConVelMps: Double) = meanConVelMps.mapIndexed { i, v ->
        RepAnalysis(
            index = i,
            eccS = 2.0,
            bottomPauseS = 0.0,
            conS = 1.0,
            topPauseS = 0.0,
            meanConVelMps = v,
            peakConVelMps = v,
            meanEccVelMps = null,
            peakEccVelMps = null,
            romM = 0.5,
            peakPowerW = null,
        )
    }

    @Test
    fun `velocity loss is best rep to last rep`() {
        // 0.50 is the best and 0.30 is the last: (0.50 - 0.30) / 0.50 = 40%.
        // The middle rep is faster than the last and slower than the best, so a
        // best-to-WORST reading would give the same answer here -- which is why
        // the case below, where the two definitions differ, is also pinned.
        assertEquals(40.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.40, 0.30)))
    }

    @Test
    fun `a slow rep in the middle of the set is not the set's velocity loss`() {
        // best 0.50, worst 0.10, last 0.45. Best-to-last is 10%; best-to-worst
        // would be 80%. This is the pin that says which definition is in force.
        assertEquals(10.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.10, 0.45)))
    }

    @Test
    fun `velocity loss is zero when the last rep is the set's fastest (pre-fix)`() {
        // `best` is a maximum taken over a list that CONTAINS `last`, so
        // best - last can never be negative and the quotient can only reach
        // exactly 0.0 when the last rep ties the maximum. 0.0 is therefore a
        // fact about the ORDER of this list, not a measurement of fatigue.
        assertEquals(0.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.40, 0.50)))
    }

    @Test
    fun `a two-rep set whose reps tie reports zero loss (pre-fix)`() {
        // The tie case in its smallest form. Kept separate from the case above
        // because an implementation that asks "is the maximum AT the last
        // index" using maxByOrNull answers no here -- maxByOrNull returns the
        // FIRST maximum -- and that is one character away from the natural way
        // to write the rule that replaces this behaviour.
        assertEquals(0.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.50)))
    }

    @Test
    fun `a single rep has nothing to compare against`() {
        assertNull(SetAnalyzer.velocityLossPct(reps(0.50)))
        assertNull(SetAnalyzer.velocityLossPct(reps()))
    }

    @Test
    fun `a set with no positive drive velocity has no reference to divide by`() {
        assertNull(SetAnalyzer.velocityLossPct(reps(0.0, 0.0)))
        assertNull(SetAnalyzer.velocityLossPct(reps(-0.10, -0.20)))
    }
}
