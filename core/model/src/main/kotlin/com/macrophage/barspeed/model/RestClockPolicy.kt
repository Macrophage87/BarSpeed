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
 * ## Today's rule, which #172 replaces
 *
 * This commit states the rule the app follows NOW, unchanged, so that it is a
 * pinned statement in a module with tests before it is altered: the countdown
 * is seeded with the whole prescribed period, whatever has already elapsed.
 * [remainingS] therefore ignores its clock arguments. That is not an oversight
 * and it is not the intended end state -- it is today's behaviour, lifted out
 * of `:app` where nothing could assert it.
 *
 * ## What this cannot check
 *
 * That `:app` hands in the right instants, or calls this at all. `:app` has one
 * test file and none of it reaches a coroutine or a composable, so that half is
 * compile- and lint-gated only.
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
     * TODAY'S RULE, which is the whole period regardless of the clock. #172
     * changes it to subtract the elapsed time; this states what the app does
     * now so the change is a differential against a pinned statement rather
     * than against a memory of one.
     *
     * A non-positive prescription is zero seconds of rest and not a negative
     * countdown.
     *
     * [startedAtMs] and [nowMs] are declared and not read. That is deliberate:
     * they are the arguments the fixed rule needs, so #172 changes one
     * expression in this file and no call site anywhere.
     */
    @Suppress("UNUSED_PARAMETER")
    fun remainingS(restS: Int, startedAtMs: Long, nowMs: Long): Int = restS.coerceAtLeast(0)
}
