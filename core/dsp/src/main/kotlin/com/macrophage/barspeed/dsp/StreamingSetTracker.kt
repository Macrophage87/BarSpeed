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
) {
    private val filter = Biquad.lowPass(config.lowPassCutoffHz, expectedSampleRateHz)

    private var lastTimeS = Double.NaN
    private var lastAccel = 0.0
    private var rawV = 0.0
    private var anchorOffset = 0.0

    private var quietWindowStartS = Double.NaN
    private var quietWindowLo = 0.0
    private var quietWindowHi = 0.0

    private var runType = 0
    private var runStartS = 0.0
    private var runPeak = 0.0

    private var repCount = 0

    var state: LiveSetState = LiveSetState()
        private set

    fun feed(sample: ImuSample): LiveSetState {
        val timeS = sample.timestampMs / 1000.0
        val accel = filter.process(FrameTransform.verticalLinearAccelMps2(sample, config.gravityMps2))
        if (!lastTimeS.isNaN()) {
            val dt = (timeS - lastTimeS).coerceIn(0.0, 0.1)
            rawV += 0.5 * (accel + lastAccel) * dt
        }
        lastTimeS = timeS
        lastAccel = accel

        updateZupt(sample, timeS)
        val v = rawV - anchorOffset
        updateRuns(v, timeS)

        state =
            LiveSetState(
                velocityMps = v,
                phase = currentPhase(),
                repCount = repCount,
                currentPhaseElapsedS = if (runType == 0 && repCount == 0) 0.0 else timeS - runStartS,
            )
        return state
    }

    private fun updateZupt(sample: ImuSample, timeS: Double) {
        val quiet =
            abs(FrameTransform.accMagnitudeG(sample) - 1.0) < config.stationaryAccBandG &&
                FrameTransform.gyroMagnitudeDps(sample) < config.stationaryGyroBandDps
        if (quiet) {
            if (quietWindowStartS.isNaN()) {
                quietWindowStartS = timeS
                quietWindowLo = rawV
                quietWindowHi = rawV
            }
            quietWindowLo = minOf(quietWindowLo, rawV)
            quietWindowHi = maxOf(quietWindowHi, rawV)
            if (timeS - quietWindowStartS >= config.minStationaryS) {
                // Anchor only on flat, near-zero windows: a slow eccentric is IMU-quiet
                // but its raw velocity ramps and sits far from the last anchor.
                val stable = quietWindowHi - quietWindowLo <= config.anchorStabilityBandMps
                if (stable && abs(rawV - anchorOffset) <= config.anchorRejectThresholdMps) {
                    anchorOffset = rawV
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
            if (type != 0) runPeak = maxOf(runPeak, abs(v))
            return
        }
        // A movement run just ended; count it if it qualified.
        if (runType != 0) {
            val duration = timeS - runStartS
            val qualified = runPeak >= config.startThresholdMps && duration >= config.minPhaseS
            val concentricDirection = 1
            if (qualified && runType == concentricDirection) repCount++
        }
        runType = type
        runStartS = timeS
        runPeak = abs(v)
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
