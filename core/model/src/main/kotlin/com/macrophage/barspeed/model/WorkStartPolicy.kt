package com.macrophage.barspeed.model

/**
 * The instant a set's WORK began, as the raw archive's manifest publishes it
 * under `workStartedAt_ms` (#337).
 *
 * A set with a prep stores its window -- [PrepWindow], written by
 * [PrepWindowPolicy] -- and the manifest has published its work start from that
 * since #185. A set with NO prep stores no window, because there is no
 * interval to state, and so published no work start at all: 0 of the 13
 * straight squat and deadlift sets in field-43, -44 and -46 carries one. An
 * analysis reading the archive then had nothing marking where the work began
 * on exactly the sets whose set-up motion -- walking to the bar, plate
 * handling -- is inside the capture, and a cue-bounded window would be
 * circular on a set whose cue track holds only its own counter's speech.
 *
 * Where no prep ran the work began at the lifter's START SET tap:
 * [AbandonedSetPolicy.workBegan] already answers true for [PrepCase.NONE] with
 * no instant consulted, for that reason. That tap is captured on every row as
 * `startedAtMs`, so nothing new needs capturing -- what was missing is the
 * statement that on such a set it is also the work start.
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
