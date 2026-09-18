package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomBound] on its own, away from any capture: the three clauses of the rule
 * and the absence case, on spans and anchor lists built by hand.
 *
 * Green pins on a new symbol. Nothing reads [RepAnalysis.romBounded] yet --
 * `romSpread_pct`, the export and the post-set chip are untouched by the commit
 * that adds this -- so no published figure moves. The corpus column is in
 * [RomBoundCorpusTest], and the measurement that argued for withholding rather
 * than repairing is in [RomDriftBaselineTest].
 */
class RomBoundTest {
    /** An eccentric-first rep occupying [lo]..[hi], with the phases split down the middle. */
    private fun span(lo: Int, hi: Int): RepSpan {
        val mid = (lo + hi) / 2
        return RepSpan(
            eccStartIdx = lo,
            eccEndIdx = mid,
            conStartIdx = mid,
            conEndIdx = hi,
            turnaroundPauseS = 0.1,
        )
    }

    /** A drive-only rep, whose eccentric span is a placeholder inside the drive's own range. */
    private fun driveOnly(lo: Int, hi: Int) = RepSpan(
        eccStartIdx = hi,
        eccEndIdx = hi,
        conStartIdx = lo,
        conEndIdx = hi,
        turnaroundPauseS = null,
        hasEccentric = false,
    )

    @Test
    fun `a rep alone between two accepted anchors is bounded`() {
        val one = span(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50)))
    }

    @Test
    fun `an anchor exactly at the span's own ends counts`() {
        // At or before, at or after: the anchor is a sample index and the sample
        // the rep begins on may be the anchor itself. A strict comparison would
        // call the best-bounded rep in a set unbounded.
        val one = span(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(20, 40)))
    }

    @Test
    fun `two reps sharing one inter-anchor interval are both unbounded`() {
        // The clause that does the work on real captures. The erased-displacement
        // cap VelocityEstimator.anchorAcceptable applies is spent once per
        // interval, so an interval holding two reps bounds neither.
        val first = span(20, 40)
        val second = span(50, 70)
        val spans = listOf(first, second)
        val anchors = intArrayOf(0, 10, 80)
        assertFalse(RomBound.bounded(first, spans, anchors), "first rep")
        assertFalse(RomBound.bounded(second, spans, anchors), "second rep")
    }

    @Test
    fun `a rep with no accepted anchor after it is unbounded`() {
        // After the last anchor the drift offset is a constant, so the integration
        // error runs to the end of the stream -- the case
        // AccelArtefact.corruptedSpan describes from the same anchor structure.
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(0, 10)))
    }

    @Test
    fun `a rep with no accepted anchor before it is unbounded`() {
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(50, 60)))
    }

    @Test
    fun `a set that accepted no anchor at all bounds nothing`() {
        // VelocitySeries.anchorIndices defaults to empty, and that default is
        // chosen for its failure direction. A hand-built series gets it, and it
        // must read as "nothing is bounded" rather than "everything is".
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), IntArray(0)))
        assertEquals(listOf(false), RomBound.boundedFlags(listOf(one), IntArray(0)))
    }

    @Test
    fun `a drive-only rep is judged on its drive window alone`() {
        // Its eccentric span is a placeholder inside the drive's own range, so
        // taking the min and the max of the four indices must not widen it.
        val one = driveOnly(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(19, 41)))
        assertFalse(
            RomBound.bounded(one, listOf(one), intArrayOf(21, 41)),
            "an anchor inside the drive is not before it",
        )
    }

    @Test
    fun `flags come back in span order, one per span`() {
        val a = span(20, 40)
        val b = span(60, 80)
        val c = span(120, 140)
        // Anchors at 0, 50, 100 and 200: a and b each hold an interval alone,
        // c shares 100..200 with nothing but has no following anchor until 200,
        // so it is bounded too. The interval 50..100 holds b alone.
        assertEquals(
            listOf(true, true, true),
            RomBound.boundedFlags(listOf(a, b, c), intArrayOf(0, 50, 100, 200)),
        )
        // Remove the anchor at 50 and a and b share 0..100.
        assertEquals(
            listOf(false, false, true),
            RomBound.boundedFlags(listOf(a, b, c), intArrayOf(0, 100, 200)),
        )
    }

    @Test
    fun `boundedReps keeps a rep whose flag is null and drops one that is false`() {
        // Absence is not falsity. An analysis stored before this rule existed
        // carries null on every rep, and those sets must keep the figures they
        // have always published rather than lose them to a rule they predate.
        val reps = listOf(rep(0, null), rep(1, true), rep(2, false))
        assertEquals(listOf(0, 1), RomBound.boundedReps(reps).map { it.index })
    }

    @Test
    fun `the minimum population is two, the same minimum a dispersion already had`() {
        assertEquals(2, RomBound.MIN_BOUNDED_REPS)
    }

    private fun rep(index: Int, bounded: Boolean?) = RepAnalysis(
        index = index,
        eccS = 1.0,
        bottomPauseS = 0.0,
        conS = 1.0,
        topPauseS = 0.0,
        meanConVelMps = 0.3,
        peakConVelMps = 0.5,
        meanEccVelMps = -0.2,
        peakEccVelMps = -0.3,
        romM = 0.4,
        peakPowerW = null,
        romBounded = bounded,
    )
}
