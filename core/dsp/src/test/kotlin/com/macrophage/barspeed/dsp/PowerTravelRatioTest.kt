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
    fun `a declared pulley ratio does not change the power published`() {
        // Power is conserved across an ideal pulley: a 2:1 halves the force at
        // the handle and doubles its travel, so the watts are the same measured
        // at either end. The published figure must therefore not move when only
        // the ratio is declared.
        //
        // Before this was fixed it moved a long way. mappedToLifter multiplies
        // BOTH velocity and acceleration by travelRatio while loadKg is left in
        // the sensor's frame, giving P = r*(m*g*v) + r^2*(m*a*v): the gravity
        // term scaled once and the inertial term twice. A 2:1 read 1055.8 W
        // peak against 481.8, and a 1:2 read 233.6.
        assertEquals(481.8, meanPeakW(2.0), 0.05, "2:1 pulley, peak, W")
        assertEquals(481.8, meanPeakW(0.5), 0.05, "1:2 pulley, peak, W")

        // Mean concentric power is now identical across ratios too. It was not
        // before issue #70: this file used to pin 297.4 at 2:1 and 306.1 at
        // 1:2 against 300.3 at 1:1, and called the spread "the segmentation
        // shift pinned below". That shift was the #70 defect -- the run limits
        // were applied unconverted to the scaled series, so a declared ratio
        // chose a different drive span and the mean was taken over different
        // samples. With the limits converted the spans are the same samples at
        // every ratio and the three figures collapse onto one.
        assertEquals(300.3, meanConW(2.0), 0.05, "2:1 pulley, mean concentric, W")
        assertEquals(300.3, meanConW(0.5), 0.05, "1:2 pulley, mean concentric, W")
        listOf(meanConW(2.0), meanConW(0.5)).forEach {
            assertEquals(meanConW(1.0), it, 1e-9, "mean power equals the 1:1 figure exactly")
        }
    }

    @Test
    fun `range of motion and velocity are correctly in the lifter frame`() {
        // Not part of the defect, and pinned so the fix is not mistaken for one
        // that should touch them. The handle really does travel `ratio` times
        // as far as the stack, so these SHOULD scale. Only power mixes frames,
        // because only power brings a mass into the expression.
        //
        // They used to scale to about a per cent rather than exactly, and this
        // file blamed rounding and "the run gates are applied to the SCALED
        // series, so the segmented spans shift slightly". The second half of
        // that was the #70 defect, not a rounding residue. With the limits
        // converted the spans are identical at every ratio and what is left is
        // only the per-rep round to three decimals, worth about a part in
        // 2000. The old figures were 1.1980 and 1.2108.
        val one = analyse(1.0).reps
        val two = analyse(2.0).reps
        assertEquals(0.5985, one.map { it.romM }.average(), 5e-4, "ROM at 1:1, m")
        assertEquals(1.19775, two.map { it.romM }.average(), 5e-4, "ROM at 2:1, m")
        assertEquals(0.6115, one.map { it.meanConVelMps }.average(), 5e-4, "mean con velocity at 1:1")
        assertEquals(1.22275, two.map { it.meanConVelMps }.average(), 5e-4, "mean con velocity at 2:1")

        val romRatio = two.map { it.romM }.average() / one.map { it.romM }.average()
        val velRatio = two.map { it.meanConVelMps }.average() / one.map { it.meanConVelMps }.average()
        assertEquals(2.0, romRatio, 2e-3, "ROM scales with the ratio: 2.00125, rounding only")
        assertEquals(2.0, velRatio, 2e-3, "velocity scales with the ratio: 1.99959, rounding only")
    }
}
