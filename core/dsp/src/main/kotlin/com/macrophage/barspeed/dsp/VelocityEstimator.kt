package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
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

    /**
     * The same series mapped into the lifter's frame. A cable machine puts the
     * sensor on the weight stack, so the lifter's downward drive reads as upward
     * motion; a non-1:1 pulley scales the distance as well. Applying it once
     * here keeps every downstream stage — run classification, phase labels,
     * ROM, velocity — working in the frame the lifter actually moves in.
     */
    fun mappedToLifter(factor: Double): VelocitySeries {
        if (factor == 1.0) return this
        return VelocitySeries(
            timeS = timeS,
            accelMps2 = DoubleArray(size) { accelMps2[it] * factor },
            velocityMps = DoubleArray(size) { velocityMps[it] * factor },
            sampleRateHz = sampleRateHz,
        )
    }
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
    fun estimate(
        samples: List<ImuSample>,
        config: DspConfig = DspConfig(),
        plane: MovementPlane = MovementPlane.VERTICAL,
    ): VelocitySeries {
        require(samples.size >= 8) { "Not enough samples (${samples.size})" }

        // BLE delivers frames in bursts: several frames share one arrival
        // timestamp, then a ~100 ms jump. The sensor itself samples uniformly,
        // so rebuild a uniform time base from the overall span — integrating
        // against arrival times would give most samples zero duration.
        val n = samples.size
        val spanS = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        val sampleRateHz = measureSampleRate(n, spanS)
        val dt = 1.0 / sampleRateHz
        val timeS = DoubleArray(n) { it * dt }

        val filter = Biquad.lowPass(config.lowPassCutoffHz, sampleRateHz)
        val accel = DoubleArray(n)
        when (plane) {
            MovementPlane.VERTICAL ->
                for (i in 0 until n) {
                    accel[i] =
                        filter.process(FrameTransform.verticalLinearAccelMps2(samples[i], config.gravityMps2))
                }
            // A seated row or a chest-press machine barely moves vertically:
            // measuring world-Z there returns noise. Travel along one horizontal
            // line instead, with the line recovered from the set's own motion.
            MovementPlane.HORIZONTAL -> {
                val horizontal = samples.map { FrameTransform.horizontalLinearAccelMps2(it, config.gravityMps2) }
                val (ux, uy) = principalHorizontalAxis(horizontal)
                for (i in 0 until n) {
                    val (ax, ay) = horizontal[i]
                    accel[i] = filter.process(ax * ux + ay * uy)
                }
            }
        }

        // Trapezoidal integration to raw velocity. Accel bias (gravity-projection
        // error while the sensor is tilted) is NOT corrected here — the ZUPT
        // stage detects biased pauses and its piecewise-linear offset removes
        // the resulting drift. A per-sample bias learner is unusable: a clean
        // slow eccentric is IMU-quiet too, and the learner eats the movement.
        val rawV = DoubleArray(n)
        for (i in 1 until n) {
            rawV[i] = rawV[i - 1] + 0.5 * (accel[i] + accel[i - 1]) * dt
        }

        val quiet = quietMask(samples, timeS, config)
        val velocity = applyZupt(rawV, timeS, quiet, config)
        return VelocitySeries(timeS, accel, velocity, sampleRateHz)
    }

    private const val MIN_PLAUSIBLE_HZ = 4.0
    private const val MAX_PLAUSIBLE_HZ = 250.0
    private const val DEFAULT_HZ = 100.0

    /** No acceptable anchor for this long → next flat window re-anchors (s). */
    private const val ANCHOR_STARVATION_S = 6.0

    /**
     * Dominant direction of horizontal motion across the set, as a unit vector.
     *
     * Closed-form principal axis of the 2x2 covariance: a machine travels along
     * one line, so almost all horizontal variance lies on that line, and its
     * heading in the (yaw-free) world frame is whatever it is. The sign is
     * arbitrary here; [SetAnalyzer] orients it so the drive reads positive.
     */
    internal fun principalHorizontalAxis(accel: List<Pair<Double, Double>>): Pair<Double, Double> {
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for ((x, y) in accel) {
            sxx += x * x
            sxy += x * y
            syy += y * y
        }
        if (sxx + syy <= 0.0) return 1.0 to 0.0
        // Largest-eigenvalue eigenvector of [[sxx, sxy], [sxy, syy]].
        val theta = 0.5 * kotlin.math.atan2(2.0 * sxy, sxx - syy)
        return kotlin.math.cos(theta) to kotlin.math.sin(theta)
    }

    /**
     * The mean arrival rate of these samples, or null when they cannot state
     * one: (n-1)/span, clamped to a plausible band.
     *
     * Computed from the span rather than from consecutive deltas because BLE
     * delivers several samples under one arrival timestamp, so a median-of-dt
     * estimator returns 0 on real input.
     *
     * Null for a single sample, and null when every sample shares one arrival
     * stamp, because both are genuinely unmeasurable rather than slow. Use this
     * wherever the figure is PUBLISHED -- an unknown rate must reach the reader
     * as an absent key, never as a number they will divide by.
     *
     * What this measures is the rate the samples ARRIVED at, which equals the
     * rate the sensor sampled at only if none were dropped. Nothing in
     * [ImuSample] can express a gap, so a dropout reads here as a slower
     * sensor and there is no way to tell the two apart from the stream alone.
     */
    fun measuredSampleRateOrNull(sampleCount: Int, spanS: Double): Double? {
        if (sampleCount < 2 || spanS <= 0.0) return null
        return ((sampleCount - 1) / spanS).coerceIn(MIN_PLAUSIBLE_HZ, MAX_PLAUSIBLE_HZ)
    }

    /**
     * Span-based rate: (n-1)/span. Robust against burst arrivals, unlike median dt.
     *
     * Falls back to [DEFAULT_HZ] when the samples cannot state a rate, which is
     * a fabricated number and is deliberate here: the integrator needs some dt
     * to proceed with, and [estimate] refuses streams under eight samples
     * anyway. Never use this for a figure that leaves the app -- reach for
     * [measuredSampleRateOrNull] there, which says null instead of inventing
     * 100 Hz.
     */
    fun measureSampleRate(sampleCount: Int, spanS: Double): Double =
        measuredSampleRateOrNull(sampleCount, spanS) ?: DEFAULT_HZ

    internal fun isQuietSample(sample: ImuSample, config: DspConfig): Boolean =
        abs(FrameTransform.accMagnitudeG(sample) - 1.0) < config.stationaryAccBandG &&
            FrameTransform.gyroMagnitudeDps(sample) < config.stationaryGyroBandDps

    /** True where the IMU itself is quiet for at least minStationaryS. */
    internal fun quietMask(samples: List<ImuSample>, timeS: DoubleArray, config: DspConfig): BooleanArray {
        val n = samples.size
        val candidate = BooleanArray(n) { isQuietSample(samples[it], config) }
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
        // Exception: after ANCHOR STARVATION (a long stretch with no acceptable
        // anchor — e.g. continuous press cycling where accel bias drifts rawV far
        // past the rejection band) the next flat window re-anchors regardless of
        // (b). Tempo work pauses every few seconds, so starvation never triggers
        // there and slow eccentrics keep being rejected as anchors.
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
                    val last = anchors.last()
                    val nearPrev = abs(rawV[mid] - last.rawValue) <= config.anchorRejectThresholdMps
                    val starved = timeS[mid] - timeS[last.index] > ANCHOR_STARVATION_S
                    if (stable && (nearPrev || starved)) {
                        anchors += Anchor(mid, rawV[mid])
                    }
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
