package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample

/**
 * The closing-rule candidates issue #305's design round measured: live
 * deadlift counters that OPEN a rep on the drive and CLOSE it only on evidence
 * the rep completed. Models on the test classpath, wired to nothing.
 *
 * Every table in `ClosingRuleCandidateTest` re-derives by
 *
 * ```
 * ./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.ClosingRuleCandidateTest"
 * ```
 *
 * ## What every candidate reads
 *
 * One `LiveFrame` per sample: the tracker's own clock and drive-frame
 * acceleration, plus two facts about the RAW sample -- its accelerometer
 * magnitude and whether `VelocityEstimator.isQuietSample` holds for it. Since
 * #305's implement round `LiveSetState` publishes both
 * (`LiveSetState.accMagnitudeG`, `LiveSetState.quiet`). [ClosingFrames.of]
 * still computes them from the raw sample rather than reading them off the
 * tracker, so `ClosingRuleCandidateTest`'s licence that the harness matches
 * the app's own path is a comparison of two computations, not one read twice.
 *
 * ## One copy of the shipped rule
 *
 * `LiveFrame`, `RepClosed`, `DriveBrakeTracker` and `CycleRule` are production
 * code in `CycleRepCounter.kt`, and the constants are `DspConfig`'s `cycle*`
 * fields -- each KDoc there says whether it was fitted on the eight sets,
 * chosen and never varied, or borrowed. [CycleCandidate] wraps the production
 * counter; the other candidates subclass the production event machinery. The
 * eight are field-43 sets 4-6 and field-44 sets 1-5, the only deadlifts the
 * corpus holds, and the same eight every figure is scored on.
 */
/** A live rep counter over [LiveFrame]s: non-null at the frame a rep is called. */
internal interface ClosingCandidate {
    fun feed(frame: LiveFrame): RepClosed?
}

internal object ClosingFrames {
    /** A floor contact: any sample whose accelerometer magnitude exceeds this, g -- the production value. */
    val CONTACT_G: Double = DspConfig().cycleContactG

    /**
     * The frames the app's own tracker would publish for [samples], plus the
     * two raw-sample fields. The tracker is `StreamingSetTracker.forLift`, the
     * app's construction, so the clock and the acceleration are production's.
     */
    fun of(samples: List<ImuSample>, direction: LiftDirection, config: DspConfig = DspConfig()): List<LiveFrame> {
        val tracker = StreamingSetTracker.forLift(direction, config)
        val driveSign = direction.sensorToLifter * direction.concentricSign
        return samples.map { sample ->
            val live = tracker.feed(sample)
            LiveFrame(
                timeS = live.elapsedS,
                accelMps2 = live.accelMps2 * driveSign,
                accMagnitudeG = FrameTransform.accMagnitudeG(sample),
                quiet = VelocityEstimator.isQuietSample(sample, config),
            )
        }
    }

    /**
     * Lens A's contact-free check, #305: every frame from 0.05 s before to
     * 0.3 s after a sample above [CONTACT_G] is replaced by a frame that
     * carries NOTHING -- no acceleration, 1 g of magnitude so it is neither a
     * contact nor a fall, and not quiet. A candidate that only works because
     * floor-contact ringing inflates a drive, or because it reads the contact
     * itself, shows the loss here.
     *
     * An analysis transform, not a live one: it looks 0.05 s ahead.
     */
    fun contactFree(frames: List<LiveFrame>): List<LiveFrame> {
        val contacts = frames.filter { it.accMagnitudeG > CONTACT_G }.map { it.timeS }
        var k = 0
        return frames.map { frame ->
            while (k < contacts.size && contacts[k] + 0.3 < frame.timeS) k++
            val inWindow = k < contacts.size && frame.timeS >= contacts[k] - 0.05
            if (inWindow) frame.copy(accelMps2 = 0.0, accMagnitudeG = 1.0, quiet = false) else frame
        }
    }

    /** Every rep [candidate] calls over [frames], in order. */
    fun calls(candidate: ClosingCandidate, frames: List<LiveFrame>): List<RepClosed> =
        frames.mapNotNull { candidate.feed(it) }
}

/**
 * The shipped counter, and lens A's L2, driven through the [LiveFrame] harness
 * so every row of every table is measured the same way.
 *
 * `DriveImpulseCounter` with the default [LiftDirection] reads the frame's
 * acceleration unchanged, because the frame is already in the drive frame.
 * `ClosingRuleCandidateTest` asserts this wrapper reproduces the app's own
 * path call for call before any other row is read.
 */
internal class DriveImpulseRow(config: DspConfig = DspConfig()) : ClosingCandidate {
    private val counter = DriveImpulseCounter(LiftDirection(), config)

    override fun feed(frame: LiveFrame): RepClosed? {
        val call = counter.feed(LiveSetState(elapsedS = frame.timeS, accelMps2 = frame.accelMps2), 0L)
        return if (call is RepCall.Speak) RepClosed(frame.timeS, frame.timeS, frame.timeS) else null
    }

    companion object {
        /** Lens A's best-balanced relaxation on #305: threshold 0.4, minimum phase 0.20, peak 0.6, gap 1.0. */
        val L2 = DspConfig(
            driveAccelThresholdMps2 = 0.4,
            driveMinPhaseS = 0.20,
            drivePeakAccelMps2 = 0.6,
            driveMaxGapS = 1.0,
        )
    }
}
