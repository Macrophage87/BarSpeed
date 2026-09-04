package com.macrophage.barspeed.dsp

/**
 * When the set's work began, and therefore which detected drives are early
 * enough not to be reps of it. The head-of-stream mirror of [SetEnd].
 *
 * A set records from the lifter's tap, and a prep runs between that tap and the
 * work: a countdown, a `Ready` and a `Brace`. On a `PrepCase.TIMED` set
 * `PrepWindow.workStartedAtMs` IS the instant the set's own clock starts, so
 * the clock the set is measured over is the WORK and not part of the prep.
 * What the lifter does in the prep's seconds is ordinary handling -- settling
 * into the seat, cleaning a pair of dumbbells to the shoulders, one practice
 * stroke -- and it segments like any other movement, so it arrives in the rep
 * list as a rep and sets whatever the rep list decides. Issue #245.
 *
 * ## What was measured, on field session 38
 *
 * Nineteen of the 226 detections the analysed role resolves across that
 * session's sixteen dynamic sets have a drive that began before their own set's
 * `workStartedAt_ms`, and fourteen have one that ENDED before it. On four sets
 * one of those detections is the fastest rep of the set, and `VelocityLoss`
 * divides by the fastest rep. Dropping them and re-running the shipped rule
 * moves `velocityLoss_pct` on set 2 from 27.4 to 11.6, set 5 from 62.1 to 40.3,
 * set 9 from 58.3 to 48.7 and set 10 from 41.2 to 36.1; set 5's
 * `summary.peakPower_w` moves 402.5 -> 320.1, a 25.7% overstatement produced
 * during the countdown. Two of those sets are committed here as
 * `field-ohp-3010-8rep-s38-set05` and
 * `field-inclinepress-3010-12rep-s38-set02`, and `PrepDetectionFieldTest` is
 * where their figures are pinned; the other fourteen sets are measured in that
 * session's archive and not in this repository.
 *
 * THE RULE. A detection is early enough to be excluded when its DRIVE ENDED
 * strictly before the instant the set's work began. Nothing else is asked of
 * it.
 *
 * ## Why the drive's END, when [SetEnd] bounds on the drive's START
 *
 * Because the two together are the same statement: a detection belongs to the
 * set when it OVERLAPS the working window at all. [SetEnd] keeps a drive that
 * began before the terminal cue and ended after it, on the argument that the
 * lifter began it while the set was running; the mirror of that is keeping a
 * drive that began before the work did and was still under way when it started.
 * Bounding the head on the drive's START instead would refuse such a drive, and
 * that is the case where refusing is most costly: the first rep of a set is
 * usually its fastest, `velocityLoss_pct` is best-rep-to-last-rep, and refusing
 * a real first rep RAISES the published loss -- the defect issue #245 exists
 * for, appearing in a new place. On a cued set the instant is when the
 * cadence's first stroke call is DUE, and a lifter who moves as the word is
 * spoken rather than after it is ordinary.
 *
 * THE CHOICE IS NOT LOAD-BEARING ON THE CAPTURES HELD HERE, AND SAYING SO IS
 * THE POINT. Both rules were run over all sixteen dynamic sets of field
 * session 38 and they publish IDENTICAL `velocityLoss_pct` and
 * `summary.peakPower_w` on every one of them, including the four the issue
 * names. They differ only in how many detections they exclude, 19 against 14,
 * and the five that differ are drives straddling the instant on sets 5, 12, 13,
 * 14 and 16. None of the five is the fastest detection of its set. On peak
 * power the claim is narrower and is stated narrowly: set 5 is the only one of
 * the five whose set publishes `peakPower_w` at all -- 12, 13, 14 and 16 are
 * stack-mounted cable work on which no detection carries the key -- and set 5's
 * straddler is not its most powerful. So the rule is chosen on the direction it
 * errs in, not on a figure it moves, and it errs the way `RepRefusal`'s bound
 * errs: toward admitting a phantom rather than refusing a rep.
 *
 * Inclusive at the boundary, like [SetEnd.startedWithinSet]: a drive ending on
 * the same millisecond work began was still under way when it began.
 *
 * ## A set with no instant is [Unknown], and nothing is bounded
 *
 * Not every set has one, and none may be invented. `PrepWindowPolicy` refuses
 * to build a window for a set that ran no prep at all, for a set ended while
 * its prep was still running, and for an inverted instant pair; and the instant
 * is a capture fact stored from database v15 on (#216), so every set recorded
 * before that carries none and always will. An ad-hoc set with no prep is the
 * ordinary case, not the exotic one.
 *
 * The first sample of the stream is NOT a substitute. Recording opens at the
 * tap, which is before the prep, so a boundary placed there excludes nothing
 * while looking like a rule that ran -- the same trap [SetEnd] names for the
 * last sample at the other end.
 *
 * [detectionsBefore] reports null there rather than 0, because "nothing said
 * when the work began" and "the work began and nothing came before it" are
 * different facts about a set and a reader of a stored analysis cannot recover
 * the difference from anything else it carries. Same doctrine as
 * [SetAnalysis.detectionsAfterSetEndCue] and [SetAnalysis.refusedDetections].
 *
 * ## Which clock
 *
 * Epoch milliseconds, host arrival. `PrepWindow.workStartedAtMs` is stamped in
 * the recorder and `ImuSample.timestampMs` when the notification lands, so the
 * two are the same clock. The caller must pass a drive-end instant read off the
 * SAMPLE, never a time converted from the DSP's reconstructed clock: that
 * conversion costs up to 105.3 ms, the worst skew measured across the committed
 * cue tracks. [RollExcursion] already reads this same instant for the same
 * reason.
 */
sealed interface WorkStart {
    /** The set's work began at [atMs], host arrival clock. */
    data class Known(val atMs: Long) : WorkStart

    /** Nothing on the record says when this set's work began, so nothing is bounded. */
    data object Unknown : WorkStart

    /**
     * Was a drive ending at [driveEndMs] still under way once the set's work had
     * begun?
     *
     * Inclusive at the boundary: a drive ending on the same millisecond work
     * began had not finished before it.
     */
    fun withinSet(driveEndMs: Long): Boolean = when (this) {
        is Known -> driveEndMs >= atMs
        Unknown -> true
    }

    /**
     * How many of [driveEndMs] finished before the set's work began, or null
     * when nothing said when that was.
     *
     * Defined through [withinSet] so the count and the rule cannot disagree
     * about a boundary case.
     */
    fun detectionsBefore(driveEndMs: List<Long>): Int? = when (this) {
        is Known -> driveEndMs.count { !withinSet(it) }
        Unknown -> null
    }

    companion object {
        /**
         * The bound this set can apply, from `PrepWindow.workStartedAtMs` or
         * from null where the set has no window.
         *
         * A total function of a nullable instant rather than two call sites
         * deciding for themselves what a missing one means: the analyzer takes
         * the instant, this decides what it is worth, and there is one place
         * for "absent" to be handled.
         */
        fun of(workStartedAtMs: Long?): WorkStart = workStartedAtMs?.let { Known(it) } ?: Unknown
    }
}
