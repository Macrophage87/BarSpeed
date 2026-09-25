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
 * One [LiveFrame] per sample: the tracker's own clock and drive-frame
 * acceleration -- the two fields `DriveImpulseCounter` already reads off
 * `LiveSetState` -- plus two facts about the RAW sample that `LiveSetState`
 * does not publish today: its accelerometer magnitude and whether
 * `VelocityEstimator.isQuietSample` holds for it. So every candidate here but
 * the two drive-impulse rows needs `LiveSetState` (or the counter's `feed`) to
 * carry those two fields before it could ship. That is a production change and
 * it is the implement round's, not this one's.
 *
 * ## Every constant here is fitted, and fitted on eight sets
 *
 * The eight are field-43 sets 4-6 and field-44 sets 1-5, the only deadlifts
 * the corpus holds, and the same eight every figure is scored on. Nothing here
 * is a survey value. Each constant says what it was chosen against.
 */
internal data class LiveFrame(
    /** `LiveSetState.elapsedS`: the reconstructed clock, s. */
    val timeS: Double,
    /** `LiveSetState.accelMps2` in the DRIVE frame (x `sensorToLifter` x `concentricSign`), m/s^2. */
    val accelMps2: Double,
    /** The raw sample's accelerometer magnitude, g. */
    val accMagnitudeG: Double,
    /** `VelocityEstimator.isQuietSample` on the raw sample. */
    val quiet: Boolean,
)

/**
 * One rep a candidate called: the instant it spoke, and the drive the call
 * closes. Scoring matches the DRIVE to a batch window, not the instant,
 * because a candidate that closes on the floor speaks up to 3 s after the
 * pull -- inside the next rep's window, or a set-up pull's floor inside rep
 * 1's. For the two drive-impulse rows, which call at the brake right after
 * their drive, the drive is the call instant itself, which is how #301's
 * harness scored them.
 */
internal data class RepClosed(val atS: Double, val driveStartS: Double, val driveEndS: Double)

/** A live rep counter over [LiveFrame]s: non-null at the frame a rep is called. */
internal interface ClosingCandidate {
    fun feed(frame: LiveFrame): RepClosed?
}

internal object ClosingFrames {
    /** A floor contact: any sample whose accelerometer magnitude exceeds this, g. */
    const val CONTACT_G = 4.0

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

/**
 * The drive/brake/floor events every closing candidate below is built from,
 * stated once.
 *
 * ## Drive and brake, by velocity gained rather than by peak
 *
 * The acceleration is clipped to +-[clipMps2] for [contactWindowS] after a
 * floor contact -- causal, so a live counter can do it -- and smoothed over
 * [smoothFrames] frames. A RUN is a stretch of one sign beyond +-[runThreshold].
 * A DRIVE is a positive run that gains at least [driveGainMps] of velocity
 * (its integral) over at least [minRunS]; a BRAKE is a negative run that loses
 * at least [brakeLossMps], beginning within [maxGapS] of the drive's end.
 *
 * Why an integral and not a peak: a pull at 111-120 kg rises above 1.0 m/s^2
 * for 0.01-0.10 s (#305's gate table) but still gains 0.16-0.39 m/s over its
 * run. The velocity a pull gains is what the pull IS; the peak is a property of
 * how the gain is shaped in time.
 *
 * ## The three floor events
 *
 * - CONTACT: a sample above [ClosingFrames.CONTACT_G], at most one per
 *   [contactDebounceS].
 * - FALL: the [fallFrames]-frame mean accelerometer magnitude below [fallG] --
 *   the bar in or near free fall. Field-44 set 5's grip failure reaches 0.14 g.
 * - STILL: a run of quiet samples reaching [stillS].
 */
internal data class CycleParams(
    /** m/s^2. Fitted: low enough that set 5's 120 kg pulls form a run at all. */
    val runThreshold: Double = 0.15,
    /** m/s. Fitted: under the 0.16 m/s field-44 set 5 rep 1 gains. */
    val driveGainMps: Double = 0.12,
    /** m/s. */
    val brakeLossMps: Double = 0.12,
    /** s. */
    val minRunS: Double = 0.15,
    /** s. */
    val maxGapS: Double = 1.5,
    /** Frames of moving mean on the acceleration: 0.1 s at the corpus's ~100 Hz. */
    val smoothFrames: Int = 10,
    /** m/s^2. */
    val clipMps2: Double = 1.0,
    /** s. */
    val contactWindowS: Double = 0.3,
    /** s. */
    val contactDebounceS: Double = 0.5,
    /** g. Fitted: every completed rep's lowering stays above it, the failed pull does not. */
    val fallG: Double = 0.4,
    val fallFrames: Int = 5,
    /** s: `DspConfig.minStationaryS`. */
    val stillS: Double = 0.3,
    /**
     * The shortest a completed rep is assumed to take from the drive's end to
     * the bar reaching the floor again, s. Fitted: the next CONTACT comes 0.68 s
     * after the failed pull's drive ends, and 1.25 s or later after the drive of
     * every completed rep that arms (35 of 36; `ClosingRuleCandidateTest`'s
     * lockout probe prints both). A contact is a proxy for the floor, not a
     * measurement of it.
     */
    val minCycleS: Double = 1.2,
    /**
     * The magnitude the negative part of the smoothed drive-frame acceleration
     * must reach, integrated from the brake run's start to the closing event,
     * before a rep can be CALLED, m/s. An acceleration integral, not a measured
     * bar velocity. Until it is reached a STILL keeps the rep pending, and a
     * CONTACT, a FALL or the next armed drive REJECTS it ([CycleCandidate]).
     * Fitted: completed reps 1.38-3.1, the two set-down phantoms it removes
     * 0.35 and 0.51. Null switches the clause off.
     */
    val descentMps: Double? = 0.9,
    /** Whether a FALL too soon after the drive rejects the attempt. */
    val fallRejects: Boolean = true,
)

/** The event machinery [CycleParams] describes; each candidate below decides what closes a rep. */
internal abstract class DriveBrakeTracker(protected val p: CycleParams) : ClosingCandidate {
    private val window = ArrayDeque<Double>()
    private var windowSum = 0.0
    private val magWindow = ArrayDeque<Double>()
    private var magSum = 0.0
    private var lastContactS = Double.NEGATIVE_INFINITY
    private var lastContactEventS = Double.NEGATIVE_INFINITY
    private var lastFallEventS = Double.NEGATIVE_INFINITY
    private var prevTimeS = Double.NaN
    private var quietS = 0.0

    private var runSign = 0
    private var runStartS = 0.0
    private var runIntegral = 0.0

    /** A qualified drive awaiting its brake: start, end, velocity gained. */
    protected data class Drive(val startS: Double, val endS: Double, val gainMps: Double)

    protected var drive: Drive? = null

    /** The smoothed drive-frame acceleration of the current frame. */
    protected var smoothed = 0.0
        private set

    /** The frame's clipped, unsmoothed acceleration, for candidates that integrate. */
    protected var clipped = 0.0
        private set

    protected var dt = 0.0
        private set

    override fun feed(frame: LiveFrame): RepClosed? {
        val t = frame.timeS
        dt = if (prevTimeS.isNaN()) 0.0 else t - prevTimeS
        prevTimeS = t
        quietS = if (frame.quiet) quietS + dt else 0.0
        val contact = frame.accMagnitudeG > ClosingFrames.CONTACT_G
        if (contact) lastContactS = t
        clipped = if (t - lastContactS <= p.contactWindowS) {
            frame.accelMps2.coerceIn(-p.clipMps2, p.clipMps2)
        } else {
            frame.accelMps2
        }
        window.addLast(clipped)
        windowSum += clipped
        if (window.size > p.smoothFrames) windowSum -= window.removeFirst()
        smoothed = windowSum / window.size
        magWindow.addLast(frame.accMagnitudeG)
        magSum += frame.accMagnitudeG
        if (magWindow.size > p.fallFrames) magSum -= magWindow.removeFirst()
        val magMean = if (contact) frame.accMagnitudeG else magSum / magWindow.size

        beforeRuns(t)
        var called = updateRuns(t)
        val event = when {
            contact && t - lastContactEventS > p.contactDebounceS -> {
                lastContactEventS = t
                FloorEvent.CONTACT
            }
            magMean < p.fallG && t - lastFallEventS > 0.3 -> {
                lastFallEventS = t
                FloorEvent.FALL
            }
            frame.quiet && quietS >= p.stillS && quietS - dt < p.stillS -> FloorEvent.STILL
            else -> null
        }
        if (event != null) called = onEvent(event, t) ?: called
        return called
    }

    private fun updateRuns(t: Double): RepClosed? {
        val sign = when {
            smoothed > p.runThreshold -> 1
            smoothed < -p.runThreshold -> -1
            else -> 0
        }
        var called: RepClosed? = null
        if (runSign != 0 && sign != runSign) {
            val duration = t - runStartS
            if (runSign == 1 && runIntegral >= p.driveGainMps && duration >= p.minRunS) {
                drive = Drive(runStartS, t, runIntegral)
            } else if (runSign == -1 && -runIntegral >= p.brakeLossMps && duration >= p.minRunS) {
                val armed = drive
                if (armed != null && runStartS - armed.endS <= p.maxGapS) {
                    called = onArmed(armed, runIntegral, t)
                    drive = null
                }
            }
            runSign = 0
        }
        if (sign != 0) {
            if (runSign == 0) {
                runSign = sign
                runStartS = t
                runIntegral = 0.0
            }
            runIntegral += smoothed * dt
        }
        return called
    }

    /** Called every frame before the run bookkeeping. */
    protected open fun beforeRuns(t: Double) = Unit

    /** A drive met its brake: the rep is OPEN. Non-null if that alone closes an earlier rep. */
    protected abstract fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed?

    /** A floor event. Non-null if it closes a rep. */
    protected abstract fun onEvent(event: FloorEvent, t: Double): RepClosed?

    /** The call closing [closed] at [t]. */
    protected fun closing(closed: Drive, t: Double) = RepClosed(t, closed.startS, closed.endS)

    enum class FloorEvent { CONTACT, FALL, STILL }
}
