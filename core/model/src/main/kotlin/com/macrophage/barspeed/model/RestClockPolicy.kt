package com.macrophage.barspeed.model

/**
 * When a rest period starts, and how much of it is left by the time the rest
 * screen can show a number.
 *
 * [SetClockPolicy] answers which instant a SET is measured from and
 * [TimedSetEndPolicy] answers which instant it ends at. This answers the one
 * after those: the rest between two sets is also a measured interval, and it
 * has the same choice of instants to start from.
 *
 * ## Why the instant is a decision and not an obvious fact
 *
 * A rest period has two candidate starts: the instant the set was over, and
 * the instant the app got round to drawing a countdown. Between them sits the
 * terminal cue, the analysis, the gzip, the Room write, and -- the part that
 * costs whole seconds -- the lifter standing over the phone deciding how the
 * set felt. The effort grid IS the end-set control, so the tap that ends the
 * set is the tap that rates it, and the interval this policy exists for is the
 * one BEFORE that tap: the app calls `Done` when the prescription has been
 * called through, and the set keeps recording until the lifter picks the phone
 * up. On the eleven sets of session 32 that carry both a `Done` cue and an IMU
 * stream, that interval is 4.3 to 13.7 s, measured as last sample minus cue.
 *
 * Started from the second instant, every one of those seconds is rest the app
 * gave and does not know it gave, and it then counts a full period on top of
 * them.
 *
 * ## The rule
 *
 * The period starts at the instant the set was over, so what a countdown is
 * seeded with is the prescription MINUS what has already gone, floored at
 * zero. The seeding used to be the prescription flat; that statement was
 * pinned here for one commit so this change could be a differential against
 * it, and is deleted rather than reworded.
 *
 * ## What this cannot check
 *
 * That `:app` hands in the right instants, or calls this at all. No test on the
 * CI path reaches a coroutine or a composable, so that half is compile- and
 * lint-gated only.
 */
object RestClockPolicy {
    /**
     * The instant the rest period runs from.
     *
     * [setOverCueAtMs] is the stamp of the cue that called the set over --
     * `SetEnd.of` in `:core:dsp` reads it off the set's own frozen cue track --
     * or null when nothing on the record says when the set ended. [endedAtMs]
     * is the instant the set write froze, which every set has.
     *
     * The cue when there is one, because that is when the lifter stopped
     * lifting; the write instant otherwise. Nullable rather than defaulted at
     * the call site so that "nothing said when this set ended" stays a state
     * this function decides about, rather than a zero somebody has to remember
     * not to subtract.
     *
     * ONE owner of the instant. Nothing else may work out when the set was
     * over: two computations of one instant is how the countdown and the
     * record come to disagree about which second a set finished on, which is
     * the failure #168 arranged [TimedSetEndPolicy.remainingS] to make
     * impossible one screen over.
     *
     * A cue instant AFTER the write instant is still taken. It cannot arise
     * from the app -- the cue is written before the set ends, on the same
     * clock -- so if it ever does, the wall clock moved between the two and
     * neither figure is more trustworthy than the other. [remainingS] floors
     * the result either way, so the worst it can produce is a full period.
     */
    fun startedAtMs(setOverCueAtMs: Long?, endedAtMs: Long): Long = setOverCueAtMs ?: endedAtMs

    /**
     * Seconds of rest left at [nowMs], for a period of [restS] that started at
     * [startedAtMs].
     *
     * WHOLE elapsed seconds are subtracted, floored, the way
     * [SetClockPolicy.heldSeconds] measures a hold. Rounding up would take a
     * second of rest off every set for a reason the lifter had no part in, and
     * the countdown would then reach zero before the period it names had run.
     *
     * Clamped at BOTH ends, and each end is a separate hazard.
     *
     * Zero at the bottom: a rest that fully elapsed while the lifter was still
     * on the set-end screen has none left, and it must read as none rather
     * than starting a fresh full period. That zero is a measured zero -- the
     * rest happened and none of it remains -- and not an absence, so it is a
     * number and not a null. Negative seconds have nowhere to go: the screen
     * formats mm:ss, and the countdown loop asks `> 0` to decide whether to
     * tick at all.
     *
     * [restS] at the top: [nowMs] is a WALL clock, so NTP, a timezone update
     * or the lifter setting the time can move it backwards between the two
     * readings, and a negative elapsed would otherwise ADD to the countdown.
     * The ceiling is the same figure the progress ring divides by, so without
     * it the ring can draw more than full.
     *
     * Arithmetic in Long throughout. The two instants are epoch milliseconds
     * and their difference does not fit an Int for any interval worth naming.
     *
     * A non-positive prescription is zero seconds of rest, by the same floor.
     */
    fun remainingS(restS: Int, startedAtMs: Long, nowMs: Long): Int {
        val elapsedS = (nowMs - startedAtMs) / 1_000L
        return (restS.toLong() - elapsedS).coerceIn(0L, restS.toLong().coerceAtLeast(0L)).toInt()
    }

    /**
     * Which of a set's OWN heart-rate samples fall in the rest that followed
     * it, given the instant [startedAtMs] the rest runs from. Issue #178.
     *
     * The countdown and the rest-HR window are two readers of one instant, and
     * until this pair of commits they read different ones: the countdown runs
     * from [startedAtMs] (#172), while the capture that becomes the archive's
     * `rest_before_hrm` stream begins only when the app leaves the set stage,
     * which is the write instant. Measured on field-37, that gap is 0 s on a
     * set whose terminal cue sits at its end and 53.06 s on set 7, which spoke
     * `Done` and kept recording.
     *
     * TODAY'S RULE, written here for one commit so the change can be a
     * differential against it and deleted at that differential rather than
     * reworded: the window opens only once the set's capture has stopped, so
     * whatever [startedAtMs] says, the seed is empty on every set.
     */
    fun restWindowSeed(setHrSamples: List<HrSample>, startedAtMs: Long): List<HrSample> {
        val afterTheSetsLastSample = (setHrSamples.maxOfOrNull { it.timestampMs } ?: startedAtMs) + 1L
        val windowOpensAtMs = maxOf(startedAtMs, afterTheSetsLastSample)
        return setHrSamples.filter { it.timestampMs >= windowOpensAtMs }
    }
}
