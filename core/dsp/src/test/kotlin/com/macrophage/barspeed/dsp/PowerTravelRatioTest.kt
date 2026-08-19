package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What the published power figures do when a pulley ratio is declared.
 *
 * `peakPower_w` and `meanConPower_w` reach the export per rep and per set. The
 * only assertion anywhere in this repository that touched either of them before
 * this file was `assertNotNull(rep.peakPowerW)`. A published figure with one
 * null-check is not covered, so these pins record what it computes today.
 *
 * Issue 26. These values are characterization, not targets.
 */
class PowerTravelRatioTest {
    private val fourReps = SyntheticSets.generate(
        List(4) { SyntheticSets.RepSpec(eccS = 3.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.5, romM = 0.6) },
    )

    private fun direction(ratio: Double) = LiftDirection(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = ratio,
    )

    private fun analyse(ratio: Double) = SetAnalyzer.analyze(fourReps, direction(ratio), loadKg = 50.0)

    private fun meanPeakW(ratio: Double) = analyse(ratio).reps.mapNotNull { it.peakPowerW }.average()

    private fun meanConW(ratio: Double) = analyse(ratio).reps.mapNotNull { it.meanConPowerW }.average()

    @Test
    fun `power is published at all, for every rep`() {
        val a = analyse(1.0)
        assertEquals(4, a.reps.size, "reps segmented")
        a.reps.forEach {
            assertNotNull(it.peakPowerW, "peak power")
            assertNotNull(it.meanConPowerW, "mean concentric power")
        }
    }

    @Test
    fun `power on a direct-drive lift, where sensor and load share a frame`() {
        // travelRatio 1.0: the series and loadKg describe the same body, so this
        // is the figure every other case should agree with.
        assertEquals(481.8, meanPeakW(1.0), 0.05, "mean of per-rep peak power, W")
        assertEquals(300.3, meanConW(1.0), 0.05, "mean of per-rep mean concentric power, W")
    }

    @Test
    fun `a declared pulley ratio scales the published power (pre-fix)`() {
        // The defect. mappedToLifter multiplies BOTH velocity and acceleration
        // by travelRatio, while loadKg is left in the sensor's frame, so
        // P = r*(m*g*v) + r^2*(m*a*v). The gravity term scales once and the
        // inertial term twice, which is why peak moves further than mean.
        assertEquals(1055.8, meanPeakW(2.0), 0.05, "2:1 pulley, peak, W")
        assertEquals(596.0, meanConW(2.0), 0.05, "2:1 pulley, mean concentric, W")
        assertEquals(233.6, meanPeakW(0.5), 0.05, "1:2 pulley, peak, W")
        assertEquals(152.9, meanConW(0.5), 0.05, "1:2 pulley, mean concentric, W")
    }

    @Test
    fun `range of motion and velocity are correctly in the lifter frame`() {
        // Not part of the defect, and pinned so the fix is not mistaken for one
        // that should touch them. The handle really does travel `ratio` times
        // as far as the stack, so these SHOULD scale. Only power mixes frames,
        // because only power brings a mass into the expression.
        //
        // They scale to about a per cent rather than exactly, and that is not
        // the defect either: the run gates are applied to the SCALED series, so
        // the segmented spans shift slightly, and the published figures are
        // rounded to two decimals before they get here.
        val one = analyse(1.0).reps
        val two = analyse(2.0).reps
        assertEquals(0.5985, one.map { it.romM }.average(), 5e-4, "ROM at 1:1, m")
        assertEquals(1.1980, two.map { it.romM }.average(), 5e-4, "ROM at 2:1, m")
        assertEquals(0.6115, one.map { it.meanConVelMps }.average(), 5e-4, "mean con velocity at 1:1")
        assertEquals(1.2108, two.map { it.meanConVelMps }.average(), 5e-4, "mean con velocity at 2:1")

        val romRatio = two.map { it.romM }.average() / one.map { it.romM }.average()
        val velRatio = two.map { it.meanConVelMps }.average() / one.map { it.meanConVelMps }.average()
        assertEquals(2.0, romRatio, 0.03, "ROM scales with the ratio, to within about a per cent")
        assertEquals(2.0, velRatio, 0.03, "velocity scales with the ratio: 1.980, about a per cent short")
    }
}
