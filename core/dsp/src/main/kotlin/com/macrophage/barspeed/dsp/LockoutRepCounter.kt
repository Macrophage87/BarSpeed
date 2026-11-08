package com.macrophage.barspeed.dsp

import kotlin.math.exp

/**
 * A live rep counter for an ECCENTRIC-FIRST lift that calls each rep at
 * LOCKOUT: an eccentric, then a concentric, called as the concentric ends.
 *
 * ## Why not [CycleRepCounter] on a squat
 *
 * [CycleRepCounter] is a deadlift rule: a drive and its brake OPEN a rep,
 * and it is CALLED once the bar is back on the floor -- a contact, a fall, a
 * stillness or the next drive. A back squat has no floor. Replayed on the
 * owner's first v0.2.2 session (2026-11-06, seven back squat sets at 45-185
 * lb), the app's counter reproduces every live count, and three mechanisms
 * account for what he reported -- *"It seemed to undercount when under very
 * low load and sometimes count the racking as a rep."*:
 *
 * - THE WALK-OUT IS A REP. Lifting the bar off the hooks is a drive met by a
 *   brake, and the first descent closes it, so the unrack is called "Rep 1"
 *   as the first real rep goes down.
 * - EVERY CALL IS A REP LATE. A real rep is closed by the NEXT descent's
 *   fall, so its number is spoken while the next rep is under way, and the
 *   last rep's number comes as the bar is racked. With the walk-out counted,
 *   two of the three 185 lb triples (sets 5 and 7) read 4, the last call on
 *   the rack.
 * - LIGHT REPS ARE DROPPED. On the empty bar a contact at the bottom of rep
 *   4 rejected it, and rep 5 was replaced by the re-rack's drive and then
 *   dropped by the rack's contact: 4 calls, one of them the walk-out, for 5
 *   reps.
 *
 * ## The rule
 *
 * Every frame, in the DRIVE frame (positive is the concentric):
 *
 * - the acceleration is clipped after a contact and smoothed exactly as
 *   [CycleRepCounter] does, with the same [DspConfig] fields;
 * - a running mean with time constant [DspConfig.lockoutBiasTauS] is taken
 *   off it, so a bias the integrator would turn into a standing velocity is
 *   gone;
 * - that is integrated into a LEAKY velocity, decaying with
 *   [DspConfig.lockoutVelocityTauS]. Both decays are exponentials in the
 *   frame interval rather than a forward step. It is not the bar's
 *   velocity: it forgets in seconds, so it cannot run away the way the
 *   tracker's does.
 *
 * Then a three-state machine. IDLE until the leaky velocity falls under
 * -[DspConfig.lockoutDescentMps] -- the eccentric has begun; DOWN until it
 * rises over [DspConfig.lockoutAscentMps] -- the concentric has begun; UP
 * until it falls back under [DspConfig.lockoutEndMps] -- LOCKOUT. At lockout
 * the cycle is CALLED if its RISE, the positive part of the leaky velocity
 * integrated from the eccentric's start, reaches [DspConfig.lockoutMinRise];
 * either way the machine is IDLE again.
 *
 * A concentric with no eccentric before it -- the unrack -- never leaves
 * IDLE. A step, a settle or a re-rack that dips and recovers rises too
 * little to be called. Nothing waits for a floor, so the number is spoken at
 * the top of the rep it belongs to.
 *
 * ## What it was measured on, and what it costs
 *
 * Fitted and swept (`LockoutSweepTest`) on every untempo'd back squat the
 * owner has counted or bounded, on both units: the 2026-11-06 session
 * (`SquatLockoutFieldTest`), field-46's five straight sets (#335) and
 * field-backsquat-99hz-6rep. It calls 5, 5, 4, 2, 3, 3, 3 on the 2026-11-06
 * sets -- equal to the owner's count on sets 1, 4, 5, 6 and 7; sets 2 and 3
 * he did not settle -- where [CycleRepCounter] called 4, 6, 5, 1, 4, 3, 4;
 * and 5, 5, 4, 4, 5 on field-46, inside "at least 4, likely 5", where
 * [CycleRepCounter] called 6, 4, 5, 5, 5.
 *
 * - ONE LIFTER'S BACK SQUAT. Every constant is fitted there, and the app
 *   arms it on squats alone (`LiftDirection.squat`, the owner's choice).
 *   Front, box, goblet, split and the other squats reach it by name with no
 *   capture behind them. Bench press and Romanian deadlift do not reach it:
 *   on their tempo'd captures it does WORSE than [CycleRepCounter], a
 *   summed miss of 12 against 6, the slow 3-s eccentrics rising too little
 *   (`LockoutCorpusTest`).
 * - THE RISE MARGIN IS A FEW HUNDREDTHS. A re-rack that rises a little more,
 *   or a grinding rep that rises a little less, than this session's will be
 *   miscounted.
 * - THE LIVE BAR OFTEN HAS NO FIGURE. The call is made at lockout, and
 *   `LiveRepFigures` matches it against the tracker's drive runs at that
 *   sample, before the tracker has closed the concentric's run. A one-off
 *   replay of the 2026-11-06 role a streams in review gave a figure for 3 of
 *   25 calls, against 8 of 27 under [CycleRepCounter]; no test pins those
 *   figures. The count is unaffected.
 * - A DESCENT THAT STOPS CAN BE CALLED. Taking the running mean off makes
 *   a descent with no concentric after it read as a descent and a rise, so
 *   a squat dumped onto the safeties, or a sit that stays on a box, can be
 *   counted -- at ordinary squat speed, not only a fast one
 *   (`LockoutRepCounterTest`). The failure this counter fixes, the rack
 *   counted, has this second door.
 * - A REP CUT SHORT IS NOT CALLED until its concentric ends.
 * - FRAMES, NOT SECONDS. The smoothing window is
 *   [DspConfig.cycleSmoothFrames] frames, 0.1 s at the 100 Hz every fitted
 *   stream ran at, but 1 s at 10 Hz, where synthetic reviews dropped reps.
 *   And through the tracker the frame interval is its reconstructed uniform
 *   clock, so a BLE dropout is spliced out of time rather than decayed: a
 *   rep whose frames are lost can be lost with them. [CycleRepCounter]
 *   shares both.
 */
class LockoutRepCounter(
    direction: LiftDirection = LiftDirection(),
    config: DspConfig = DspConfig(),
) : LiveRepCounter {
    /** Sensor frame to drive frame: positive is the direction the lifter drives. */
    private val driveSign: Double = direction.sensorToLifter * direction.concentricSign

    private val rule = LockoutRule(config)

    /** What this counter has called; 0 before the first rep. */
    val called: Int
        get() = rule.called

    override fun feed(live: LiveSetState, timestampMs: Long): RepCall {
        val magnitude = live.accMagnitudeG ?: return RepCall.Hold
        val quiet = live.quiet ?: return RepCall.Hold
        val window = rule.feed(LiveFrame(live.elapsedS, live.accelMps2 * driveSign, magnitude, quiet))
            ?: return RepCall.Hold
        return RepCall.Speak(rule.called, timestampMs, window)
    }
}

/**
 * [LockoutRepCounter]'s rule on drive-frame frames; see its KDoc. Internal
 * so a test can feed it frames without a tracker.
 */
internal class LockoutRule(private val config: DspConfig) {
    private enum class State { IDLE, DOWN, UP }

    private val window = ArrayDeque<Double>()
    private var windowSum = 0.0
    private var lastContactS = Double.NEGATIVE_INFINITY
    private var prevTimeS = Double.NaN
    private var bias = Double.NaN
    private var velocity = 0.0
    private var state = State.IDLE
    private var cycleStartS = 0.0
    private var ascentStartS = 0.0
    private var rise = 0.0

    /** Reps this rule has called. */
    var called: Int = 0
        private set

    /**
     * One frame: the concentric of the rep called on it, from the leaky
     * velocity's rise past [DspConfig.lockoutAscentMps] to this frame, or
     * null where nothing is called.
     */
    fun feed(frame: LiveFrame): DriveWindow? {
        val t = frame.timeS
        val dt = if (prevTimeS.isNaN()) 0.0 else t - prevTimeS
        prevTimeS = t
        if (frame.accMagnitudeG > config.cycleContactG) lastContactS = t
        val clipped = if (t - lastContactS <= config.cycleContactWindowS) {
            frame.accelMps2.coerceIn(-config.cycleClipMps2, config.cycleClipMps2)
        } else {
            frame.accelMps2
        }
        window.addLast(clipped)
        windowSum += clipped
        if (window.size > config.cycleSmoothFrames) windowSum -= window.removeFirst()
        val smoothed = windowSum / window.size
        bias = if (bias.isNaN()) smoothed else bias + (smoothed - bias) * (1.0 - exp(-dt / config.lockoutBiasTauS))
        velocity = velocity * exp(-dt / config.lockoutVelocityTauS) + (smoothed - bias) * dt
        return step(t, dt)
    }

    private fun step(t: Double, dt: Double): DriveWindow? {
        if (state == State.IDLE) {
            if (velocity < -config.lockoutDescentMps) {
                state = State.DOWN
                cycleStartS = t
                rise = 0.0
            }
            return null
        }
        if (velocity > 0) rise += velocity * dt
        if (state == State.DOWN) {
            if (velocity > config.lockoutAscentMps) {
                state = State.UP
                ascentStartS = t
            }
            return null
        }
        if (velocity >= config.lockoutEndMps) return null
        state = State.IDLE
        if (rise < config.lockoutMinRise) return null
        called++
        return DriveWindow(ascentStartS, t)
    }
}
