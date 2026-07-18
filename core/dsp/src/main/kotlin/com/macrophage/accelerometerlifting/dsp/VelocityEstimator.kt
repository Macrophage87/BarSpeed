package com.macrophage.accelerometerlifting.dsp

import com.macrophage.accelerometerlifting.model.ImuSample
import kotlin.math.abs

/** Uniformly-indexed vertical kinematics for one recorded set. */
data class VelocitySeries(
    /** Sample times in seconds from the first sample. */
    val timeS: DoubleArray,
    /** Filtered vertical linear acceleration, m/s². */
    val accelMps2: DoubleArray,
    /** Drift-corrected vertical velocity, m/s (up positive). */
    val velocityMps: DoubleArray,
    /** Measured (not configured) sample rate. */
    val sampleRateHz: Double,
) {
    val size: Int get() = timeS.size
}

/**
 * Turns raw IMU samples into drift-corrected vertical velocity.
 *
 * Pipeline: world-frame rotation and gravity removal → low-pass filter →
 * trapezoidal integration → ZUPT-style piecewise-linear drift correction using
 * quiet windows as zero-velocity anchors. Quiet windows whose raw velocity is
 * far from the previous anchor are really slow motion (a controlled eccentric
 * produces near-zero acceleration too) and are rejected as anchors — the
 * discriminator is velocity, not acceleration alone.
 */
object VelocityEstimator {
    fun estimate(samples: List<ImuSample>, config: DspConfig = DspConfig()): VelocitySeries {
        require(samples.size >= 8) { "Not enough samples (${samples.size})" }

        val n = samples.size
        val t0 = samples.first().timestampMs
        val timeS = DoubleArray(n) { (samples[it].timestampMs - t0) / 1000.0 }
        val sampleRateHz = measureSampleRate(timeS)

        val filter = Biquad.lowPass(config.lowPassCutoffHz, sampleRateHz)
        val accel = DoubleArray(n)
        for (i in 0 until n) {
            accel[i] = filter.process(FrameTransform.verticalLinearAccelMps2(samples[i], config.gravityMps2))
        }

        // Trapezoidal integration to raw velocity.
        val rawV = DoubleArray(n)
        for (i in 1 until n) {
            val dt = (timeS[i] - timeS[i - 1]).coerceIn(0.0, 0.1)
            rawV[i] = rawV[i - 1] + 0.5 * (accel[i] + accel[i - 1]) * dt
        }

        val quiet = quietMask(samples, timeS, config)
        val velocity = applyZupt(rawV, timeS, quiet, config)
        return VelocitySeries(timeS, accel, velocity, sampleRateHz)
    }

    /** Median inter-sample interval → rate; robust against batched BLE arrivals. */
    fun measureSampleRate(timeS: DoubleArray): Double {
        val dts = (1 until timeS.size).map { timeS[it] - timeS[it - 1] }.filter { it > 0 }.sorted()
        if (dts.isEmpty()) return 100.0
        val median = dts[dts.size / 2]
        return if (median > 0) 1.0 / median else 100.0
    }

    /** True where the IMU itself is quiet for at least minStationaryS. */
    internal fun quietMask(samples: List<ImuSample>, timeS: DoubleArray, config: DspConfig): BooleanArray {
        val n = samples.size
        val candidate =
            BooleanArray(n) { i ->
                abs(FrameTransform.accMagnitudeG(samples[i]) - 1.0) < config.stationaryAccBandG &&
                    FrameTransform.gyroMagnitudeDps(samples[i]) < config.stationaryGyroBandDps
            }
        val quiet = BooleanArray(n)
        var runStart = -1
        for (i in 0..n) {
            val inRun = i < n && candidate[i]
            if (inRun && runStart < 0) runStart = i
            if (!inRun && runStart >= 0) {
                if (timeS[i - 1] - timeS[runStart] >= config.minStationaryS) {
                    for (j in runStart until i) quiet[j] = true
                }
                runStart = -1
            }
        }
        return quiet
    }

    private data class Anchor(val index: Int, val rawValue: Double)

    private fun applyZupt(rawV: DoubleArray, timeS: DoubleArray, quiet: BooleanArray, config: DspConfig): DoubleArray {
        val n = rawV.size
        val anchors = mutableListOf(Anchor(0, rawV[0]))
        // Walk quiet regions in windows of minStationaryS. A window anchors only if
        // (a) raw velocity is noise-flat across it (true pause, not slow motion) and
        // (b) its raw value is near the previous anchor (drift, not displacement).
        var i = 1
        while (i < n) {
            if (!quiet[i]) {
                i++
                continue
            }
            var windowStart = i
            var lo = rawV[i]
            var hi = rawV[i]
            var j = i
            while (j < n && quiet[j]) {
                lo = minOf(lo, rawV[j])
                hi = maxOf(hi, rawV[j])
                if (timeS[j] - timeS[windowStart] >= config.minStationaryS) {
                    val mid = (windowStart + j) / 2
                    val stable = hi - lo <= config.anchorStabilityBandMps
                    val nearPrev = abs(rawV[mid] - anchors.last().rawValue) <= config.anchorRejectThresholdMps
                    if (stable && nearPrev) anchors += Anchor(mid, rawV[mid])
                    windowStart = j + 1
                    if (windowStart < n) {
                        lo = rawV[minOf(windowStart, n - 1)]
                        hi = lo
                    }
                }
                j++
            }
            i = j
        }

        // Piecewise-linear offset through anchor raw values; constant after the last anchor.
        val corrected = DoubleArray(n)
        var a = 0
        for (k in 0 until n) {
            while (a + 1 < anchors.size && anchors[a + 1].index <= k) a++
            val offset =
                if (a + 1 < anchors.size) {
                    val cur = anchors[a]
                    val next = anchors[a + 1]
                    val span = timeS[next.index] - timeS[cur.index]
                    if (span <= 0) {
                        cur.rawValue
                    } else {
                        cur.rawValue + (next.rawValue - cur.rawValue) * (timeS[k] - timeS[cur.index]) / span
                    }
                } else {
                    anchors[a].rawValue
                }
            corrected[k] = rawV[k] - offset
        }
        // Inside accepted quiet windows the bar is genuinely still; clamp to zero.
        for (k in 0 until n) {
            if (quiet[k] && abs(corrected[k]) < config.pauseBandMps) corrected[k] = 0.0
        }
        return corrected
    }
}
