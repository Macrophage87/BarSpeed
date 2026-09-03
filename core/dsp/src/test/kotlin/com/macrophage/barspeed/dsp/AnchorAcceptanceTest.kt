package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the ZUPT accept rule does to a phase that moves at a steady speed.
 *
 * An accelerometer cannot tell rest from constant velocity: both read zero
 * linear acceleration. So [VelocityEstimator] cannot decide from the sample and
 * decides from the anchor history instead. It used to ask one question -- is
 * this window within an absolute 0.15 m/s of the previous anchor -- and
 * 0.15 m/s is a real bar speed, so any steady phase slower than it was accepted
 * as a zero and subtracted away as drift.
 *
 * The synthetic below isolates the mechanism with no fixture, no ground truth
 * and no hardware: 3 s at rest, a 0.2 s ramp, 4 s at constant velocity, a 0.2 s
 * ramp, 3 s at rest, 100 Hz, level sensor, gyro pinned so only the velocity test
 * acts. A synthetic is not a lift; nothing here is evidence about a lifter, it
 * is evidence about the accept rule.
 *
 * The rule now asks two questions instead, and the pins below record the answer
 * as a LOCATION rather than as an improvement: a steady phase is erased below
 * 0.10 m/s and preserved at and above it, and 0.10 is not a number anyone typed
 * into a comparison. It is the maximum of the two caps taken together, and the
 * pin showing 0.10 m/s accepted at EXACTLY 2.0 s of gap and refused at 1.9 s
 * and at 2.1 s is that maximum showing through: the floor is the single point
 * where the two caps cross.
 *
 * What has NOT changed is pinned too, so it cannot be claimed away: below the
 * floor a steady phase is still erased, and 0.05 m/s still reports 0.2 per cent
 * of its travel. The rule bounds how slow a movement has to be before it can be
 * taken for a pause. It does not tell rest from constant velocity, because
 * nothing reading an accelerometer can.
 */
class AnchorAcceptanceTest {
    /**
     * One descent at [vMps], level sensor, gyro at [gyroDps]. The ramps carry
     * the only non-zero acceleration, so the true travel over the descent is
     * exactly `vMps * (4.0 + 0.2)` metres.
     */
    private fun descent(vMps: Double, gyroDps: Double = 0.0, quietRestGyro: Boolean = false): List<ImuSample> {
        val hz = 100.0
        val rest = 3.0
        val ramp = 0.2
        val flat = 4.0
        val out = mutableListOf<ImuSample>()
        val n = ((rest + ramp + flat + ramp + rest) * hz).toInt()
        for (i in 0 until n) {
            val t = i / hz
            val a = when {
                t < rest -> 0.0
                t < rest + ramp -> -vMps / ramp
                t < rest + ramp + flat -> 0.0
                t < rest + ramp + flat + ramp -> vMps / ramp
                else -> 0.0
            }
            out += ImuSample(
                timestampMs = (t * 1000.0).toLong(),
                axG = 0.0,
                ayG = 0.0,
                azG = 1.0 + a / DspConfig().gravityMps2,
                wxDps = if (quietRestGyro && t < rest) 0.0 else gyroDps,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }
        return out
    }

    private fun series(vMps: Double, gyroDps: Double = 0.0, quietRestGyro: Boolean = false) =
        VelocityEstimator.estimate(descent(vMps, gyroDps, quietRestGyro), DspConfig(), MovementPlane.VERTICAL)

    /**
     * The fraction of the descent the pipeline still reports, as PATH LENGTH
     * (sum of |v| dt) across the descent window.
     *
     * Path length rather than net displacement, and the choice is stated because
     * the two disagree by a factor of three on the erased rows: what survives an
     * erasure is the two ramps, and they are signed opposite ways, so net
     * displacement reads 0.012 where path length reads 0.038 at 0.10 m/s. Either
     * puts the cliff in the same place; only path length says how much is left.
     */
    private fun recoveredFraction(vMps: Double, gyroDps: Double = 0.0, quietRestGyro: Boolean = false): Double {
        val s = series(vMps, gyroDps, quietRestGyro)
        var path = 0.0
        for (i in 1 until s.size) {
            if (s.timeS[i] in 3.0..7.4) path += abs(s.velocityMps[i]) * (s.timeS[i] - s.timeS[i - 1])
        }
        return path / (vMps * 4.2)
    }

    @Test
    fun `a steady descent is preserved at and above the floor and erased below it`() {
        // Still erased, and this is the honest limit of the change rather than
        // an oversight. 0.05 m/s reports 0.2 per cent of its travel and 0.08
        // reports 10 per cent; both are under the floor, and at 0.05 the ramps
        // are 0.25 m/s^2, inside stationaryAccBandG, so the capture is one
        // unbroken quiet run with no ramp left over to survive.
        assertEquals(0.0015, recoveredFraction(0.05), 5e-4, "0.05 m/s descent, fraction recovered")
        assertEquals(0.0975, recoveredFraction(0.08), 5e-4, "0.08 m/s descent, fraction recovered")
        // Preserved from the floor upward. The cliff has moved from between
        // 0.15 and 0.16 to between 0.08 and 0.10, and 0.10, 0.14 and 0.15 are
        // the three speeds that changed side.
        assertEquals(0.9993, recoveredFraction(0.10), 5e-4, "0.10 m/s descent, fraction recovered")
        assertEquals(0.9993, recoveredFraction(0.14), 5e-4, "0.14 m/s descent, fraction recovered")
        assertEquals(0.9993, recoveredFraction(0.15), 5e-4, "0.15 m/s descent, fraction recovered")
        assertEquals(0.9993, recoveredFraction(0.16), 5e-4, "0.16 m/s descent, fraction recovered")
        assertEquals(0.9995, recoveredFraction(0.20), 5e-4, "0.20 m/s descent, fraction recovered")
    }

    @Test
    fun `the gyro term hides the erasure wherever the implement rotates`() {
        // The same descents with the gyro at 15 deg/s for the WHOLE capture,
        // over stationaryGyroBandDps, so no sample is quiet, no anchor is
        // placed and nothing is subtracted.
        //
        // Issue #87 deliberately leaves this case alone and the test is kept
        // green rather than inverted. A capture rotating constantly has its
        // tenth percentile above the gate as well as its median, so its
        // distribution does not straddle, [VelocityEstimator.gyroGateApplies]
        // stays true, and the clause is still applied. The set beside this one
        // -- rotation during the movement, stillness before it -- is the case
        // #87 does change, and it is what pays for the anchors.
        listOf(0.05, 0.10, 0.14, 0.15).forEach { v ->
            assertEquals(1.0, recoveredFraction(v, gyroDps = 15.0), 5e-4, "$v m/s with the implement rotating")
        }
    }

    @Test
    fun `a set whose rotation straddles the gate loses that cover, and that is the price`() {
        // RED until issue #87 wires [VelocityEstimator.gyroGateApplies] into
        // the mask. The test above keeps a CONSTANT 15 deg/s through the whole
        // capture, so its tenth percentile is 15 too and the straddle test does
        // not fire: nothing changes there, which is the point of the low probe.
        //
        // This one rotates at 15 deg/s from the start of the descent onward and
        // is still for the 3 s before it. 71% of the capture is above the gate
        // and 29% below, so the distribution straddles, the clause is dropped,
        // and the anchor supply the rotation used to hide behind is gone.
        //
        // THIS IS THE COST OF #87 STATED AS AN ASSERTION. A steady 0.05 m/s
        // phase on such a set was reported whole and is now erased to 0.2% of
        // its travel -- exactly the erasure issue #85 bounded but did not
        // remove, at exactly the speeds below
        // [DspConfig.anchorSlowPhaseFloorMps]. A 4 s eccentric over the
        // 0.333-0.345 m bench ROM issue #87 records runs at
        // 0.083-0.086 m/s, which is inside the band being spent. Above the
        // floor nothing is lost, and that is asserted here rather than assumed.
        assertEquals(
            15.0,
            VelocityEstimator.medianGyroDps(descent(0.05, gyroDps = 15.0, quietRestGyro = true)),
            1e-9,
            "median rotation of the straddling capture",
        )
        assertEquals(
            0.0,
            VelocityEstimator.gyroQuantileDps(
                descent(0.05, gyroDps = 15.0, quietRestGyro = true),
                VelocityEstimator.GYRO_STILLNESS_QUANTILE,
            ),
            1e-9,
            "tenth percentile of the straddling capture",
        )
        assertEquals(
            0.0015,
            recoveredFraction(0.05, gyroDps = 15.0, quietRestGyro = true),
            5e-4,
            "0.05 m/s on a straddling capture: erased, where a constant rotation would have hidden it",
        )
        assertEquals(
            0.9993,
            recoveredFraction(0.10, gyroDps = 15.0, quietRestGyro = true),
            5e-4,
            "0.10 m/s on a straddling capture: preserved, at the floor",
        )
        assertEquals(
            0.9993,
            recoveredFraction(0.15, gyroDps = 15.0, quietRestGyro = true),
            5e-4,
            "0.15 m/s on a straddling capture: preserved",
        )
    }

    @Test
    fun `reported peak velocity is the true velocity at and above the floor`() {
        // The same behaviour read as the number a lifter would see rather than
        // as a fraction. A 3 s eccentric over the 0.333-0.345 m bench ROM
        // issue #87 records is 0.111-0.115 m/s: it used to be
        // reported at half speed and is now reported whole.
        fun peakOverTrue(v: Double): Double {
            val s = series(v)
            return (1 until s.size).maxOf { abs(s.velocityMps[it]) } / v
        }
        assertEquals(1.0098, peakOverTrue(0.10), 5e-4, "0.10 m/s, reported peak as a fraction of true")
        assertEquals(1.0098, peakOverTrue(0.15), 5e-4, "0.15 m/s, reported peak as a fraction of true")
        assertEquals(1.0098, peakOverTrue(0.16), 5e-4, "0.16 m/s, reported peak as a fraction of true")
        // Below the floor it is still short, and shorter than it was is not the
        // point -- 0.72 of true against 0.51 before. Refusing an anchor leaves
        // drift where accepting one left nothing, and on a phase this slow the
        // drift is the larger term. Neither number is a measurement of a lift.
        assertEquals(0.7183, peakOverTrue(0.05), 5e-4, "0.05 m/s, reported peak as a fraction of true")
    }

    @Test
    fun `the accept rule is a band in velocity and elapsed time and the floor is where it closes`() {
        // The rule as behaviour rather than as arithmetic. Accepting an anchor
        // declares dv of velocity change, dt seconds after the last one, to
        // have been drift; that is allowed only if the implied bias rate is
        // small enough AND the travel it erases is small enough.
        val c = DspConfig()
        fun ok(dv: Double, dt: Double) = VelocityEstimator.anchorAcceptable(dv, dt, c)

        // The rate cap binds at short gaps: 0.02 m/s is too large a step to
        // have been drift in 0.3 s and small enough to have been drift in 0.5 s.
        assertEquals(false, ok(0.02, 0.3), "0.02 m/s step, 0.3 s ago")
        assertEquals(true, ok(0.02, 0.5), "0.02 m/s step, 0.5 s ago")
        // The displacement cap binds at long gaps, and a small step clears both.
        assertEquals(true, ok(0.05, 1.0), "0.05 m/s step, 1.0 s ago")
        assertEquals(true, ok(0.05, 4.0), "0.05 m/s step, 4.0 s ago")

        // The floor is the single point where the two caps cross: 0.10 m/s is
        // acceptable at EXACTLY one gap and refused on both sides of it. That
        // is what makes 0.10 a maximum rather than a threshold, and it is the
        // property an absolute cap could not have had.
        assertEquals(true, ok(0.10, 2.0), "0.10 m/s step, exactly 2.0 s ago")
        assertEquals(false, ok(0.10, 1.9), "0.10 m/s step, 1.9 s ago")
        assertEquals(false, ok(0.10, 2.1), "0.10 m/s step, 2.1 s ago")

        // Nothing above the floor is acceptable at any gap whatever. That is
        // the guarantee, and all three of these were accepted before.
        listOf(0.05, 0.3, 1.0, 2.0, 4.0, 30.0).forEach { dt ->
            assertEquals(false, ok(0.11, dt), "0.11 m/s step after $dt s")
            assertEquals(false, ok(0.14, dt), "0.14 m/s step after $dt s")
            assertEquals(false, ok(0.16, dt), "0.16 m/s step after $dt s")
        }
    }

    @Test
    fun `a lowering too slow to resolve now resolves, and its unanchored tail invents a rep`() {
        // Four synthetic reps, each a 5 s lowering over 0.4 m: mean 0.080 m/s,
        // and because the profile is a half sine its peak is 0.126 m/s, over
        // the floor. Every one used to be subtracted away and absorbed into the
        // bottom pause, which read 6.9 s -- the pause WAS the lowering. Three of
        // the four now resolve, at 4.24 s against a true 5.0 s.
        val samples = SyntheticSets.generate(
            List(4) {
                SyntheticSets.RepSpec(eccS = 5.0, bottomPauseS = 0.5, conS = 0.8, topPauseS = 1.0, romM = 0.4)
            },
            sampleRateHz = 50.0,
            seed = 1234,
            eccentricFirst = false,
        )
        val analysis = SetAnalyzer.analyze(samples, LiftDirection(startsWith = StartPhase.CONCENTRIC))
        assertEquals(3, analysis.reps.count { it.eccS != null }, "reps that resolved an eccentric, of 4 performed")
        analysis.reps.mapNotNull { it.eccS }.forEach {
            assertEquals(4.24, it, 5e-3, "resolved eccentric seconds, against a true 5.0")
        }
        // And the cost, pinned rather than left to be found later. The fourth
        // lowering is the last movement in the capture, so no later quiet window
        // anchors it; applyZupt holds the offset constant after the final anchor
        // and the residual reads as an UP run of 0.301 m, which the segmenter
        // pairs into a fifth rep on a set of four.
        //
        // That is the constant-offset extrapolation, not this accept rule. The
        // tail was flat before because the erasure reached it too, so the fix
        // exposes a defect rather than causing one -- which is not a defence,
        // only a statement of where to go and fix it. Raised separately.
        assertEquals(5, analysis.reps.size, "segmented reps; the synthetic contains 4")
        assertEquals(3.06, analysis.reps.last().conS, 5e-3, "the phantom fifth rep is a 3 s drive")
        assertEquals(0.301, analysis.reps.last().romM, 5e-4, "the phantom fifth rep, metres")
    }

    @Test
    fun `the still capture measures the drift rate any anchor rule has to tolerate`() {
        // The calibration source, pinned so it cannot move silently. This is the
        // only capture in the corpus with no motion in it, so it is the only
        // measurement of how fast the integrator error grows on its own, and any
        // rule that refused anchors at these rates would refuse the genuine
        // re-anchoring the whole correction depends on.
        val samples = ImuCsv.decode(
            javaClass.getResourceAsStream("/field-still-0rep.csv")!!.readBytes().decodeToString(),
        )
        val s = VelocityEstimator.estimate(samples, DspConfig(), MovementPlane.VERTICAL)
        val dt = 1.0 / s.sampleRateHz
        val raw = DoubleArray(s.size)
        for (i in 1 until s.size) raw[i] = raw[i - 1] + 0.5 * (s.accelMps2[i] + s.accelMps2[i - 1]) * dt
        assertEquals(0.0078182, abs(raw.last()) / s.timeS.last(), 1e-6, "mean drift rate over 44.9 s, m/s^2")
        // Worst block, taken over minStationaryS-wide blocks: that is the gap
        // between two adjacent anchors during a rest, and so the shortest
        // interval the accept rule is ever asked about.
        val step = (DspConfig().minStationaryS * s.sampleRateHz).toInt()
        var worst = 0.0
        var i = step
        while (i < s.size) {
            worst = maxOf(worst, abs(raw[i] - raw[i - step]) / (step * dt))
            i += step
        }
        assertEquals(0.0115116, worst, 1e-6, "worst drift rate over any 0.29 s block, m/s^2")
    }
}
