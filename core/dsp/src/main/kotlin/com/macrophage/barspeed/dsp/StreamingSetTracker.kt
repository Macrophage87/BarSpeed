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
) {
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
                // Anchor only on flat, near-anchor windows: a slow eccentric is
                // IMU-quiet and flat near its velocity peak, but sits a real
                // bar-speed away from the last anchor. Exception: after ANCHOR
                // STARVATION (no anchor for many seconds — residual drift may
                // have outrun the rejection band) the next flat window
                // re-anchors, or the tracker locks into a phantom phase.
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
                v > config.pauseBandMps -> 1
                v < -config.pauseBandMps -> -1
                else -> 0
            }
        if (type == runType) {
            if (type != 0) {
                runPeak = maxOf(runPeak, abs(v))
                runVelocitySum += v
                runSampleCount++
                runVelocityMax = maxOf(runVelocityMax, v)
                runDisplacement += abs(v) * frameIntervalS
            }
            return
        }
        // A movement run just ended; count it if it qualified. The displacement
        // gate filters dead-band jitter, walkout steps, and re-rack bumps.
        if (runType != 0) {
            val duration = timeS - runStartS
            val qualified =
                runPeak >= config.startThresholdMps &&
                    duration >= config.minPhaseS &&
                    runDisplacement >= config.minRomM
            if (qualified) onQualifiedRun(runType)
        }
        runType = type
        runStartS = timeS
        runPeak = abs(v)
        runVelocitySum = v
        runSampleCount = 1
        runVelocityMax = v
        runDisplacement = abs(v) * frameIntervalS
    }

    private fun onQualifiedRun(direction: Int) {
        val concentric = direction == 1
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

    private companion object {
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
