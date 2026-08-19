package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SetAnalyzer.romSpreadPct] on its own, away from any capture.
 *
 * Green pins on a new symbol. The corpus values it produces are in
 * [RomDispersionTest]; these are the arithmetic and the absence cases, which
 * fixtures cannot state cleanly because no capture has exactly one rep of a
 * chosen size.
 */
class RomSpreadTest {
    private fun reps(vararg roms: Double) = roms.mapIndexed { i, rom ->
        RepAnalysis(
            index = i,
            eccS = 1.0,
            bottomPauseS = 0.0,
            conS = 1.0,
            topPauseS = 0.0,
            meanConVelMps = 0.3,
            peakConVelMps = 0.5,
            meanEccVelMps = -0.2,
            peakEccVelMps = -0.3,
            romM = rom,
            peakPowerW = null,
        )
    }

    @Test
    fun `a set whose reps agree reports no spread`() {
        assertEquals(0.0, SetAnalyzer.romSpreadPct(reps(0.4, 0.4, 0.4, 0.4)))
    }

    @Test
    fun `spread is the population deviation as a percentage of the mean`() {
        // 0.3 and 0.5: mean 0.4, population deviation 0.1, so 25.0 per cent.
        // Population and not sample, because the reps of a set ARE the
        // population; a sample deviation would report 35.4 here and would be
        // estimating a wider set of reps that does not exist.
        assertEquals(25.0, SetAnalyzer.romSpreadPct(reps(0.3, 0.5)))
    }

    @Test
    fun `a set with fewer than two reps reports absence, not zero`() {
        // The figure is undefined here and must READ as undefined. 0.0 would
        // say the reps agreed perfectly and 100.0 would say they disagreed
        // completely; both are claims about evidence that does not exist.
        assertNull(SetAnalyzer.romSpreadPct(reps(0.4)), "one rep")
        assertNull(SetAnalyzer.romSpreadPct(emptyList()), "no reps")
    }

    @Test
    fun `a set whose reps average nothing reports absence rather than dividing`() {
        // Guards the divide. A set of zero-ROM reps has no mean to be a
        // percentage of, and NaN reaching an export is worse than a missing
        // key: it serialises, and a reader that compares it to a threshold
        // gets false for every comparison.
        assertNull(SetAnalyzer.romSpreadPct(reps(0.0, 0.0)))
    }

    @Test
    fun `spread is rounded to one decimal, like the other published percentages`() {
        // velocityLoss_pct is rounded the same way. A figure published beside
        // it at full double precision would invite a reader to believe the
        // extra digits mean something.
        assertEquals(27.8, SetAnalyzer.romSpreadPct(reps(0.31, 0.47, 0.63)))
    }
}
