package com.macrophage.barspeed.dsp

/**
 * (b) FULL CYCLE, AS PRODUCTION RUNS IT -- a thin wrapper over
 * [CycleRepCounter], whose KDoc states the rule. Issue #305's candidate (b),
 * with (e)'s two combinations and the fall-rejection ablation as switches.
 *
 * Every frame goes through the production counter's own `feed`, as a
 * [LiveSetState] in the drive frame with the default [LiftDirection] -- the
 * frame is already in the drive frame, so the mapping is the identity -- and
 * a call is scored by the drive it closes ([CycleRepCounter.lastClosed]). So
 * the (b) rows, the sweeps and the ablations measure the code that ships, not
 * a copy of it; the constants are [DspConfig]'s `cycle*` fields, and a sweep
 * varies them there.
 *
 * @param relativeDrive (e2) a SET-RELATIVE drive: a drive arms only if it
 *   gains at least this fraction of the median gain of the reps already called
 *   in the set. Null is the production rule.
 * @param minHeightM (e1) CYCLE + HEIGHT: a rep past the descent gate is still
 *   REJECTED unless it rose this far, integrated from the drive's start to the
 *   first instant its velocity returns to zero (candidate (c)'s window). Null
 *   is the production rule.
 * @param fallRejects false is the ablation in which a FALL sooner than
 *   `cycleMinCycleS` rejects nothing.
 */
internal class CycleCandidate(
    config: DspConfig = DspConfig(),
    relativeDrive: Double? = null,
    minHeightM: Double? = null,
    fallRejects: Boolean = true,
) : ClosingCandidate {
    private val counter = CycleRepCounter(
        LiftDirection(),
        if (relativeDrive == null && minHeightM == null) {
            CycleRule(config, fallRejects)
        } else {
            CycleVariant(config, relativeDrive, minHeightM, fallRejects)
        },
    )

    override fun feed(frame: LiveFrame): RepClosed? {
        val live = LiveSetState(
            elapsedS = frame.timeS,
            accelMps2 = frame.accelMps2,
            accMagnitudeG = frame.accMagnitudeG,
            quiet = frame.quiet,
        )
        return if (counter.feed(live, 0L) is RepCall.Speak) counter.lastClosed else null
    }
}

/**
 * (e1) and (e2): the production [CycleRule] with one of its two hooks
 * overridden, so neither variant restates the rule it modifies.
 */
private class CycleVariant(
    config: DspConfig,
    private val relativeDrive: Double?,
    private val minHeightM: Double?,
    fallRejects: Boolean,
) : CycleRule(config, fallRejects) {
    private val calledGains = mutableListOf<Double>()
    private val recent = RecentAccel()
    private var height: ShortWindowHeight? = null

    override fun beforeRuns(t: Double) {
        recent.add(t, clipped)
        super.beforeRuns(t)
        if (pendingDrive != null) height?.step(t, clipped)
    }

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
        val closed = super.onArmed(armed, brakeIntegral, t)
        if (pendingDrive == armed) height = minHeightM?.let { ShortWindowHeight.from(recent, armed.startS) }
        return closed
    }

    override fun admits(armed: Drive): Boolean {
        val scale = relativeDrive ?: return true
        return calledGains.isEmpty() || armed.gainMps >= scale * median(calledGains)
    }

    override fun accepts(closing: Drive): Boolean {
        val floor = minHeightM
        if (floor != null && (height?.peakM ?: 0.0) < floor) return false
        calledGains += closing.gainMps
        return true
    }
}

/**
 * (a) LOCKOUT, AS MODELLED HERE -- meant to close on a stillness at the top,
 * but it can only look for that stillness after the drive has ARMED.
 *
 * - OPEN: a drive met by its brake, as (b): the rep is pending only from the
 *   END of the brake run. A drive that arms more than [maxLockS] after its own
 *   end is dropped on the next frame, so it can never be called, whatever the
 *   bar did. On field-44 set 5 both completed reps arm 2.39 s and 2.09 s after
 *   their drives end (the event-stream pin in `ClosingRuleCandidateTest`), so
 *   (a) cannot call either. On all eight sets, 12 completed reps finish a
 *   0.15 s still between the drive's end and the arming, which (a) never
 *   sees (`ClosingRuleCandidateTest`'s lockout probe).
 * - CLOSE: the first floor event after arming. If it is a STILL -- a run of
 *   quiet samples (`isQuietSample`: |a| within 0.05 g of 1 g, gyro under
 *   10 deg/s) reaching [lockoutS] -- and that run reaches [lockoutS] within
 *   [maxLockS] of the drive's end, the rep is CALLED. A quiet run that reached
 *   [lockoutS] before arming is not seen. Nothing checks that the stillness
 *   is at the top rather than on the floor.
 * - Otherwise nothing is spoken: a CONTACT or FALL first, the next armed drive
 *   replacing it, or [maxLockS] passing.
 *
 * Its row therefore measures this arming-gated model, not a lockout rule. It
 * is NOT evidence against a counter that looks for the top's stillness
 * straight after the drive; no such model is measured here.
 */
internal class LockoutCandidate(
    lockoutS: Double = 0.15,
    private val maxLockS: Double = 2.0,
    config: DspConfig = DspConfig(cycleStillS = lockoutS),
) : DriveBrakeTracker(config),
    ClosingCandidate {
    private var pending: Drive? = null

    override fun beforeRuns(t: Double) {
        val open = pending ?: return
        if (t - open.endS > maxLockS) pending = null
    }

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
        pending = armed
        return null
    }

    override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
        val open = pending ?: return null
        pending = null
        return if (event == FloorEvent.STILL && t - open.endS <= maxLockS) closing(open, t) else null
    }
}

/**
 * (d) FLOOR CONTACT AS THE BOUNDARY -- the rep closes when the bar meets the
 * floor.
 *
 * - OPEN: a drive met by its brake, as (b).
 * - CLOSE: the next CONTACT, however soon.
 * - AN ATTEMPT THAT NEVER COMPLETES: called, if it ends on the floor hard
 *   enough. That is the point of measuring it: a contact says the bar came
 *   down, not that it first went all the way up.
 */
internal class ContactCandidate(config: DspConfig = DspConfig()) :
    DriveBrakeTracker(config),
    ClosingCandidate {
    private var pending: Drive? = null

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
        pending = armed
        return null
    }

    override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
        val open = pending
        if (event != FloorEvent.CONTACT || open == null) return null
        pending = null
        return closing(open, t)
    }
}

/**
 * (c) SHORT-WINDOW INTEGRATION -- the rep closes at the top if the bar rose
 * far enough to be one.
 *
 * - OPEN: a qualified drive (no brake needed): integration starts at the
 *   drive's first sample with velocity taken as zero -- the bar leaving the
 *   floor.
 * - CLOSE: the first instant the integrated velocity returns to zero, if the
 *   height reached is at least [minHeightM] and the window is under
 *   [maxWindowS]. Drift is bounded by the window, not by the set.
 * - AN ATTEMPT THAT NEVER COMPLETES: its height is short of [minHeightM], or
 *   it turns into a fall before the velocity ever returns to zero gently --
 *   either way no call.
 */
internal class HeightCandidate(
    private val minHeightM: Double = 0.25,
    private val maxWindowS: Double = 3.0,
    config: DspConfig = DspConfig(),
) : DriveBrakeTracker(config),
    ClosingCandidate {
    private val recent = RecentAccel()
    private var open: ShortWindowHeight? = null
    private var openDrive: Drive? = null
    private var lastDriveStartS = Double.NaN

    override fun beforeRuns(t: Double) {
        recent.add(t, clipped)
        val window = open
        val qualified = drive
        if (window == null && qualified != null && qualified.startS != lastDriveStartS) {
            lastDriveStartS = qualified.startS
            open = ShortWindowHeight.from(recent, qualified.startS)
            openDrive = qualified
        }
    }

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? = null

    override fun onEvent(event: FloorEvent, t: Double): RepClosed? = null

    override fun feed(frame: LiveFrame): RepClosed? {
        super.feed(frame)
        val window = open ?: return null
        val qualified = openDrive ?: return null
        window.step(frame.timeS, clipped)
        if (frame.timeS - window.startS > maxWindowS) {
            open = null
            return null
        }
        if (!window.topped) return null
        open = null
        return if (window.peakM >= minHeightM) closing(qualified, frame.timeS) else null
    }
}

/** The last few seconds of clipped drive-frame acceleration, for windows that open in the past. */
internal class RecentAccel(private val keepS: Double = 5.0) {
    val frames = ArrayDeque<Pair<Double, Double>>()

    fun add(t: Double, accel: Double) {
        frames.addLast(t to accel)
        while (frames.isNotEmpty() && frames.first().first < t - keepS) frames.removeFirst()
    }
}

/**
 * Velocity and height integrated (trapezoid) from a start instant with the
 * velocity taken as zero there, until the velocity first returns to zero.
 */
internal class ShortWindowHeight private constructor(val startS: Double) {
    var velocityMps = 0.0
        private set
    var heightM = 0.0
        private set
    var peakM = 0.0
        private set
    var topped = false
        private set
    private var lastS = Double.NaN
    private var lastA = 0.0
    private var rose = false

    fun step(t: Double, accel: Double) {
        if (topped) return
        if (!lastS.isNaN()) {
            val dt = t - lastS
            velocityMps += 0.5 * (accel + lastA) * dt
            heightM += velocityMps * dt
            peakM = maxOf(peakM, heightM)
            if (velocityMps > 0.0) rose = true
            if (rose && velocityMps <= 0.0) topped = true
        }
        lastS = t
        lastA = accel
    }

    companion object {
        fun from(recent: RecentAccel, startS: Double): ShortWindowHeight {
            val window = ShortWindowHeight(startS)
            recent.frames.filter { it.first >= startS }.forEach { (t, a) -> window.step(t, a) }
            return window
        }
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else 0.5 * (sorted[mid - 1] + sorted[mid])
}
