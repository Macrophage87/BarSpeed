package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomBound] on its own, away from any capture: the four clauses of the rule and
 * the absence case, on spans, anchor lists and route records built by hand.
 *
 * Every call passes a route record, [VelocitySeries.anchorCapped]'s per-anchor
 * "did `anchorAcceptable` accept this one", because an anchor the starvation
 * escape took applies no cap to the displacement the correction erases over the
 * interval ending at it. [AnchorRouteTest] measures what that costs on the
 * committed captures; this file is the rule in isolation.
 *
 * The corpus column is in [RomBoundCorpusTest], and the measurement that argued
 * for withholding rather than repairing is in [RomDriftBaselineTest].
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

    /** Every anchor of [anchorIndices] accepted with the caps met. */
    private fun allCapped(count: Int) = BooleanArray(count) { true }

    @Test
    fun `a rep alone between two capped anchors is bounded`() {
        val one = span(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50), allCapped(3)))
    }

    @Test
    fun `a rep whose interval was closed by a starvation anchor is unbounded`() {
        // THE CLAUSE THE CORPUS TURNS ON. The anchor at 50 is the one that ends
        // the interval this rep sits in, and it was taken through the starvation
        // escape, so nothing capped what the offset erased across it -- on the
        // committed captures that came to 1.0180 m, 2.3153 m and 5.0198 m against
        // a 0.10 m cap (AnchorRouteTest). The first three clauses all hold here.
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50), booleanArrayOf(true, true, false)))
    }

    @Test
    fun `an uncapped anchor before the rep does not make it unbounded`() {
        // A flag describes the interval ENDING at its anchor. Whatever the ramp
        // into the anchor at 10 erased, it erased it before this rep began, and
        // the interval the rep occupies is the capped one after it.
        val one = span(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50), booleanArrayOf(true, false, true)))
    }

    @Test
    fun `every interval a rep crosses must be capped, not just the last`() {
        // A rep whose span holds an anchor of its own crosses two intervals, and
        // the bound is minRomM per interval crossed. One uncapped interval
        // inside the span is enough to lose the bound.
        val one = span(20, 60)
        val anchors = intArrayOf(0, 15, 40, 70)
        assertTrue(RomBound.bounded(one, listOf(one), anchors, allCapped(4)))
        assertFalse(
            RomBound.bounded(one, listOf(one), anchors, booleanArrayOf(true, true, false, true)),
            "the interval ending inside the span was uncapped",
        )
        assertFalse(
            RomBound.bounded(one, listOf(one), anchors, booleanArrayOf(true, true, true, false)),
            "the interval ending after the span was uncapped",
        )
    }

    @Test
    fun `a route record that does not match the anchor list bounds nothing`() {
        // Unknown route withholds. VelocitySeries.anchorCapped defaults to
        // EMPTY, so a hand-built series must read as "nothing is bounded"
        // rather than as "every anchor was acceptable".
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50), BooleanArray(0)))
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(0, 10, 50), booleanArrayOf(true, true)))
    }

    @Test
    fun `an anchor exactly at the span's own ends counts`() {
        // At or before, at or after: the anchor is a sample index and the sample
        // the rep begins on may be the anchor itself. A strict comparison would
        // call the best-bounded rep in a set unbounded.
        val one = span(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(20, 40), allCapped(2)))
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
        assertFalse(RomBound.bounded(first, spans, anchors, allCapped(3)), "first rep")
        assertFalse(RomBound.bounded(second, spans, anchors, allCapped(3)), "second rep")
    }

    @Test
    fun `a rep with no accepted anchor after it is unbounded`() {
        // After the last anchor the drift offset is a constant, so the integration
        // error runs to the end of the stream -- the case
        // AccelArtefact.corruptedSpan describes from the same anchor structure.
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(0, 10), allCapped(2)))
    }

    @Test
    fun `a rep with no accepted anchor before it is unbounded`() {
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), intArrayOf(50, 60), allCapped(2)))
    }

    @Test
    fun `a set that accepted no anchor at all bounds nothing`() {
        // VelocitySeries.anchorIndices defaults to empty, and that default is
        // chosen for its failure direction. A hand-built series gets it, and it
        // must read as "nothing is bounded" rather than "everything is".
        val one = span(20, 40)
        assertFalse(RomBound.bounded(one, listOf(one), IntArray(0), BooleanArray(0)))
        assertEquals(listOf(false), RomBound.boundedFlags(listOf(one), IntArray(0), BooleanArray(0)))
    }

    @Test
    fun `a drive-only rep is judged on its drive window alone`() {
        // Its eccentric span is a placeholder inside the drive's own range, so
        // taking the min and the max of the four indices must not widen it.
        val one = driveOnly(20, 40)
        assertTrue(RomBound.bounded(one, listOf(one), intArrayOf(19, 41), allCapped(2)))
        assertFalse(
            RomBound.bounded(one, listOf(one), intArrayOf(21, 41), allCapped(2)),
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
            RomBound.boundedFlags(listOf(a, b, c), intArrayOf(0, 50, 100, 200), allCapped(4)),
        )
        // Remove the anchor at 50 and a and b share 0..100.
        assertEquals(
            listOf(false, false, true),
            RomBound.boundedFlags(listOf(a, b, c), intArrayOf(0, 100, 200), allCapped(3)),
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
