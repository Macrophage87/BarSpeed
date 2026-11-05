package com.macrophage.barspeed.model

/**
 * The instant the app let a set's WORK begin, as the raw archive's manifest
 * publishes it under `workStartedAt_ms` (#337).
 *
 * A set with a prep stores its window -- [PrepWindow], written by
 * [PrepWindowPolicy] -- and the manifest has published its work start from that
 * since #185. A set with NO prep stores no window, because there is no
 * interval to state, and so published no work start at all: 0 of the 13
 * straight squat and deadlift sets in field-43, -44 and -46 carries one.
 * #337 asked for one so that an analysis reading the archive could bound
 * set-up motion -- walking to the bar, plate handling -- which on such sets
 * is inside the capture.
 *
 * Where no prep ran, the app let the work begin at the lifter's START SET
 * tap, so that tap is what this publishes: [AbandonedSetPolicy.workBegan]
 * already answers true for [PrepCase.NONE] with no instant consulted. It is
 * the row's `startedAtMs` and the first instant of the capture: it adds no
 * information a reader of `startedAt_ms` did not already have, and it does
 * NOT bound set-up motion that follows the tap -- walking to the bar, plate
 * handling, a set-up pull -- which on the 24 Sep 225 x 5 deadlift ran inside
 * a 138 s capture (#337). Where the lifter actually began lifting on such a
 * set is not recorded by anything. It is the app's lower bound on the work,
 * not an observation of it, and #337's purpose is not met by it.
 *
 * Here rather than in the exporter because the archive's two writers, and
 * any later reader, must reach one answer, and `:core:data` runs its tests
 * through fakes only.
 */
object WorkStartPolicy {
    /**
     * The work-start instant to publish, or null to publish none.
     *
     * @param stored the set's stored prep window, or null where it stored none.
     * @param startedAtMs the row's own instant: the START SET tap.
     * @param prepS the prep the app set out to play, null on a set that ran no
     *   prep (and on every row written before v10).
     * @param workBegan the row's `workBegan`, null on every row written before v15.
     */
    fun publishedAtMs(stored: PrepWindow?, startedAtMs: Long, prepS: Int?, workBegan: Boolean?): Long? =
        stored?.workStartedAtMs ?: startedAtMs.takeIf { prepS == null && workBegan == true }
}
