package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs

/** Live state for the in-set display. */
data class LiveSetState(
    val velocityMps: Double = 0.0,
    val phase: Phase = Phase.IDLE,
    val repCount: Int = 0,
    val currentPhaseElapsedS: Double = 0.0,
    /** Mean concentric velocity of each completed rep, for live per-rep bars. */
    val repMeanVelocities: List<Double> = emptyList(),
    /** Peak concentric velocity of each completed rep — the metric for explosive lifts. */
    val repPeakVelocities: List<Double> = emptyList(),
    /**
     * False once a movement run has carried further than any real phase of
     * this lift can — the integrator has lost its zero, and [repCount] from
     * that point on is a number the tracker cannot stand behind. Latched for
     * the set and never cleared.
     *
     * [repCount] is an `Int` and cannot say "I have lost track", so a stale
     * low count reads exactly like a correct low count. That is the repo's
     * *absence rendered as a value* class, and this is the same separation
     * `RepAnalysis.eccS` already makes by being nullable.
     *
     * NOTHING READS THIS. No rep count, no screen and no spoken cue behaves
     * differently because of it; it is a capability, not a fix.
     */
    val countTrusted: Boolean = true,
    /**
     * The reconstructed uniform clock this sample sits on, in seconds from the
     * first sample of the set -- the same `i * dt` time base
     * `VelocityEstimator.estimate` builds for the batch path, accumulated one
     * frame at a time instead of divided out of a span.
     *
     * NOT the arrival clock. BLE delivers frames in bursts sharing one arrival
     * stamp, so an arrival time is not a duration; this is what the integrator
     * above actually integrated against. Published because [LiveRepCall] needs
     * a [VelocitySeries] and a series is a velocity and a time.
     */
    val elapsedS: Double = 0.0,
    /**
     * The bias-corrected, low-passed vertical acceleration this sample
     * contributed, m/s^2 -- the number the integrator added, before
     * [velocityMps]'s anchor offset and frame scaling.
     *
     * Published for the same reason as [elapsedS]: a [VelocitySeries] carries
     * an acceleration and constructing one with zeros would state a
     * measurement nothing made. Nothing in the rep decision reads it --
     * [RepSegmenter] reads velocity and time only.
     */
    val accelMps2: Double = 0.0,
)

/**
 * Incremental, low-latency tracker for live in-set feedback (velocity readout,
 * rep counter, current phase). Uses ZUPT anchor resets but no retroactive drift
 * correction — authoritative metrics come from [SetAnalyzer] at set end.
 */
class StreamingSetTracker(
    private val startsWith: StartPhase = StartPhase.ECCENTRIC,
    private val config: DspConfig = DspConfig(),
    expectedSampleRateHz: Double = 100.0,
    /**
     * Maps measured sensor motion into the lifter's frame — negative when the
     * sensor rides a cable's weight stack and moves opposite the handle. Applied
     * to the live velocity so the on-screen phase is the lifter's, not the stack's.
     */
    private val velocityScale: Double = 1.0,
    /**
     * True when the drive moves in the positive direction --
     * [LiftDirection.driveIsPositive]. A leg curl and a lat pulldown drive
     * DOWN, and without this the tracker completes every rep on the return.
     *
     * Deliberately NOT folded into [velocityScale]. That maps sensor motion
     * into the lifter's frame -- cable inversion and pulley ratio -- while this
     * says which way the lifter drives. On a leg curl both are negative and
     * their product is positive, so a single combined factor would cancel and
     * look correct while reporting the wrong stroke. One number, two jobs, in
     * the class least able to show it.
     */
    private val driveIsPositive: Boolean = true,
) {
    /** +1 when the drive moves up, -1 when it moves down. */
    private val driveSign: Double = if (driveIsPositive) 1.0 else -1.0

    /**
     * The run limits [updateRuns] applies, converted into the frame it sees.
     *
     * That method is handed a velocity already multiplied by [velocityScale],
     * while [DspConfig]'s limits are sensor-frame numbers. Applying them
     * unconverted let a declared pulley ratio move the live rep count and flip
     * [LiveSetState.countTrusted] on a set nothing about the lift had changed.
     * Issue #70; the batch segmenter converts through the same type, so the
     * two paths cannot drift apart on this.
     *
     * [velocityScale] rather than a ratio of its own: it IS
     * `LiftDirection.sensorToLifter`, and [RunThresholds.forSeriesScaledBy]
     * takes its magnitude. The KDoc above warns against folding
     * [driveIsPositive] into it, and nothing here does -- a drive direction is
     * not a scale and does not appear in any limit.
     */
    private val thresholds: RunThresholds = RunThresholds.forSeriesScaledBy(config, velocityScale)

    private var filter = Biquad.lowPass(config.lowPassCutoffHz, expectedSampleRateHz)

    // The sensor samples uniformly, but two things make arrival timestamps
    // unusable as an integration time base: BLE delivers frames in bursts that
    // share one arrival time (most samples would integrate over zero seconds),
    // and the sensor may stream at a rate other than the one we requested.
    // Measure the average per-frame interval from the arrival SPAN and run the
    // whole pipeline on a reconstructed uniform clock.
    private var rateCalibrated = false
    private var firstArrivalMs = Long.MIN_VALUE
    private var lastArrivalMs = Long.MIN_VALUE
    private var sampleCount = 0
    private var frameIntervalS = 1.0 / expectedSampleRateHz
    private var correctedTimeS = 0.0

    private var lastAccel = 0.0
    private var rawV = 0.0
    private var anchorOffset = 0.0
    private var anchorTimeS = 0.0

    /**
     * Learned accelerometer bias (gravity-projection error while the sensor is
     * tilted/rotated). When the IMU is quiet the true acceleration is zero, so
     * the filtered reading IS the bias; without correction, continuous press
     * cycling drifts the integrator far past the ZUPT rejection band.
     */
    private var accelBias = 0.0

    private var quietWindowStartS = Double.NaN
    private var quietWindowLo = 0.0
    private var quietWindowHi = 0.0

    private var runType = 0
    private var runStartS = 0.0
    private var runPeak = 0.0

    private var repCount = 0
    private val repVelocities = mutableListOf<Double>()
    private val repPeaks = mutableListOf<Double>()
    private var runVelocitySum = 0.0
    private var runSampleCount = 0
    private var runVelocityMax = 0.0
    private var runDisplacement = 0.0

    /** Ecc-first lifts: a concentric only counts after a qualified eccentric (kills walkout/re-rack bumps). */
    private var eccentricPending = false

    /** Latched false by [noteRunaway]; published as [LiveSetState.countTrusted]. */
    private var countTrusted = true

    var state: LiveSetState = LiveSetState()
        private set

    fun feed(sample: ImuSample): LiveSetState {
        val firstSample = sampleCount == 0
        updateClock(sample.timestampMs)
        val timeS = correctedTimeS
        val filtered = filter.process(FrameTransform.verticalLinearAccelMps2(sample, config.gravityMps2))
        val quietSample = VelocityEstimator.isQuietSample(sample, config)
        if (quietSample) {
            val alpha = (frameIntervalS / BIAS_TAU_S).coerceAtMost(MAX_BIAS_ALPHA)
            accelBias += alpha * (filtered - accelBias)
        }
        val accel = filtered - accelBias
        if (!firstSample) {
            rawV += 0.5 * (accel + lastAccel) * frameIntervalS
        }
        lastAccel = accel

        updateZupt(quietSample, timeS)
        val v = (rawV - anchorOffset) * velocityScale
        updateRuns(v, timeS)

        state =
            LiveSetState(
                velocityMps = v,
                phase = currentPhase(),
                repCount = repCount,
                currentPhaseElapsedS = if (runType == 0 && repCount == 0) 0.0 else timeS - runStartS,
                repMeanVelocities = repVelocities.toList(),
                repPeakVelocities = repPeaks.toList(),
                countTrusted = countTrusted,
                elapsedS = timeS,
                accelMps2 = accel,
            )
        return state
    }

    /** Advance the reconstructed uniform clock and (re)estimate the frame interval. */
    private fun updateClock(arrivalMs: Long) {
        if (sampleCount == 0) {
            firstArrivalMs = arrivalMs
        } else {
            correctedTimeS += frameIntervalS
        }
        lastArrivalMs = arrivalMs
        sampleCount++
        val spanS = (lastArrivalMs - firstArrivalMs) / 1000.0
        if (sampleCount >= RATE_WARMUP_SAMPLES && spanS >= RATE_WARMUP_SPAN_S) {
            val measured = spanS / (sampleCount - 1)
            if (measured in MIN_FRAME_INTERVAL_S..MAX_FRAME_INTERVAL_S) {
                frameIntervalS = measured
                if (!rateCalibrated) {
                    filter = Biquad.lowPass(config.lowPassCutoffHz, 1.0 / measured)
                    rateCalibrated = true
                }
            }
        }
    }

    private fun updateZupt(quiet: Boolean, timeS: Double) {
        if (quiet) {
            if (quietWindowStartS.isNaN()) {
                quietWindowStartS = timeS
                quietWindowLo = rawV
                quietWindowHi = rawV
            }
            quietWindowLo = minOf(quietWindowLo, rawV)
            quietWindowHi = maxOf(quietWindowHi, rawV)
            if (timeS - quietWindowStartS >= config.minStationaryS) {
                // Anchor only on flat windows [VelocityEstimator.anchorAcceptable]
                // will have: a slow eccentric is IMU-quiet and flat near its
                // velocity peak, so flatness alone accepts it. Exception: after
                // ANCHOR STARVATION (no anchor for many seconds — residual drift
                // may have outrun the caps) the next flat window re-anchors, or
                // the tracker locks into a phantom phase.
                // The batch path builds its anchors from the same functions
                // and its correction is retroactive while this one is not, so
                // the two still disagree on a capture. Since issue #87 the
                // batch path drops the gyro clause on sets whose gyro
                // distribution straddles the gate, so on those seven captures
                // the two paths disagree about candidacy and therefore about
                // which windows are anchors.
                val stable = quietWindowHi - quietWindowLo <= config.anchorStabilityBandMps
                val elapsedS = timeS - anchorTimeS
                val nearPrev = VelocityEstimator.anchorAcceptable(abs(rawV - anchorOffset), elapsedS, config)
                val starved = elapsedS > ANCHOR_STARVATION_S
                if (stable && (nearPrev || starved)) {
                    anchorOffset = rawV
                    anchorTimeS = timeS
                }
                quietWindowStartS = timeS
                quietWindowLo = rawV
                quietWindowHi = rawV
            }
        } else {
            quietWindowStartS = Double.NaN
        }
    }

    private fun updateRuns(v: Double, timeS: Double) {
        val type =
            when {
                v > thresholds.pauseBandMps -> 1
                v < -thresholds.pauseBandMps -> -1
                else -> 0
            }
        if (type == runType) {
            if (type != 0) {
                runPeak = maxOf(runPeak, abs(v))
                runVelocitySum += v * driveSign
                runSampleCount++
                runVelocityMax = maxOf(runVelocityMax, v * driveSign)
                runDisplacement += abs(v) * frameIntervalS
                noteRunaway()
            }
            return
        }
        // A movement run just ended; count it if it qualified. The displacement
        // gate filters dead-band jitter, walkout steps, and re-rack bumps.
        if (runType != 0) {
            val duration = timeS - runStartS
            val qualified =
                runPeak >= thresholds.startThresholdMps &&
                    duration >= thresholds.minPhaseS &&
                    runDisplacement >= thresholds.minRomM &&
                    runDisplacement <= thresholds.maxRunDisplacementM
            if (qualified) onQualifiedRun(runType)
        }
        runType = type
        runStartS = timeS
        runPeak = abs(v)
        runVelocitySum = v * driveSign
        runSampleCount = 1
        runVelocityMax = v * driveSign
        runDisplacement = abs(v) * frameIntervalS
        noteRunaway()
    }

    /**
     * The run in progress has carried past the CONVERTED displacement cap read
     * from [thresholds] — [DspConfig.maxRunDisplacementM] scaled by
     * `abs(velocityScale)`, not that constant direct — which is the same bound
     * the qualification gate above uses to throw the run away. At a declared
     * `travelRatio` of 1.0 the two are the same number.
     *
     * Tested WHILE the run accumulates rather than when it ends, because a
     * runaway run may not end. On `field-reardeltfly-s32-set06` one run carries
     * 127 m across 51 s; waiting for it to close would withhold the fact for
     * most of the set, and the point of the flag is to be true of the count
     * while the lifter is still reading it. The two readings agree on every
     * committed capture — [LiveIntegratorRunawayTest] pins that they do — so
     * nothing here rests on the difference.
     *
     * Never cleared. A run that has travelled that far did so because the
     * integrator has no zero, and nothing downstream re-establishes one within
     * the set.
     */
    private fun noteRunaway() {
        if (runDisplacement > thresholds.maxRunDisplacementM) countTrusted = false
    }

    private fun onQualifiedRun(direction: Int) {
        val concentric = (direction == 1) == driveIsPositive
        if (startsWith == StartPhase.ECCENTRIC) {
            // Pair phases: down arms the rep, the following up completes it.
            if (!concentric) {
                eccentricPending = true
            } else if (eccentricPending) {
                eccentricPending = false
                countRep()
            }
        } else if (concentric) {
            countRep()
        }
    }

    private fun countRep() {
        repCount++
        if (runSampleCount > 0) repVelocities += runVelocitySum / runSampleCount
        repPeaks += runVelocityMax
    }

    companion object {
        /**
         * Build a tracker for a declared lift.
         *
         * The call site in `:app` has an `ExerciseDef` in hand. `:app` does have
         * a test source set now, but nothing in it constructs a tracker, so a
         * decision made at that call site is still asserted nowhere. This
         * factory is where the mapping from a declared lift to tracker
         * parameters lives, in a module a test can reach.
         */
        fun forLift(direction: LiftDirection, config: DspConfig = DspConfig()): StreamingSetTracker =
            StreamingSetTracker(
                startsWith = direction.startsWith,
                config = config,
                velocityScale = direction.sensorToLifter,
                driveIsPositive = direction.driveIsPositive,
            )

        const val RATE_WARMUP_SAMPLES = 24
        const val RATE_WARMUP_SPAN_S = 1.0
        const val MIN_FRAME_INTERVAL_S = 1.0 / 250.0
        const val MAX_FRAME_INTERVAL_S = 1.0 / 4.0

        /** Time constant for the quiet-sample accel-bias learner (s). */
        const val BIAS_TAU_S = 2.0
        const val MAX_BIAS_ALPHA = 0.2

        /** No accepted anchor for this long → next flat window re-anchors (s). */
        const val ANCHOR_STARVATION_S = 6.0
    }

    private fun currentPhase(): Phase = when (runType) {
        1 -> Phase.CONCENTRIC
        -1 -> Phase.ECCENTRIC
        else ->
            when {
                repCount == 0 && startsWith == StartPhase.ECCENTRIC -> Phase.IDLE
                startsWith == StartPhase.ECCENTRIC -> Phase.TOP_PAUSE
                else -> Phase.BOTTOM_PAUSE
            }
    }
}
