package com.macrophage.barspeed.hrm

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrvTest {
    @Test
    fun `rmssd of alternating series matches hand calculation`() {
        // 800/850 alternation: every successive diff is 50 ms → RMSSD = 50.
        val rr = List(40) { if (it % 2 == 0) 800.0 else 850.0 }
        val rmssd = Hrv.rmssdMs(rr)!!
        assertTrue(abs(rmssd - 50.0) < 1e-9, "rmssd $rmssd")
    }

    @Test
    fun `steady series has near-zero rmssd and sdnn`() {
        val rr = List(30) { 900.0 }
        assertEquals(0.0, Hrv.rmssdMs(rr)!!, 1e-9)
        assertEquals(0.0, Hrv.sdnnMs(rr)!!, 1e-9)
    }

    @Test
    fun `artifacts are rejected before computing`() {
        // A dropped-beat artifact (1700 ms ≈ two beats) would wreck RMSSD.
        val rr = List(30) { 800.0 + (it % 2) * 20.0 }
        val withArtifacts = rr.toMutableList().apply {
            add(15, 1700.0)
            add(5, 120.0)
        }
        val cleanRmssd = Hrv.rmssdMs(rr)!!
        val dirtyRmssd = Hrv.rmssdMs(withArtifacts)!!
        assertTrue(abs(cleanRmssd - dirtyRmssd) < 2.0, "artifact leaked: $dirtyRmssd vs $cleanRmssd")
    }

    @Test
    fun `too few beats returns null`() {
        assertNull(Hrv.rmssdMs(List(5) { 800.0 }))
        assertNull(Hrv.sdnnMs(List(3) { 800.0 }))
    }

    @Test
    fun `sdnn matches population standard deviation`() {
        val rr = listOf(780.0, 820.0, 800.0, 790.0, 810.0, 805.0, 795.0, 800.0, 815.0, 785.0, 800.0)
        val mean = rr.average()
        val expected = sqrt(rr.map { (it - mean) * (it - mean) }.average())
        assertEquals(expected, Hrv.sdnnMs(rr, minIntervals = 10)!!, 1e-9)
    }

    // ---- issue #27: a dropout must not poison the rest of the stream -------

    /**
     * The exact step #27 was filed against: 60 beats near 857 ms (70 bpm),
     * then a dropout that reconnects at 571 ms (105 bpm, a 33% step) for 400
     * beats. Today every reconnected beat is compared against the frozen
     * 857 ms reference and none of them is ever within 25% of it, so the
     * chain never recovers and the published figure is the warm-up's own
     * RMSSD. Deterministic jitter, not random, so this is reproducible.
     */
    @Test
    fun `a dropout that reconnects at a different heart rate recovers the reconnected beats`() {
        val warm = List(60) { 857.0 + (it % 3 - 1) * 10.0 }
        val reconnected = List(400) { 571.0 + (it % 3 - 1) * 8.0 }
        val rr = warm + reconnected
        val segments = Hrv.segments(rr)
        assertEquals(2, segments.size, "expected one re-anchor at the reconnection")
        assertEquals(60, segments[0].size, "the warm-up segment should be untouched")
        assertTrue(segments[1].size >= 395, "reconnected beats were not recovered: ${segments[1].size} of 400")
        assertTrue(Hrv.rmssdMs(rr) != null, "a recovered 400-beat segment should not read as too little data")
    }

    /**
     * A single implausible seed beat (1900 ms, inside the 300-2000 ms
     * plausibility band so it is never filtered) followed by 50 clean beats.
     * Today the seed becomes the chain's unconditional first reference and
     * nothing after it is ever within 25% of 1900 ms, so rmssdMs returns
     * null for an otherwise entirely clean session.
     */
    @Test
    fun `an artifactual first beat does not null out the rest of a clean session`() {
        val rr = listOf(1900.0) + List(50) { 800.0 + (it % 3 - 1) * 6.0 }
        assertTrue(Hrv.rmssdMs(rr) != null, "one bad seed should not poison 50 good beats behind it")
    }

    /**
     * The confirmation test directly: a rejected candidate (1300 ms against
     * a running 800 ms reference) is discarded, but the very next raw beat
     * (1310 ms) is close enough to IT to confirm a genuine shift, and a new
     * segment starts there -- not at the rejected 1300 ms itself. The
     * rejected candidate is evidence a shift happened; it is never used as
     * data (issue #27's review: a single misdetection can split one true
     * beat into two implausible-looking halves, and neither half should
     * enter the recovered segment).
     */
    @Test
    fun `two distinct disagreeing beats confirm a re-anchor, anchored on the second`() {
        val rr = List(10) { 800.0 } + listOf(1300.0, 1310.0) + List(10) { 1305.0 }
        val segments = Hrv.segments(rr)
        assertEquals(listOf(10, 11), segments.map { it.size })
        assertEquals(1310.0, segments[1].first(), "new segment must start at the confirming beat, not the rejected one")
    }

    /**
     * NOT a red differential -- this is vacuously true today, because
     * segments() never splits anything yet, for any input. Its value is as
     * a mutation detector for the distinctness guard added with the fix:
     * deleting `next != v` from the confirmation test is predicted to red
     * this assertion (see the fix commit for the measured result), because
     * a strap that re-sends its last completed R-R at a fixed cadence
     * (issue #81) would otherwise supply its own confirmation for free.
     */
    @Test
    fun `a resent duplicate cannot confirm a re-anchor on its own`() {
        val rr = List(20) { 800.0 } + listOf(1300.0, 1300.0) + List(20) { 800.0 }
        assertEquals(1, Hrv.segments(rr).size, "a duplicated candidate re-anchored the series")
    }
}
