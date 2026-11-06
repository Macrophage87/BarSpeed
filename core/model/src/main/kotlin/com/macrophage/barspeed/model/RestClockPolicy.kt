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
     * [setOverCueAtMs] is the instant the record says the set was over, or
     * null when nothing on it does. `RestStart.of` in `:core:dsp` reads it off
     * the set's own frozen record: the cue `SetEnd.of` admits, or on a set no
     * cadence ran, the lifter's last `+1 REP` tap. [endedAtMs] is the instant
     * the set write froze, which every set has.
     *
     * The paragraph that stood here chose `SetEnd.calledOver` over `SetEnd.of`
     * because "the tap at the planned count is the best instant on the record"
     * for when a lifter counting their own set stopped. That is true only of a
     * lifter who stopped AT the plan with the cues on; one who tapped past it
     * had their rest start at the planned tap, and with the cues off the same
     * set started its rest at the write. #284's H1 round moves the choice to
     * `RestStart`, and the paragraph is DELETED rather than reworded.
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
     * [sensorEndAtMs] FIRST, and it is offered only where a release DECIDED the
     * set's recorded seconds -- `HoldEndPolicy`'s answer, never a release that
     * was found and not believed. That ordering is the whole point of the
     * parameter: once a hold's duration is measured to the release, a rest
     * counted from anything else means the app holds two answers to when the set
     * ended, which is #178's defect in a new place. Since #295 `Time` is in
     * `SetEnd.TERMINAL_CUES`, so a hold the clock ended carries a cue instant
     * and the ordering is no longer only pinned in principle: a release that
     * decided a clock-ended hold's seconds outranks the `Time` stamp, which
     * would otherwise start the rest at the target the hold did not reach --
     * field-45 set 13's would start 4.284 s earlier than its write, which came
     * 1 ms after its `Time`. On a tapped hold it outranks the write instant,
     * or since #288 the `Set ended` the tap speaks where the timed voice is on.
     *
     * A cue instant AFTER the write instant is still taken. It cannot arise
     * from the app -- the cue is written before the set ends, on the same
     * clock -- so if it ever does, the wall clock moved between the two and
     * neither figure is more trustworthy than the other. [remainingS] floors
     * the result either way, so the worst it can produce is a full period.
     */
    fun startedAtMs(setOverCueAtMs: Long?, sensorEndAtMs: Long?, endedAtMs: Long): Long =
        sensorEndAtMs ?: setOverCueAtMs ?: endedAtMs

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
     * Milliseconds from [nowMs] to the next whole second of a rest that started
     * at [startedAtMs], in 1..1000 (UX review 2026-11-06, rest F10).
     *
     * The countdown sleeps this long and then asks [remainingS] again, so every
     * tick lands on the second the period is measured in and no tick carries
     * the previous one's lateness forward. Floor-modulo, so a wall clock that
     * moved backwards past the start still answers a positive wait.
     */
    fun msToNextTick(startedAtMs: Long, nowMs: Long): Long = MS_PER_S - Math.floorMod(nowMs - startedAtMs, MS_PER_S)

    /**
     * What the countdown says when the remainder moves from [previousS] to
     * [remainingS], or null for nothing (rest F10).
     *
     * Computed from the same clock the ring draws, so the voice and the screen
     * cannot disagree. Spoken once, as a number is crossed: "Rest over" on
     * reaching zero, and the digit on reaching 1..[countdownFromS]. A jump
     * across several seconds -- the CPU slept with the screen off -- says only
     * where the clock now is, never a late burst of "3, 2, 1". A remainder
     * that did not fall (a wall clock moved backwards) says nothing.
     */
    fun cueOnTick(previousS: Int, remainingS: Int, countdownFromS: Int): String? = when {
        remainingS >= previousS -> null
        remainingS == 0 -> REST_OVER
        remainingS <= countdownFromS -> remainingS.toString()
        else -> null
    }

    /** The words the countdown speaks at zero. */
    const val REST_OVER = "Rest over"

    private const val MS_PER_S = 1_000L

    /**
     * Which of a set's OWN heart-rate samples fall in the rest that followed
     * it, given the instant [startedAtMs] the rest runs from. Issue #178.
     *
     * ONE INSTANT, TWO READERS. The countdown the lifter watches runs from
     * [startedAtMs] (#172). The capture that becomes the archive's
     * `rest_before_hrm` stream ran, until this, from the instant the app left
     * the set stage instead -- so the same rest had two starts. Measured on
     * field-37: 0 s apart on a set whose terminal cue sits at its end, 53.06 s
     * apart on set 7, which spoke `Done` and kept recording. Neither document
     * published which instant it used, so they could not be joined.
     *
     * The set-over instant wins and the window follows it, because that is the
     * instant [startedAtMs] already owns and the one the lifter's rest
     * actually began at. A sample stamped AT it is rest; anything earlier is
     * the set's.
     *
     * NOTHING IS MOVED. The caller reads its frozen in-set capture and copies
     * the tail forward, so the set's own `hrm` stream is unchanged and every
     * figure computed over it -- `hr.avgBpm`, `maxBpm`, `endOfSetBpm`, the
     * archive's `minBpm` -- means exactly what it meant before. The cost is
     * that a set's last samples can appear in two files of one archive, which
     * is stated in the export rather than left to be discovered.
     *
     * ## What this cannot check
     *
     * That `:app` hands in its own set's samples and its own instant, that the
     * seed reaches the buffer before the next sample does, or that any of it
     * runs at all. No test on the CI path reaches a coroutine, so that half is
     * compile- and lint-gated only.
     */
    fun restWindowSeed(setHrSamples: List<HrSample>, startedAtMs: Long): List<HrSample> =
        setHrSamples.filter { it.timestampMs >= startedAtMs }
}
