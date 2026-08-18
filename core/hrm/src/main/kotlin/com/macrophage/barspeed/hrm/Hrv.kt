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
     * consistent beats, re-anchoring on a CONFIRMED shift instead of
     * chaining every later beat against one frozen reference (issue #27).
     *
     * A candidate that breaks with the running segment is an artifact
     * UNLESS the very next raw beat confirms it: is itself within the same
     * jump fraction of the rejected candidate, AND is not numerically
     * identical to it. Two is the smallest number of observations for which
     * "agreement" is a coherent idea at all -- a lone ectopic or
     * dropped-beat spike cannot supply a second point that agrees with it,
     * because by definition it is a one-off. Reusing [MAX_JUMP_FRACTION] for
     * the confirmation test adds no threshold tuned to any one capture; the
     * distinctness check exists because this strap re-sends its last
     * completed R-R at a fixed cadence when no new beat has arrived (issue
     * #81) -- a repeated notification is the same beat, not independent
     * evidence of a shift. Verified against the worn control: without the
     * distinctness check, a real cadence-resent duplicate pair (757.8 ms
     * twice, worn set 13) supplies exactly that false confirmation.
     *
     * The new segment starts at the CONFIRMING beat, not the rejected
     * candidate. The candidate is evidence a shift happened; it is never
     * used as data. This matters beyond conservatism: a single misdetection
     * can split one true beat into two implausible-looking halves rather
     * than reporting a heart-rate change at all. On the worn control's own
     * set 1, a dip to 496.1 ms is followed by 792.0 ms twice (the second a
     * cadence-resent duplicate of the first) before recovering through
     * 635.7 ms -- and 496.1 + 792.0 = 1288.1, close to double the ~645 ms
     * local baseline, the signature of exactly that split. Anchoring on
     * 792.0 would splice a spurious jump into every RMSSD computed from
     * this set from here on; anchoring on 635.7, the beat that actually
     * confirmed the shift, does not.
     */
    internal fun segments(rrMs: List<Double>): List<List<Double>> {
        val plausible = rrMs.filter { it in MIN_RR_MS..MAX_RR_MS }
        if (plausible.isEmpty()) return emptyList()
        val result = mutableListOf(mutableListOf(plausible[0]))
        var i = 1
        while (i < plausible.size) {
            val anchor = result.last().last()
            val v = plausible[i]
            if (withinJump(v, anchor)) {
                result.last() += v
                i++
                continue
            }
            val next = plausible.getOrNull(i + 1)
            if (next != null && next != v && withinJump(next, v)) {
                result += mutableListOf(next)
                i += 2
            } else {
                i++
            }
        }
        return result
    }

    /**
     * Artifact rejection: drop physiologically implausible intervals and
     * ectopic/missed-beat jumps, which otherwise dominate RMSSD.
     *
     * The first segment is dropped, unflattened, when it is an orphan of
     * size 1: the only way that happens is the unconditional seed beat
     * later being disowned by a confirmed re-anchor elsewhere in the
     * stream, which is as strong a signal as this function has that the
     * seed itself was the artifact. A later segment can also end up size
     * 1 -- its lone beat was still independently confirmed against the one
     * that broke with it, so it is kept.
     */
    fun clean(rrMs: List<Double>): List<Double> {
        val segs = segments(rrMs)
        return (if (segs.size > 1 && segs.first().size == 1) segs.drop(1) else segs).flatten()
    }

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
