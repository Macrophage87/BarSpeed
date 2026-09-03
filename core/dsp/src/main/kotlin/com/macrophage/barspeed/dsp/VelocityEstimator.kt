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
 * quiet windows as zero-velocity anchors. A controlled eccentric produces
 * near-zero acceleration too, so acceleration alone cannot place the anchors;
 * the discriminator is the integrated velocity, in [anchorAcceptable], which
 * bounds how much travel an anchor is allowed to declare to have been drift.
 */
object VelocityEstimator {
    /**
     * The series every consumer gets. Identical to [estimateAnchored] today;
     * the split exists so a second drift stage has a named BEFORE to be
     * measured against rather than a reconstruction of one. See issue #94.
     */
    fun estimate(
        samples: List<ImuSample>,
        config: DspConfig = DspConfig(),
        plane: MovementPlane = MovementPlane.VERTICAL,
    ): VelocitySeries = estimateAnchored(samples, config, plane)

    /**
     * Velocity with the ZUPT anchor pass applied and nothing after it.
     *
     * Internal because no production caller should reach for a partly-corrected
     * series: [estimate] is the series the analyzer runs on. It exists so a
     * test can say what a later stage changed, which a test rebuilding the
     * pipeline for itself could only approximate -- and a rebuilt pipeline that
     * drifts from the shipped one is a measurement of nothing. Issue #94's own
     * record carries an instance: four test files measured a
     * `StreamingSetTracker` construction the app had stopped using.
     */
    internal fun estimateAnchored(
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

    /**
     * May a quiet window be taken as a zero-velocity anchor, given that
     * accepting it declares [dvMps] of velocity change since the previous
     * anchor, [dtS] seconds ago, to have been drift rather than movement?
     *
     * The batch and streaming paths ask the same question and used to answer it
     * with two copies of the same expression, one in each file, with nothing
     * asserting they agreed. This is that expression, once.
     *
     * Two caps, and the answer is yes only if both hold.
     *
     * The BIAS-RATE cap. If the sensor was at rest at both anchors then [dvMps]
     * is accumulated integration error, and that error grows no faster than the
     * accelerometer bias. The limit is `floor^2 / (2 * minRomM)` m/s^2, which is
     * 0.05 at the shipped defaults; the only capture in the corpus with no
     * motion in it drifts at 0.0078 m/s^2 mean and 0.0115 m/s^2 across its worst
     * 0.3 s block, so the cap sits above the worst rate anyone has measured by
     * more than a factor of four, and refuses none of that capture's anchors.
     *
     * The ERASED-DISPLACEMENT cap. `0.5 * dv * dt` is exactly the travel the
     * piecewise-linear offset below removes over the interval -- the area of the
     * ramp -- and it may not exceed [DspConfig.minRomM], the least distance this
     * pipeline is willing to call a rep.
     *
     * The rate cap binds at short gaps and the displacement cap at long ones, so
     * `min(R*dt, 2B/dt)` peaks at `sqrt(2*B*R)`, which is identically
     * [DspConfig.anchorSlowPhaseFloorMps]. That is the whole guarantee: no
     * steady phase at or above the floor can be mistaken for a pause at ANY gap
     * length. An absolute threshold could not say that -- it had one number for
     * a third of a second of bias and half a minute of it, so it had to be set
     * wide enough for the worst gap, and 0.15 m/s is a real bar speed.
     *
     * What this does NOT do is tell rest from constant velocity, which no
     * accelerometer can. It bounds how fast a movement has to be before the
     * question is allowed to arise.
     */
    internal fun anchorAcceptable(dvMps: Double, dtS: Double, config: DspConfig): Boolean {
        val floor = config.anchorSlowPhaseFloorMps
        val biasRateCapMps2 = floor * floor / (2.0 * config.minRomM)
        return dvMps <= biasRateCapMps2 * dtS && 0.5 * dvMps * dtS <= config.minRomM
    }

    /**
     * The ABSOLUTE quiet test: near 1 g and barely rotating, both thresholds
     * fixed. This is the predicate [StreamingSetTracker] reads sample by
     * sample, where there is no set to take a distribution over.
     */
    internal fun isQuietSample(sample: ImuSample, config: DspConfig): Boolean =
        isAnchorCandidate(sample, config, gyroGate = true)

    /**
     * One sample's anchor candidacy, with the gyro clause switchable.
     *
     * The acceleration clause is not optional and never has been. The gyro
     * clause is separated out because it carries an assumption the
     * acceleration clause does not: see [gyroGateApplies].
     */
    internal fun isAnchorCandidate(sample: ImuSample, config: DspConfig, gyroGate: Boolean): Boolean =
        abs(FrameTransform.accMagnitudeG(sample) - 1.0) < config.stationaryAccBandG &&
            (!gyroGate || FrameTransform.gyroMagnitudeDps(sample) < config.stationaryGyroBandDps)

    /** Median gyro magnitude over the set, deg/s. */
    internal fun medianGyroDps(samples: List<ImuSample>): Double = gyroQuantileDps(samples, 0.5)

    /**
     * Linearly-interpolated quantile of gyro magnitude over the set, deg/s.
     * `fraction` is 0.0 for the minimum and 1.0 for the maximum.
     */
    internal fun gyroQuantileDps(samples: List<ImuSample>, fraction: Double): Double {
        if (samples.isEmpty()) return 0.0
        val g = DoubleArray(samples.size) { FrameTransform.gyroMagnitudeDps(samples[it]) }
        g.sort()
        val pos = (g.size - 1) * fraction
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        return if (lo == hi) g[lo] else g[lo] + (pos - lo) * (g[hi] - g[lo])
    }

    /** The low probe of the straddle test in [gyroGateApplies]. */
    internal const val GYRO_STILLNESS_QUANTILE = 0.10

    /**
     * Whether this set's own rotation supports the fixed gyro clause.
     *
     * [DspConfig.stationaryGyroBandDps] encodes one premise: a resting
     * implement does not rotate at 10 deg/s. That is a claim about the MOUNT,
     * not about the lift, and it is testable against the set that was
     * recorded. The clause is dropped only where the set's gyro distribution
     * STRADDLES the gate -- more than half of it above, at least a tenth of it
     * below. Both probes are against the same constant; no second threshold is
     * introduced.
     *
     * Both probes are duty-cycle statistics over the whole recorded window, so
     * idle time inside the recording moves them. Appending still samples flips
     * `field-backsquat-99hz-6rep` at 4.54 s, `field-ohp-rotating-8rep` at
     * 8.03 s and `field-ohp-3010-6rep-s37-set02` at 11.80 s. The first of those
     * is pinned in [GyroGateTest]; how long the lifter left the sensor running
     * is therefore part of what selects the policy, and nothing here bounds it.
     *
     * That is not a hypothetical margin. The buffer this function is handed is
     * everything `RecordViewModel.runSetWrite` froze into the pending write,
     * and its own cue-track comment measures the stream running on past the
     * terminal cue at 4.3 to 13.7 s across the eleven sets of session 32 that
     * carry both a cue and a stream. All three flip points above -- 4.54, 8.03
     * and 11.80 s -- lie inside that range, so on those captures the length of
     * the post-Done tail, which is tap latency and nothing else, is of the same
     * order as the margin selecting the policy. A slower tap can restore the
     * gyro clause, and with it the empty summary those sets used to publish.
     *
     * [Field] UNVERIFIED, and the direction is not measured. The flips above
     * were produced by appending STILL samples, gyro zeroed; a real tail is the
     * sensor being HANDLED, whose gyro distribution nothing here has measured
     * and which could move either probe either way. Answering it needs a
     * session that records tap-to-stop latency per set and keeps the raw stream:
     * for each set, read the terminal-cue instant off the cue track, measure the
     * tail to the last sample, and re-run `gyroGateApplies` on the buffer
     * truncated at the cue and on the full buffer. Pass criterion: the two agree
     * on all eleven sets. Any disagreement is a set whose published summary
     * depends on how fast the lifter reached the phone. Issue #87 carries the
     * protocol.
     *
     * The two halves say different things and both are needed.
     *
     * Above the median, the clause is no longer discriminating. It rejects the
     * MAJORITY of the set on rotation alone, which is a veto rather than a
     * filter, and what it vetoes is the anchor supply of a set whose implement
     * rotates throughout. That is the bar-mounted case issue #87 is about.
     *
     * Below the tenth percentile, the set still contains a genuinely
     * low-rotation population -- moments where the implement really is close to
     * still -- so there is something for the acceleration term to find once the
     * clause is out of the way. Where even the tenth percentile is above the
     * gate the implement never stops rotating at all, the clause is not the
     * reason no rest is found, and dropping it only admits samples taken while
     * the sensor was turning. `field-reardeltfly-s32-set06` is that case: it is
     * the corpus's only capture with a tenth percentile above the gate
     * (13.19 deg/s against 2.26-4.28 on the seven that straddle), and it is the
     * only capture the low probe excludes.
     *
     * BOTH CHOICES ARE CHOICES. The median is where filtering becomes vetoing;
     * the tenth percentile is selected on this corpus and nothing derives it.
     * Measured: the fifth percentile does NOT exclude the rear delt fly (6.50
     * deg/s, under the gate) and the twenty-fifth wrongly keeps the gate on
     * `field-bench-3010-6rep-s37-set06` (12.93 deg/s, over it). So the low
     * probe is fitted to one capture, and saying otherwise would be a claim
     * stronger than its evidence.
     *
     * What IS structural is the consequence. This function selects between
     * exactly two behaviours that have both been measured over this corpus --
     * today's two-term predicate, and the acceleration term alone -- so unlike
     * a retuned band it cannot produce a third, unstudied regime, and the 22
     * captures the gate still holds on are bit-identical either way -- not
     * because their whole distribution sits under the gate
     * (`field-legcurl-1030-12rep-c` peaks at 585 deg/s) but because on
     * twenty-one of the twenty-two the MEDIAN does, and on
     * `field-reardeltfly-s32-set06` -- median 62.87 deg/s -- the tenth
     * percentile is above the gate too, which is the low probe doing its job.
     * Ten of the 22 are bar- or hand-held. (It read 21, twenty of twenty-one
     * and nine before `field-rdl-3010-10rep-s36-set04` was committed for issue
     * #138; that capture's median is 5.81 deg/s, so it holds the gate and is
     * bar-mounted.)
     *
     * Not verified: whether a sample admitted here is one where the implement
     * was actually at rest. Nothing in this repository can answer that; only a
     * field session with a per-rep stopwatch can. Issue #87 carries the
     * protocol.
     */
    internal fun gyroGateApplies(samples: List<ImuSample>, config: DspConfig): Boolean {
        val band = config.stationaryGyroBandDps
        val straddles = medianGyroDps(samples) >= band &&
            gyroQuantileDps(samples, GYRO_STILLNESS_QUANTILE) < band
        return !straddles
    }

    /**
     * True where the IMU is quiet for at least minStationaryS, with the gyro
     * clause applied only on sets whose own rotation supports it. See
     * [gyroGateApplies]; on a set it rejects, candidacy rests on the
     * acceleration term and on [anchorAcceptable], which is the guard that
     * actually discriminates rest from slow motion.
     */
    internal fun quietMask(samples: List<ImuSample>, timeS: DoubleArray, config: DspConfig): BooleanArray {
        val n = samples.size
        val gyroGate = gyroGateApplies(samples, config)
        val candidate = BooleanArray(n) { isAnchorCandidate(samples[it], config, gyroGate) }
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
        // (a) raw velocity is noise-flat across it and (b) [anchorAcceptable] will
        // have the velocity step it declares to be drift. Note (a) is satisfied
        // MAXIMALLY by constant velocity — rawV is flat there, so hi - lo is about
        // zero — which is why (b) carries the whole discrimination and why it has
        // to be more than an absolute threshold.
        // Exception: after ANCHOR STARVATION (a long stretch with no acceptable
        // anchor — e.g. continuous press cycling where accel bias drifts rawV far
        // past the caps) the next flat window re-anchors regardless of (b). That
        // escape is deliberately NOT gated by (b): gating it takes the corpus
        // from 19 to 58 in absolute rep-count error. It is also the one route by
        // which a slow phase can still be erased, 0 to 7 times per capture, and
        // that is a separate defect from this one.
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
                    val elapsedS = timeS[mid] - timeS[last.index]
                    val nearPrev = anchorAcceptable(abs(rawV[mid] - last.rawValue), elapsedS, config)
                    val starved = elapsedS > ANCHOR_STARVATION_S
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
