package com.macrophage.barspeed.dsp

/**
 * A live rep counter that calls a rep only once the whole cycle is over: the
 * drive, and then the bar back at the floor. Issue #305's candidate (b).
 *
 * ## The rule
 *
 * - OPEN: a DRIVE met by its BRAKE. A drive is a run of the smoothed
 *   drive-frame acceleration above [DspConfig.cycleRunThresholdMps2] that GAINS
 *   at least [DspConfig.cycleDriveGainMps] of velocity (its integral) over at
 *   least [DspConfig.cycleMinRunS]; a brake is a negative run that loses at
 *   least [DspConfig.cycleBrakeLossMps], beginning within
 *   [DspConfig.cycleMaxGapS] of the drive's end. The drive ARMS at the END of
 *   that brake run, which on a heavy dead-stop pull spans the brake and the
 *   lowering, so arming can come seconds after the drive. An armed drive is a
 *   PENDING rep; nothing is spoken yet.
 * - THE DESCENT GATE: a pending rep can be CALLED only once the negative part
 *   of the smoothed drive-frame acceleration, integrated from the brake run's
 *   start, reaches [DspConfig.cycleDescentMps] in magnitude. That is an
 *   acceleration integral, not a measured bar velocity or height.
 * - CLOSE, from [DspConfig.cycleMinCycleS] after the drive ENDED: the first
 *   floor event -- a CONTACT, a FALL, a STILL -- or the next armed drive.
 *   - descent gate met: CALLED, and the running total is spoken at that
 *     sample.
 *   - descent gate not met: a STILL keeps the rep pending; a CONTACT, a FALL
 *     or the next armed drive REJECTS it, and nothing is spoken.
 * - BEFORE [DspConfig.cycleMinCycleS]: a CONTACT or a FALL REJECTS the pending
 *   rep; a STILL is ignored.
 * - THE NEXT DRIVE closes the pending rep only if it STARTS at least
 *   [DspConfig.cycleMinCycleS] after the old drive's END -- its start, not
 *   when it arms. Otherwise it REPLACES the pending rep and nothing is spoken
 *   for the one it replaced.
 * - A CONTACT or FALL within [DspConfig.cycleMinCycleS] of a drive that has not
 *   ARMED yet drops that drive before it can arm. On field-44 set 5 that, and
 *   not the pending-rep rule, is what refuses the failed pull: its drive ends at
 *   17.72 s, the bar's 5-frame mean magnitude falls under
 *   [DspConfig.cycleFallG] 0.49 s later and the floor contact follows at
 *   0.69 s, both before the brake run that would arm it has ended
 *   (`ClosingRuleCandidateTest`'s event-stream pin).
 *
 * The three floor events:
 *
 * - CONTACT: a sample whose accelerometer magnitude exceeds
 *   [DspConfig.cycleContactG], at most one per
 *   [DspConfig.cycleContactDebounceS]. For [DspConfig.cycleContactWindowS]
 *   after a contact the acceleration is clipped to
 *   +-[DspConfig.cycleClipMps2], so the ringing of a floor strike cannot
 *   become a drive -- causal, it looks at nothing ahead.
 * - FALL: the [DspConfig.cycleFallFrames]-frame mean magnitude under
 *   [DspConfig.cycleFallG] -- the bar in or near free fall, at most once per
 *   [DspConfig.cycleFallRefireS]. Field-44 set 5's grip failure reaches
 *   0.14 g. It also fires in the ringing after a contact.
 * - STILL: a run of `VelocityEstimator.isQuietSample` samples reaching
 *   [DspConfig.cycleStillS].
 *
 * ## Why a whole cycle, and what it costs
 *
 * [DriveImpulseCounter] calls at the brake straight after the pull, and at
 * 111 and 120 kg on field-44 it called nothing: every dead-stop pull there
 * stays above its acceleration threshold for too short a time. Measuring the
 * drive by the velocity it GAINS rather than by its peak lets a slow heavy pull
 * open a rep, and the failed pull -- which gains 0.23 m/s, more than set 5's
 * first completed rep at 0.16 -- is then refused by TIME rather than by size:
 * the bar is back on the floor too soon for the pull to have been completed.
 *
 * On the eight deadlift sets the corpus holds (field-43 sets 4-6, field-44 sets
 * 1-5) this rule counts 35 of 36 completed reps with one phantom and does not
 * call the failed pull; `ClosingRuleCandidateTest` pins the table. What that
 * costs, measured there and not argued here:
 *
 * - THE NUMBER COMES AS THE BAR LANDS. Median 1.16 s after the matched batch
 *   window ends, against -0.27 s for [DriveImpulseCounter].
 * - ON A LIGHT SOFT LANDING IT CAN COME A REP LATE. On field-44 set 1 reps 1
 *   and 3 land with no event this rule reads, so each is closed by the next
 *   rep's armed drive -- spoken at that rep's brake -- and that rep's own
 *   number follows about 1.1-1.2 s later.
 * - IT STILL MISSES field-43 set 6's rep 4, and it calls that set's set-up
 *   pull, which [DriveImpulseCounter] excludes only by its peak term.
 * - A FAILED PULL HELD AND LOWERED PAST [DspConfig.cycleMinCycleS] MAY BE
 *   CALLED. Nothing in the corpus holds one; unmeasured.
 *
 * Every constant is fitted on, chosen on or borrowed for those eight sets --
 * each [DspConfig] KDoc says which -- and one lifter's dead-stop deadlifts at
 * two mounts are all they describe. Heavy touch-and-go is unmeasured.
 *
 * ## The frame
 *
 * [LiveSetState.accelMps2] is published in the SENSOR frame; this maps it
 * through `sensorToLifter * concentricSign` as [DriveImpulseCounter] does, so
 * the rule reads the DRIVE frame. [LiveSetState.accMagnitudeG] and
 * [LiveSetState.quiet] are facts about the raw sample and need no frame.
 *
 * A state carrying no [LiveSetState.accMagnitudeG] or no [LiveSetState.quiet]
 * -- one no sample produced -- is not a frame and is skipped.
 */
class CycleRepCounter internal constructor(
    direction: LiftDirection,
    private val rule: CycleRule,
) : LiveRepCounter {
    constructor(
        direction: LiftDirection = LiftDirection(),
        config: DspConfig = DspConfig(),
    ) : this(direction, CycleRule(config))

    /** Sensor frame to drive frame: positive is the direction the lifter drives. */
    private val driveSign: Double = direction.sensorToLifter * direction.concentricSign

    /** What this counter has called; 0 before the first rep. */
    val called: Int
        get() = rule.called

    /** The rep the last fed sample closed, or null -- the drive a call closes, for the design harness. */
    internal var lastClosed: RepClosed? = null
        private set

    /**
     * One published live sample: the running total when a rep closes on it,
     * else [RepCall.Hold].
     *
     * [timestampMs] is the ARRIVAL stamp, carried through to [RepCall.Speak]
     * unchanged; the rule runs on [LiveSetState.elapsedS], the reconstructed
     * clock, for [DriveImpulseCounter]'s reason.
     */
    override fun feed(live: LiveSetState, timestampMs: Long): RepCall {
        val magnitude = live.accMagnitudeG ?: return RepCall.Hold
        val quiet = live.quiet ?: return RepCall.Hold
        val before = rule.called
        lastClosed = rule.feed(LiveFrame(live.elapsedS, live.accelMps2 * driveSign, magnitude, quiet))
        return if (rule.called > before) RepCall.Speak(rule.called, timestampMs) else RepCall.Hold
    }
}

/** One frame the drive/brake/floor machinery reads. */
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
 * One rep a rule called: the instant it spoke, and the drive the call closes.
 * The design harness scores the DRIVE against a batch window, not the instant,
 * because a rule that closes at the floor speaks inside the next rep's window.
 */
internal data class RepClosed(val atS: Double, val driveStartS: Double, val driveEndS: Double)

/**
 * The drive, brake and floor events [CycleRepCounter]'s KDoc describes, stated
 * once. Subclasses decide what closes a rep: [CycleRule] is the one production
 * runs; the other closing rules issue #305's design round measured subclass
 * this on the test classpath.
 */
internal abstract class DriveBrakeTracker(protected val config: DspConfig) {
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

    /** The qualified drive awaiting its brake, if any. */
    protected var drive: Drive? = null

    /** The smoothed drive-frame acceleration of the current frame. */
    protected var smoothed = 0.0
        private set

    /** The frame's clipped, unsmoothed acceleration, for rules that integrate. */
    protected var clipped = 0.0
        private set

    protected var dt = 0.0
        private set

    open fun feed(frame: LiveFrame): RepClosed? {
        val t = frame.timeS
        dt = if (prevTimeS.isNaN()) 0.0 else t - prevTimeS
        prevTimeS = t
        quietS = if (frame.quiet) quietS + dt else 0.0
        val contact = frame.accMagnitudeG > config.cycleContactG
        if (contact) lastContactS = t
        clipped = if (t - lastContactS <= config.cycleContactWindowS) {
            frame.accelMps2.coerceIn(-config.cycleClipMps2, config.cycleClipMps2)
        } else {
            frame.accelMps2
        }
        window.addLast(clipped)
        windowSum += clipped
        if (window.size > config.cycleSmoothFrames) windowSum -= window.removeFirst()
        smoothed = windowSum / window.size
        magWindow.addLast(frame.accMagnitudeG)
        magSum += frame.accMagnitudeG
        if (magWindow.size > config.cycleFallFrames) magSum -= magWindow.removeFirst()
        val magMean = if (contact) frame.accMagnitudeG else magSum / magWindow.size

        beforeRuns(t)
        var called = updateRuns(t)
        val event = when {
            contact && t - lastContactEventS > config.cycleContactDebounceS -> {
                lastContactEventS = t
                FloorEvent.CONTACT
            }
            magMean < config.cycleFallG && t - lastFallEventS > config.cycleFallRefireS -> {
                lastFallEventS = t
                FloorEvent.FALL
            }
            frame.quiet && quietS >= config.cycleStillS && quietS - dt < config.cycleStillS -> FloorEvent.STILL
            else -> null
        }
        if (event != null) called = onEvent(event, t) ?: called
        return called
    }

    private fun updateRuns(t: Double): RepClosed? {
        val sign = when {
            smoothed > config.cycleRunThresholdMps2 -> 1
            smoothed < -config.cycleRunThresholdMps2 -> -1
            else -> 0
        }
        var called: RepClosed? = null
        if (runSign != 0 && sign != runSign) {
            val duration = t - runStartS
            if (runSign == 1 && runIntegral >= config.cycleDriveGainMps && duration >= config.cycleMinRunS) {
                drive = Drive(runStartS, t, runIntegral)
            } else if (runSign == -1 && -runIntegral >= config.cycleBrakeLossMps && duration >= config.cycleMinRunS) {
                val armed = drive
                if (armed != null && runStartS - armed.endS <= config.cycleMaxGapS) {
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

/**
 * [CycleRepCounter]'s rule: the one copy of it, which the design harness wraps
 * rather than restates.
 *
 * Open, with two hooks and one switch, only so that `ClosingRuleCandidateTest`
 * can measure the variants issue #305's design round compared -- a
 * set-relative drive, a height floor, and the rule without its fall rejection
 * -- against THIS code rather than against a copy of it. Production builds it
 * with the defaults and overrides nothing.
 *
 * @param fallRejects false is an ABLATION the harness measures: a FALL sooner
 *   than [DspConfig.cycleMinCycleS] then neither rejects a pending rep nor
 *   drops an unarmed drive. Production never sets it.
 */
internal open class CycleRule(
    config: DspConfig,
    private val fallRejects: Boolean = true,
) : DriveBrakeTracker(config) {
    private class Pending(val drive: Drive, var negativeMps: Double)

    private var pending: Pending? = null

    /** Reps this rule has called. */
    var called: Int = 0
        private set

    /** The drive of the pending rep, or null. */
    protected val pendingDrive: Drive?
        get() = pending?.drive

    /** Whether [armed] may open a rep at all. Production admits every armed drive. */
    protected open fun admits(armed: Drive): Boolean = true

    /** Whether a rep past the descent gate is CALLED rather than rejected. Production calls every one. */
    protected open fun accepts(closing: Drive): Boolean = true

    override fun beforeRuns(t: Double) {
        val open = pending ?: return
        if (smoothed < 0) open.negativeMps += smoothed * dt
    }

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
        if (!admits(armed)) return null
        var closed: RepClosed? = null
        val old = pending
        val startsLate = old != null && armed.startS - old.drive.endS >= config.cycleMinCycleS
        if (old != null && startsLate && close(old, null) == Outcome.CALLED) closed = closing(old.drive, t)
        pending = Pending(armed, brakeIntegral)
        return closed
    }

    override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
        var closed: RepClosed? = null
        val open = pending
        val ends = event == FloorEvent.CONTACT || (event == FloorEvent.FALL && fallRejects)
        if (open != null) {
            if (t - open.drive.endS >= config.cycleMinCycleS) {
                val outcome = close(open, event)
                if (outcome != Outcome.KEPT) pending = null
                if (outcome == Outcome.CALLED) closed = closing(open.drive, t)
            } else if (ends) {
                pending = null
            }
        }
        val waiting = drive
        val soon = waiting != null && (t - waiting.endS).let { it > 0.0 && it < config.cycleMinCycleS }
        if (ends && soon) drive = null
        return closed
    }

    private enum class Outcome { CALLED, KEPT, REJECTED }

    /** [event] null is the next armed drive closing the pending rep. */
    private fun close(open: Pending, event: FloorEvent?): Outcome {
        if (open.negativeMps > -config.cycleDescentMps) {
            return if (event == FloorEvent.STILL) Outcome.KEPT else Outcome.REJECTED
        }
        if (!accepts(open.drive)) return Outcome.REJECTED
        called++
        return Outcome.CALLED
    }
}
