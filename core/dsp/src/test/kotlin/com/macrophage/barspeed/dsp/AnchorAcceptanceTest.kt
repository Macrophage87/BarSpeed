package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the ZUPT accept rule does to a phase that moves at a steady speed.
 *
 * An accelerometer cannot tell rest from constant velocity: both read zero
 * linear acceleration. So [VelocityEstimator] cannot decide from the sample and
 * decides from the anchor history instead. Today it asks one question -- is this
 * window within [DspConfig.anchorRejectThresholdMps] of the previous anchor --
 * and that is an absolute bar speed, so a steady phase slower than it is
 * accepted as a zero and subtracted away as drift.
 *
 * The synthetic below isolates the mechanism with no fixture, no ground truth
 * and no hardware: 3 s at rest, a 0.2 s ramp, 4 s at constant velocity, a 0.2 s
 * ramp, 3 s at rest, 100 Hz, level sensor, gyro pinned so only the velocity test
 * acts. A synthetic is not a lift; nothing here is evidence about a lifter, it
 * is evidence about the accept rule.
 *
 * These pins are characterization, marked (pre-fix): they record where the cliff
 * IS, not where it should be, so a change that moves it has to say so. The
 * LOCATION is pinned and not merely its existence -- 0.15 m/s is erased, 0.16 is
 * preserved, and [DspConfig.anchorRejectThresholdMps] is 0.15.
 */
class AnchorAcceptanceTest {
    /**
     * One descent at [vMps], level sensor, gyro at [gyroDps]. The ramps carry
     * the only non-zero acceleration, so the true travel over the descent is
     * exactly `vMps * (4.0 + 0.2)` metres.
     */
    private fun descent(vMps: Double, gyroDps: Double = 0.0): List<ImuSample> {
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
                wxDps = gyroDps,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }
        return out
    }

    private fun series(vMps: Double, gyroDps: Double = 0.0) =
        VelocityEstimator.estimate(descent(vMps, gyroDps), DspConfig(), MovementPlane.VERTICAL)

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
    private fun recoveredFraction(vMps: Double, gyroDps: Double = 0.0): Double {
        val s = series(vMps, gyroDps)
        var path = 0.0
        for (i in 1 until s.size) {
            if (s.timeS[i] in 3.0..7.4) path += abs(s.velocityMps[i]) * (s.timeS[i] - s.timeS[i - 1])
        }
        return path / (vMps * 4.2)
    }

    @Test
    fun `a steady descent slower than the rejection threshold is subtracted away as drift (pre-fix)`() {
        // Erased. 0.05 m/s reports NOTHING AT ALL: its ramps are 0.25 m/s^2,
        // inside stationaryAccBandG, so the whole capture is one quiet run and
        // there is no ramp left over to survive.
        assertEquals(0.0000, recoveredFraction(0.05), 5e-4, "0.05 m/s descent, fraction recovered")
        assertEquals(0.0384, recoveredFraction(0.10), 5e-4, "0.10 m/s descent, fraction recovered")
        assertEquals(0.0422, recoveredFraction(0.14), 5e-4, "0.14 m/s descent, fraction recovered")
        assertEquals(0.0427, recoveredFraction(0.15), 5e-4, "0.15 m/s descent, fraction recovered")
        // Preserved. The step between 0.15 and 0.16 is the whole finding and it
        // sits exactly on the constant asserted below.
        assertEquals(0.9993, recoveredFraction(0.16), 5e-4, "0.16 m/s descent, fraction recovered")
        assertEquals(0.9995, recoveredFraction(0.20), 5e-4, "0.20 m/s descent, fraction recovered")
        assertEquals(0.15, DspConfig().anchorRejectThresholdMps, "the constant the cliff sits on")
    }

    @Test
    fun `the gyro term hides the erasure wherever the implement rotates`() {
        // The same descents with the gyro at 15 deg/s, over stationaryGyroBandDps,
        // so no sample is quiet, no anchor is placed and nothing is subtracted.
        // This is why the erasure is invisible on bar-mounted captures, and why
        // deleting the gyro term costs measured travel while the cliff stands.
        listOf(0.05, 0.10, 0.14, 0.15).forEach { v ->
            assertEquals(1.0, recoveredFraction(v, gyroDps = 15.0), 5e-4, "$v m/s with the implement rotating")
        }
    }

    @Test
    fun `reported peak velocity is half the true velocity below the cliff (pre-fix)`() {
        // The same defect read as the number a lifter would see rather than as a
        // fraction: below the cliff the reported peak is half the speed the
        // sensor actually travelled at, and above it, right.
        fun peakOverTrue(v: Double): Double {
            val s = series(v)
            return (1 until s.size).maxOf { abs(s.velocityMps[it]) } / v
        }
        assertEquals(0.5068, peakOverTrue(0.10), 5e-4, "0.10 m/s, reported peak as a fraction of true")
        assertEquals(0.5068, peakOverTrue(0.15), 5e-4, "0.15 m/s, reported peak as a fraction of true")
        assertEquals(1.0098, peakOverTrue(0.16), 5e-4, "0.16 m/s, reported peak as a fraction of true")
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
