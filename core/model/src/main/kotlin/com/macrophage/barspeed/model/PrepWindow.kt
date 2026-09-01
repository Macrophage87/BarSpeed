package com.macrophage.barspeed.model

/**
 * The interval a set spent in its prep: when recording began, and when the
 * set's work began (#185).
 *
 * Both instants are epoch milliseconds, the same clock `ImuCsv`'s
 * `timestamp_ms` column is stamped with, so a reader joins this against the raw
 * stream with no conversion and no reference instant to get wrong.
 *
 * ## What it does NOT say
 *
 * It does not say the sensor was still. A prep is the seconds between the
 * lifter tapping START and the set beginning; what the bar actually did in them
 * is in the samples, and whether those samples are quiet enough to read a
 * gravity vector from is a question about the samples that whoever needs it can
 * ask of them. The app is not better placed to judge that, only earlier -- and
 * a stillness score published here would be a derived number the analysis could
 * not revise.
 *
 * It also states no transform, no gravity vector and no orientation. Those are
 * the normalisation #185 exists for, and the owner settled where they belong:
 * post hoc, where the whole set is available, filtering can run
 * forward-backward with no phase lag, and a method that turns out wrong can be
 * re-run. This type is the capture fact that makes any of that checkable --
 * the app knows this boundary exactly and an analysis reading the streams alone
 * can only estimate it.
 *
 * [workStartedAtMs] is never before [startedAtMs]; [PrepWindowPolicy] is what
 * enforces that, and is the only thing that should build one of these.
 */
data class PrepWindow(
    /**
     * When recording began -- the lifter's tap, taken in `beginSet` before
     * the set journal is opened.
     *
     * Equal to `SetRecordEntity.startedAtMs` for every set this build records.
     * Carried here anyway rather than left to be joined from the row, because
     * the window is one fact and half of it living somewhere else is how a
     * reader ends up bracketing a set with two instants that were never a pair.
     */
    val startedAtMs: Long,
    /**
     * When the set's work began -- the instant the prep ended.
     *
     * On a [PrepCase.TIMED] set this is the instant the set's own clock starts,
     * which is what #168 moved off the tap; on a [PrepCase.CUED] set it is the
     * instant the cadence's first stroke call is due, which no clock in the set
     * record measures. The two are different mechanisms and deliberately one
     * key: what a reader wants to know is where the prep stopped.
     */
    val workStartedAtMs: Long,
)

/**
 * Whether a set can state its prep interval, and refusing rather than
 * inventing one where it cannot (#185).
 *
 * Pure and here rather than in `:app`, so the rule runs on every push. `:app`
 * has almost no test source set, and the wiring that hands these instants in is
 * compile- and lint-gated only -- what is pinned here is the decision, not that
 * the recorder supplies the right arguments to it.
 */
object PrepWindowPolicy {
    /**
     * The window this set can publish, or null where it has none to publish.
     *
     * Null in three cases, none of them softened into a value:
     *
     * - [PrepCase.NONE]. No prep ran, so there is no interval. A zero-length
     *   window would read as a measured instant of stillness rather than as the
     *   absence of a prep, which is this repository's dominant defect wearing a
     *   helpful face.
     * - [workStartedAtMs] null. The prep was still running when the set ended,
     *   so the interval never closed. Substituting the set's end instant here
     *   would publish a window whose contents are whatever the lifter did while
     *   abandoning the set.
     * - [workStartedAtMs] before [tappedAtMs]. The pair is not an interval, and
     *   the two instants come from two different clock reads in `:app`; an
     *   inverted one reaching an archive would be read as a real window by
     *   anything that subtracts them. `System.currentTimeMillis` is not
     *   monotonic -- a clock correction mid-set is enough.
     *
     * The two instants EQUAL is a window and is returned. A prep of zero
     * seconds is legal (`LeadInPolicy.MIN_S` is 0) and is a real prescription:
     * nothing is spoken and the set begins at once. Published, it tells the
     * reader there is no stationary period to look for, which is a different
     * answer from the app not saying.
     */
    fun of(case: PrepCase, tappedAtMs: Long, workStartedAtMs: Long?): PrepWindow? {
        if (case == PrepCase.NONE) return null
        if (workStartedAtMs == null || workStartedAtMs < tappedAtMs) return null
        return PrepWindow(startedAtMs = tappedAtMs, workStartedAtMs = workStartedAtMs)
    }
}
