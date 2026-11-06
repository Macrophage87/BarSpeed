package com.macrophage.barspeed.model

/**
 * Whether a tap on the in-set effort grid is taken, given when "+1 REP" was
 * last tapped (UX review 2026-11-06, record-live F2).
 *
 * On a hand-counted set, and on a sensor-counted set with its "+1 REP THE
 * SENSOR MISSED" correction, the counting button sits directly above the
 * effort grid, and every grid tile ENDS THE SET. The lifter taps +1 between
 * reps, fast and without looking closely, with the phone on a bench; a tap
 * that lands a little low used to end the set mid-set, rated, with the rest of
 * its capture gone. So for [WINDOW_MS] after a +1 tap the grid ignores taps.
 * A real end-of-set rating comes after the last rep has been counted and the
 * bar racked, which is well past that window.
 *
 * [lastRepTapAtMs] is null when +1 has not been tapped on this set, and then
 * every grid tap is taken. Both times are read off the same monotonic clock by
 * the caller.
 */
object EffortGridSlipGuard {
    const val WINDOW_MS = 700L

    fun accepts(nowMs: Long, lastRepTapAtMs: Long?): Boolean =
        lastRepTapAtMs == null || nowMs - lastRepTapAtMs >= WINDOW_MS
}
