package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [HoldRelease]'s boundaries, on constructed streams.
 *
 * `HoldReleaseFieldTest` says what the decision answers on four real holds.
 * This says where its edges are, which no capture in the corpus reaches:
 * measured over those four, a band anywhere from 0.6 to 2.0 g answers
 * identically on every one of them, and every one of them has its clock start
 * far enough after the capture's first sample that the window's own opening is
 * never tested. A constant nothing can move is a constant nothing is pinning.
 *
 * Fabricated, and stated as such. A still sample here reads exactly 1 g on one
 * axis and zero on the others, which no real unit does; what these assert is
 * the RULE, and the captures assert the outcome.
 */
class HoldReleaseTest {
    /** A sample whose acceleration magnitude is [magnitudeG], on one axis. */
    private fun sample(atMs: Long, magnitudeG: Double): ImuSample = ImuSample(
        timestampMs = atMs,
        axG = magnitudeG,
        ayG = 0.0,
        azG = 0.0,
        wxDps = 0.0,
        wyDps = 0.0,
        wzDps = 0.0,
        rollDeg = 0.0,
        pitchDeg = 0.0,
        yawDeg = 0.0,
    )

    /** A still hold: one sample every 10 ms at exactly 1 g, so deviation is zero. */
    private fun still(fromMs: Long, toMs: Long): List<ImuSample> = (fromMs until toMs step 10L).map { sample(it, 1.0) }

    private val clockStartedAtMs = 1_000_000L

    @Test
    fun `the band is a strict crossing of one gravity`() {
        // Exactly at the band is not a crossing. This is the only pin that
        // fixes BAND_G at 1.0 rather than somewhere in the wide interval the
        // captures leave it: free fall reads 0 g, which is 1.0 of deviation,
        // and a sample that merely reaches it has not yet fallen.
        val atBand = still(clockStartedAtMs, clockStartedAtMs + 10_000L) + sample(clockStartedAtMs + 10_000L, 2.0)
        assertEquals(1.0, HoldRelease.deviationG(sample(0L, 2.0)), "2 g is 1 g of deviation")
        assertNull(HoldRelease.atMs(atBand, clockStartedAtMs), "a sample exactly at the band is not a release")

        val overBand = still(clockStartedAtMs, clockStartedAtMs + 10_000L) + sample(clockStartedAtMs + 10_000L, 2.001)
        assertEquals(
            clockStartedAtMs + 10_000L,
            HoldRelease.atMs(overBand, clockStartedAtMs),
            "a hair over the band is",
        )
        // And from the other side: free fall reads nothing, which is the same
        // 1 g of deviation and also not a crossing on its own.
        assertEquals(1.0, HoldRelease.deviationG(sample(0L, 0.0)), "0 g is 1 g of deviation too")
    }

    @Test
    fun `a crossing inside the settle window moves the window instead of ending the hold`() {
        // The step onto the implement, which field-38 set 18's partner unit
        // shows crossing the band 1.025 s into a 32 s hang. Here it is at 1 s,
        // and a second crossing 2.5 s later -- 3.5 s after the clock but only
        // 2.5 s after the first -- is still inside the settle and is also not
        // the end.
        val onset = still(clockStartedAtMs, clockStartedAtMs + 1_000L) +
            sample(clockStartedAtMs + 1_000L, 6.0) +
            still(clockStartedAtMs + 1_010L, clockStartedAtMs + 3_500L) +
            sample(clockStartedAtMs + 3_500L, 6.0)
        val quietToTheEnd = onset + still(clockStartedAtMs + 3_510L, clockStartedAtMs + 30_000L)
        assertNull(HoldRelease.atMs(quietToTheEnd, clockStartedAtMs), "two crossings, neither settled")

        // Exactly SETTLED_MS of quiet after the last crossing IS settled: the
        // rule is `>=`, so a hold that goes quiet and stays quiet for the
        // window does not need one extra sample of it.
        val settledAtMs = clockStartedAtMs + 3_500L + HoldRelease.SETTLED_MS
        val settled = onset + still(clockStartedAtMs + 3_510L, settledAtMs) +
            sample(settledAtMs, 6.0) +
            still(settledAtMs + 10L, clockStartedAtMs + 30_000L)
        assertEquals(
            settledAtMs,
            HoldRelease.atMs(settled, clockStartedAtMs),
            "a crossing exactly SETTLED_MS after the last one is the release",
        )
        val notQuite = onset + still(clockStartedAtMs + 3_510L, settledAtMs - 1L) +
            sample(settledAtMs - 1L, 6.0) +
            still(settledAtMs + 10L, clockStartedAtMs + 30_000L)
        assertNull(HoldRelease.atMs(notQuite, clockStartedAtMs), "one millisecond short is not")
    }

    @Test
    fun `the window opens at the clock, so the prep cannot end the hold`() {
        // A timed set records a whole prep before its clock starts, and the
        // lifter is moving through it. No capture in the corpus has a crossing
        // in its prep, so without this the skip could be deleted and every
        // committed pin would stay green.
        val stream = still(clockStartedAtMs - 8_000L, clockStartedAtMs - 4_000L) +
            sample(clockStartedAtMs - 4_000L, 6.0) +
            still(clockStartedAtMs - 3_990L, clockStartedAtMs + 20_000L) +
            sample(clockStartedAtMs + 20_000L, 6.0)
        assertEquals(
            clockStartedAtMs + 20_000L,
            HoldRelease.atMs(stream, clockStartedAtMs),
            "the prep's crossing is outside the window and the hold's is inside it",
        )
        // A crossing in the prep does not even open the settle: the release
        // here is 20 s after the clock, and would be the same if the prep had
        // been silent.
        assertEquals(
            HoldRelease.atMs(stream.filter { it.timestampMs >= clockStartedAtMs }, clockStartedAtMs),
            HoldRelease.atMs(stream, clockStartedAtMs),
            "the prep contributes nothing either way",
        )

        // THE CASE THAT ACTUALLY KILLS THE MUTATION, and the first version of
        // this test did not contain it: with the skip deleted, both assertions
        // above still pass, because a prep crossing far enough back leaves the
        // settle satisfied either way. Measured, not reasoned about -- the
        // mutation survived a run.
        //
        // Here the prep crosses 2 s before the clock and the hold crosses 2 s
        // after it. Counting from the clock, that is 2 s of settle and NOT a
        // release. Counting from the prep's crossing it is 4 s and would be one,
        // which would record a two-second hold.
        val tightPrep = still(clockStartedAtMs - 6_000L, clockStartedAtMs - 2_000L) +
            sample(clockStartedAtMs - 2_000L, 6.0) +
            still(clockStartedAtMs - 1_990L, clockStartedAtMs + 2_000L) +
            sample(clockStartedAtMs + 2_000L, 6.0) +
            still(clockStartedAtMs + 2_010L, clockStartedAtMs + 25_000L)
        assertNull(
            HoldRelease.atMs(tightPrep, clockStartedAtMs),
            "a prep crossing opened the settle for a crossing two seconds into the hold",
        )
    }

    @Test
    fun `no clock start and no samples are both absence, not an instant`() {
        val stream = still(clockStartedAtMs, clockStartedAtMs + 10_000L) + sample(clockStartedAtMs + 10_000L, 6.0)
        assertNull(HoldRelease.atMs(stream, clockStartedAtMs = null), "no clock start, no window, no release")
        assertNull(HoldRelease.atMs(emptyList(), clockStartedAtMs), "a set with no samples has nothing to say")
        assertNull(
            HoldRelease.atMs(still(clockStartedAtMs, clockStartedAtMs + 60_000L), clockStartedAtMs),
            "a minute of stillness is a hold, not a release",
        )
    }
}
