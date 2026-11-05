package com.macrophage.barspeed.model

import kotlin.math.sqrt

/**
 * Two facts about one unit's buffer that say whether it can stand for the set
 * beside a partner's: how long it ran (#242), and whether it moved at all
 * (#287).
 *
 * A free object rather than a member of [SensorCapturePolicy], for the reason
 * `armedCaptureOf` is a free function: these read SAMPLES, and nothing in that
 * object takes or returns an [ImuSample]. [SensorCapturePolicy.analysable]
 * reads the answers as plain numbers and flags.
 *
 * Arithmetic on the raw accelerometer columns and the arrival stamps, and
 * nothing else: no filter, no orientation, no velocity. That is why it lives in
 * `:core:model` beside the decision rather than in `:core:dsp` -- the decision
 * must be pinned where the rest of the capture policy is, and `armedCaptureOf`
 * must be able to ask it without `:app` passing a supplier through, which is
 * what keeps the whole judgement test-gated.
 */
object UnitMotion {
    /**
     * At or below this, on EVERY accelerometer axis, a stream's population
     * standard deviation is a unit that did not move: 0.01 g.
     *
     * ARGUED FROM THE COMMITTED CORPUS, and `UnitMotionFieldTest` in `:core:dsp`
     * measures it over every capture there. The one capture of a unit that was
     * not moved, `field-still-0rep`, reads 0.0005 g on its noisiest axis. The
     * quietest capture of a unit that WAS moved reads 0.0331 g:
     * `field-ropedeadhang-hold20-s37-set11`, a dead hang, where nothing but the
     * lifter's sway moved it. The stack unit of field-41 set 16, the stream
     * #287 says never moved, reads 0.0374 g -- 75 times the still capture --
     * and since #317 counts 14 of its 14 reps. 0.01 g is twenty times the
     * still capture and a third of the quietest moved one.
     *
     * NOT THE GYRO, deliberately. A weight stack slides without rotating, so a
     * unit riding one reports a few tenths of a degree per second whatever the
     * load did: the leg curl, leg press, face pull and cable-row stack units in
     * the corpus read 0.5 to 2.2 dps, and set 16's stack unit 0.43. A rotation
     * floor is exactly what #287's own figures (0.45 and 0.68 dps, 0.3 degrees
     * of roll) would have tripped on a unit that was doing its job. One
     * committed capture of one still unit is a thin floor; the threshold sits
     * far from both populations so that a second one is unlikely to move it.
     */
    const val STILL_ACCEL_SD_G = 0.01

    /** A buffer's time on the link: its last arrival stamp minus its first, or null with fewer than two samples. */
    fun spanMs(samples: List<ImuSample>): Long? {
        if (samples.size < 2) return null
        return samples.maxOf { it.timestampMs } - samples.minOf { it.timestampMs }
    }

    /**
     * True when no accelerometer axis of this buffer varied by more than
     * [STILL_ACCEL_SD_G], false when one did, null when the buffer is too short
     * to analyse at all ([SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]): absence,
     * never a verdict about a unit nobody measured.
     */
    fun still(samples: List<ImuSample>): Boolean? {
        if (samples.size < SensorCapturePolicy.MIN_ANALYSABLE_FRAMES) return null
        return listOf(ImuSample::axG, ImuSample::ayG, ImuSample::azG).all { axis ->
            populationSd(samples.map(axis)) <= STILL_ACCEL_SD_G
        }
    }

    /** The largest per-axis population standard deviation, in g; what [still] compares. */
    fun accelSdG(samples: List<ImuSample>): Double =
        listOf(ImuSample::axG, ImuSample::ayG, ImuSample::azG).maxOf { populationSd(samples.map(it)) }

    private fun populationSd(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
