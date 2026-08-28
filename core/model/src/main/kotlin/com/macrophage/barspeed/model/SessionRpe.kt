package com.macrophage.barspeed.model

/**
 * The session rating: how the whole workout felt, 1 to 10, stated once when the
 * lifter finishes (#159).
 *
 * ## Two scales, and they are not the same instrument
 *
 * The app now carries two things called RPE and they answer different
 * questions over different ranges:
 *
 *  - A SET's rpe is reps-in-reserve. The effort grid offers 6 through 10 --
 *    "6 easy, 4+ reps left" to "10 max, nothing left" -- and it is a statement
 *    about one set's proximity to failure.
 *  - A SESSION's rating is the whole workout, [MIN] through [MAX], and it is
 *    the lifter's own answer to "how am I feeling at the end of the workout?"
 *    It is not reps-in-reserve; there are no reps left in a session.
 *
 * The owner's ruling fixed the range at 1 to 10 and named the consequence:
 * nothing may average or compare the two as one quantity, so every published
 * description says which scale it is. That is why this file exists as a
 * `:core:model` object rather than as two constants in `:app` -- the value
 * reaches the database and the export, and the range the control offers and
 * the range the schema advertises have to be one fact.
 *
 * ## Direction
 *
 * Higher is harder: 1 is a session that barely touched the lifter, 10 is one
 * that took everything. That keeps the direction of the per-set scale even
 * though the range and the referent differ, and it makes the two readings of
 * the owner's question -- how hard was it, how drained am I -- agree instead of
 * inverting each other. Nothing here has been put in front of a lifter, so
 * whether the wording reads that way in a gym is a `[Field]` question, not a
 * measured fact.
 *
 * ## Absence
 *
 * There is no default and no neutral value. A session the lifter did not rate
 * carries null, all the way to the export, where the key is simply absent.
 * A 5 would be a fabricated answer and a 0 would be an answer off the scale
 * that reads as the easiest session ever recorded.
 */
object SessionRpe {
    /** The easiest rating the scale carries. Not "no rating" -- see [accepted]. */
    const val MIN = 1

    /** The hardest rating the scale carries. */
    const val MAX = 10

    /**
     * Every rating the lifter may state, in the order a control draws them.
     *
     * The control is built from this rather than from a literal `1..10` at the
     * call site, so a control cannot offer a number the stored column and the
     * published schema refuse.
     */
    val VALUES: List<Int> = (MIN..MAX).toList()

    /**
     * The rating to store for what a caller states: the value itself when it is
     * on the scale, null otherwise.
     *
     * Null in, null out -- a skipped rating is an absence and stays one.
     *
     * A value OFF the scale also becomes null, and that is a judgement rather
     * than an obvious answer. Nothing the app draws can produce one: the
     * control is built from [VALUES]. So an out-of-range value is a programming
     * error arriving at the last durable write of a session, and the two
     * alternatives are worse. Storing it publishes a rating the schema's own
     * bounds reject, into a document whose reader has been told the range.
     * Throwing kills the session close, which is also the only writer of
     * `hrvRmssdMs` -- a figure computed from R-R intervals held in memory in
     * `:app` and recoverable from nothing. Dropping the rating loses the
     * rating; the other two lose the session or corrupt the archive.
     *
     * What this does NOT do is round or clamp. An 11 does not become a 10:
     * that would turn a bug into the hardest session the lifter ever recorded.
     */
    fun accepted(rating: Int?): Int? = rating?.takeIf { it in MIN..MAX }
}
