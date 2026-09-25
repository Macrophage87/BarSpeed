package com.macrophage.barspeed.dsp

/**
 * (b) FULL CYCLE -- the rep closes when the bar is back on the floor, and only
 * if it took long enough to get there. Issue #305's candidate (b), with (e)'s
 * two combinations as switches.
 *
 * - OPEN: a drive met by its brake ([DriveBrakeTracker]). The rep is pending.
 *   The drive arms at the END of its brake run, which on field-44's heavy sets
 *   spans brake and lowering, so arming can come seconds after the drive.
 * - THE DESCENT GATE: the rep can be CALLED only once the negative part of the
 *   smoothed drive-frame acceleration, integrated from the brake run's start to
 *   the closing event, reaches [CycleParams.descentMps] in magnitude. That is
 *   an acceleration integral, not a measured bar velocity or height.
 * - CLOSE, from [CycleParams.minCycleS] after the drive ended: the first
 *   CONTACT, FALL, STILL or next armed drive (touch-and-go without a contact).
 *   - descent gate met: CALLED.
 *   - descent gate not met: a STILL keeps the rep pending; a CONTACT, a FALL or
 *     the next armed drive REJECTS it.
 * - BEFORE [CycleParams.minCycleS]: a CONTACT or FALL REJECTS the rep; a STILL
 *   is ignored; the next armed drive REPLACES it and nothing is spoken for it.
 * - With [minHeightM] set, a rep that passes the descent gate is still REJECTED
 *   if its short-window height is under [minHeightM].
 *
 * Nothing is spoken for a rejected or replaced rep. A CONTACT or FALL within
 * [CycleParams.minCycleS] of a drive that has not armed yet also drops that
 * drive before it can arm -- that, not the pending-rep rule, is what refuses
 * field-44 set 5's failed pull.
 *
 * The call is spoken at the closing event. On the eight deadlift sets its
 * median lag is 1.16 s after the matched batch window's end, against -0.27 s
 * for the shipped counter (`ClosingRuleCandidateTest`'s per-rep table).
 *
 * @param relativeDrive (e) a SET-RELATIVE drive: a drive arms only if it gains
 *   at least this fraction of the median gain of the reps already called in
 *   the set. Null is the absolute rule alone.
 * @param minHeightM (e) CYCLE + HEIGHT: the rep must also have risen this far,
 *   integrated from the drive's start to the first instant its velocity
 *   returns to zero (candidate (c)'s window). Null switches it off.
 */
internal class CycleCandidate(
    p: CycleParams = CycleParams(),
    private val relativeDrive: Double? = null,
    private val minHeightM: Double? = null,
) : DriveBrakeTracker(p) {
    private class Pending(val drive: Drive, var negativeMps: Double, val height: ShortWindowHeight?)

    private var pending: Pending? = null
    private val calledGains = mutableListOf<Double>()
    private val recent = RecentAccel()

    override fun beforeRuns(t: Double) {
        recent.add(t, clipped)
        val open = pending ?: return
        if (smoothed < 0) open.negativeMps += smoothed * dt
        open.height?.step(t, clipped)
    }

    override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
        val scale = relativeDrive
        if (scale != null && calledGains.isNotEmpty() && armed.gainMps < scale * median(calledGains)) return null
        var called: RepClosed? = null
        val old = pending
        if (old != null && armed.startS - old.drive.endS >= p.minCycleS && close(old, null) == Outcome.CALLED) {
            called = closing(old.drive, t)
        }
        val height = minHeightM?.let { ShortWindowHeight.from(recent, armed.startS) }
        pending = Pending(armed, brakeIntegral, height)
        return called
    }

    override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
        var called: RepClosed? = null
        val open = pending
        val ends = event == FloorEvent.CONTACT || (event == FloorEvent.FALL && p.fallRejects)
        if (open != null) {
            if (t - open.drive.endS >= p.minCycleS) {
                val outcome = close(open, event)
                if (outcome != Outcome.KEPT) pending = null
                if (outcome == Outcome.CALLED) called = closing(open.drive, t)
            } else if (ends) {
                pending = null
            }
        }
        val waiting = drive
        val soon = waiting != null && (t - waiting.endS).let { it > 0.0 && it < p.minCycleS }
        if (ends && soon) drive = null
        return called
    }

    private enum class Outcome { CALLED, KEPT, REJECTED }

    /** [event] null is the next armed drive closing a touch-and-go rep. */
    private fun close(open: Pending, event: FloorEvent?): Outcome {
        val need = p.descentMps
        if (need != null && open.negativeMps > -need) {
            return if (event == FloorEvent.STILL) Outcome.KEPT else Outcome.REJECTED
        }
        val floor = minHeightM
        if (floor != null && (open.height?.peakM ?: 0.0) < floor) return Outcome.REJECTED
        calledGains += open.drive.gainMps
        return Outcome.CALLED
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
    p: CycleParams = CycleParams(stillS = lockoutS),
) : DriveBrakeTracker(p) {
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
internal class ContactCandidate(p: CycleParams = CycleParams()) : DriveBrakeTracker(p) {
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
    p: CycleParams = CycleParams(),
) : DriveBrakeTracker(p) {
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
