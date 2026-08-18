package com.macrophage.barspeed.hrm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Heart-rate variability from R-R intervals (the standard BLE HR characteristic
 * carries them; the Garmin HRM 600 sends them every beat).
 *
 * RMSSD is the metric of choice for short recordings: it reflects beat-to-beat
 * (parasympathetic) variability and is robust over the 1–5 minute windows we
 * get during rest periods and sessions.
 */
object Hrv {
    private const val MIN_RR_MS = 300.0
    private const val MAX_RR_MS = 2000.0

    /** Beats that jump more than this fraction from the running reference are artifacts. */
    private const val MAX_JUMP_FRACTION = 0.25

    const val DEFAULT_MIN_INTERVALS = 10

    private fun withinJump(candidate: Double, anchor: Double): Boolean =
        abs(candidate - anchor) <= anchor * MAX_JUMP_FRACTION

    /**
     * Split the plausible R-R stream into contiguous runs of mutually
     * consistent beats.
     *
     * Today this is a straight port of the single chain [clean] used to
     * build: every candidate is tested against the last beat accepted so
     * far, and a rejected candidate is simply dropped, never restarting the
     * reference. So this always returns exactly one segment (or none, for an
     * empty input) -- the boundary case is not reachable yet. Issue #27:
     * re-anchoring on a confirmed shift, so a segment boundary becomes
     * reachable, is the next commit.
     */
    internal fun segments(rrMs: List<Double>): List<List<Double>> {
        val plausible = rrMs.filter { it in MIN_RR_MS..MAX_RR_MS }
        if (plausible.isEmpty()) return emptyList()
        val result = mutableListOf(mutableListOf(plausible[0]))
        var i = 1
        while (i < plausible.size) {
            val anchor = result.last().last()
            val v = plausible[i]
            if (withinJump(v, anchor)) result.last() += v
            i++
        }
        return result
    }

    /**
     * Artifact rejection: drop physiologically implausible intervals and
     * ectopic/missed-beat jumps, which otherwise dominate RMSSD.
     */
    fun clean(rrMs: List<Double>): List<Double> = segments(rrMs).flatten()

    /** Root mean square of successive differences, in ms. Null below [minIntervals] clean beats. */
    fun rmssdMs(rrMs: List<Double>, minIntervals: Int = DEFAULT_MIN_INTERVALS): Double? {
        val diffs = segments(rrMs).flatMap { seg -> seg.zipWithNext { a, b -> b - a } }
        if (diffs.size < minIntervals) return null
        return sqrt(diffs.map { it * it }.average())
    }

    /** Standard deviation of the (cleaned) R-R intervals, in ms. */
    fun sdnnMs(rrMs: List<Double>, minIntervals: Int = DEFAULT_MIN_INTERVALS): Double? {
        val rr = clean(rrMs)
        if (rr.size < minIntervals) return null
        val mean = rr.average()
        return sqrt(rr.map { (it - mean) * (it - mean) }.average())
    }
}
