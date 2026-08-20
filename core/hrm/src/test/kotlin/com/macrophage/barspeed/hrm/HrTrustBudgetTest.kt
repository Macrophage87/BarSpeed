package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HrTrust.accountedFraction] on synthetic streams, where the arithmetic can be
 * checked by hand. The real discharge is against the worn control in
 * :core:data; this pins the shape.
 */
class HrTrustBudgetTest {
    /**
     * A strap reporting every beat: intervals tile the span.
     *
     * The intervals ALTERNATE by one lattice step rather than repeating a
     * single value, because a real heart varies and the de-duplication this
     * budget is built on collapses consecutive identical values. Written with a
     * constant interval first, which produced a stream of exactly one distinct
     * beat and no budget at all -- a fixture describing a metronome, not a
     * heart.
     */
    private fun tiling(beats: Int, rrMs: Double): List<HrSample> {
        val out = mutableListOf<HrSample>()
        var at = 0.0
        for (i in 0 until beats) {
            val rr = if (i % 2 == 0) rrMs else rrMs + 0.9765625
            at += rr
            out += HrSample(timestampMs = at.toLong(), bpm = (60000.0 / rr).toInt(), rrIntervalsMs = listOf(rr))
        }
        return out
    }

    /** A strap holding one value and re-sending it at a fixed cadence. */
    private fun held(reports: Int, rrMs: Double, cadenceMs: Long): List<HrSample> = (0 until reports).map { i ->
        HrSample(timestampMs = i * cadenceMs, bpm = (60000.0 / rrMs).toInt(), rrIntervalsMs = listOf(rrMs))
    }

    @Test
    fun `a stream whose beats tile its span accounts for all of it`() {
        val fraction = HrTrust.accountedFraction(tiling(beats = 40, rrMs = 800.0))
        assertEquals(1.0, fraction!!, 0.01, "tiling beats must account for the whole span")
        assertTrue(HrTrust.tracksAHeart(tiling(40, 800.0)))
    }

    /**
     * The rate-freeness, as an assertion rather than a claim: the same tiling
     * stream at 40 bpm and at 180 bpm accounts for the same fraction. This is
     * the property the two withdrawn rules did not have.
     */
    @Test
    fun `the fraction does not move with heart rate`() {
        val slow = HrTrust.accountedFraction(tiling(40, 1500.0))!!
        val fast = HrTrust.accountedFraction(tiling(40, 333.0))!!
        assertEquals(slow, fast, 0.01, "the fraction moved with heart rate, which is the failure mode")
    }

    /**
     * A single value re-sent for thirty seconds collapses to ONE beat, so there
     * is no budget to take and the stream says nothing. Withheld by the
     * cannot-say branch rather than by the fraction -- two routes, one outcome,
     * and worth separating because the real unworn capture takes the other one.
     */
    @Test
    fun `a value held and re-sent for thirty seconds produces no budget at all`() {
        val stream = held(reports = 60, rrMs = 1600.0, cadenceMs = 500L)
        assertNull(HrTrust.accountedFraction(stream), "a single repeated value produced a budget")
        assertTrue(HrTrust.tracksAHeart(stream), "a stream with no budget must not be silenced on that alone")
    }

    /**
     * The shape the REAL unworn capture takes: a detector that has lost contact
     * emits a handful of distinct values over a long span, so a budget exists
     * and is far too small. Unworn set 3 scores 0.171 this way, against a worn
     * range of 0.858 to 0.963; this is the synthetic equivalent.
     */
    @Test
    fun `a few distinct values over a long span account for almost nothing`() {
        val stream =
            List(20) { HrSample(it * 500L, 37, listOf(1600.0)) } +
                List(20) { HrSample(10_000L + it * 500L, 46, listOf(1300.0)) } +
                List(20) { HrSample(20_000L + it * 500L, 60, listOf(1000.0)) }
        val fraction = HrTrust.accountedFraction(stream)
        assertTrue(fraction!! < HrTrust.MIN_ACCOUNTED_FRACTION, "a lost detector scored $fraction")
        assertFalse(HrTrust.tracksAHeart(stream))
    }

    /**
     * ISSUE #82 IS DELIBERATELY NOT COVERED, and this test is where that
     * decision is recorded rather than left to be inferred from its absence.
     *
     * Silencing on the ABSENCE of R-R evidence would have fallen out of the
     * same predicate for free, and it was written that way first. It is too
     * broad: "no budget" is true of an unworn strap holding one value, and
     * equally true of a genuinely short set, of a strap that connected a second
     * before the set ended. Measured by counterfactual it changed 8 tests --
     * 5 that already existed, most about minBpm plumbing and nothing to do with
     * wear, and 3 written in the same change. An earlier version of this
     * sentence said "eight existing", which double-counts the new ones.
     *
     * It is also a weaker claim. Silencing a stream on a budget it
     * demonstrably fails rests on one observed instance; silencing one on
     * missing R-R rests on zero. Bundling them would have hidden the weaker
     * inside the stronger.
     */
    @Test
    fun `a stream with no R-R intervals produces no budget, and is NOT silenced`() {
        val stream = (0 until 60).map { HrSample(it * 500L, 46, emptyList()) }
        assertNull(HrTrust.accountedFraction(stream))
        assertTrue(HrTrust.tracksAHeart(stream), "issue #82 is deliberately not covered here")
    }

    @Test
    fun `a stream with fewer than two beats produces no budget`() {
        assertNull(HrTrust.accountedFraction(emptyList()))
        assertNull(HrTrust.accountedFraction(listOf(HrSample(0L, 60, listOf(1000.0)))))
        assertTrue(HrTrust.tracksAHeart(emptyList()), "an empty stream is silenced by summarize, not by this")
    }

    /** Untrusted samples are excluded before the budget is taken, not after. */
    @Test
    fun `zero-bpm samples do not enter the budget`() {
        val stream = tiling(40, 800.0) + HrSample(999_999L, 0, listOf(800.0))
        assertEquals(1.0, HrTrust.accountedFraction(stream)!!, 0.01, "an untrusted sample stretched the span")
    }

    /**
     * The guard, and the number that justifies it: below three distinct beats
     * the budget is dominated by one interval's straddle. The worst two-beat
     * window anywhere in the worn control scores 0.3551 against a cut of 0.35 --
     * a margin of 1.015x. At three it is 0.4547.
     */
    @Test
    fun `two distinct beats are not enough to judge on`() {
        val two = tiling(beats = 2, rrMs = 800.0)
        assertNull(HrTrust.accountedFraction(two), "a two-beat stream was judged")
        assertTrue(HrTrust.tracksAHeart(two), "and it must not be silenced for it")
        assertEquals(3, HrTrust.MIN_DISTINCT_BEATS)
    }

    @Test
    fun `three distinct beats are enough, which is what the unworn set has`() {
        assertNotNull(HrTrust.accountedFraction(tiling(beats = 3, rrMs = 800.0)))
    }

    /**
     * THE BOUND, as arithmetic rather than as prose. Silencing needs the tie
     * rate q/(sigma*sqrt(2)) to reach 0.65, so sigma at or below 1.06 ms on the
     * closed form alone. The lowest sigma of any worn set in the corpus is
     * 6.58 ms.
     *
     * This is the claim the whole resting argument now rests on, so it is a
     * test rather than a sentence: a sigma of 1 ms is a metronome, not a heart.
     */
    @Test
    fun `silencing requires a variability no heart has`() {
        fun fractionAt(sigmaMs: Double) = HrTrust.tieOnlyFraction(sigmaMs)
        assertEquals(0.35, fractionAt(1.062), 0.005, "the sigma at which the cut bites")
        assertTrue(fractionAt(6.58) > 0.88, "the hardest worn set in the corpus")
        assertTrue(fractionAt(17.7) > 0.96, "the median rest stream of the two 0.1.40 captures")
        assertTrue(
            fractionAt(6.58) > HrTrust.MIN_ACCOUNTED_FRACTION,
            "the corpus minimum stopped clearing the cut",
        )
    }

    /**
     * THE SEAM ITSELF: [HrTrust.silencingSigmaMs] is a derivation, not a digit,
     * and this is what makes it one. Move either input and the bound moves the
     * way the algebra says it does.
     *
     * The check that would otherwise be missing is the round trip. A bound
     * asserted only against a hardcoded expectation cannot tell a correct
     * derivation from a coincidence; feeding it back through
     * [HrTrust.tieOnlyFraction] can.
     */
    @Test
    fun `the bound is the sigma at which the cut and the drop term meet`() {
        val bound = HrTrust.silencingSigmaMs()
        assertEquals(
            HrTrust.MIN_ACCOUNTED_FRACTION,
            HrTrust.tieOnlyFraction(bound) - HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY,
            1e-9,
            "the bound is not where the cut and the carried drop term meet",
        )
        assertTrue(
            bound > HrTrust.RR_QUANTUM_MS / ((1.0 - HrTrust.MIN_ACCOUNTED_FRACTION) * kotlin.math.sqrt(2.0)),
            "carrying a drop term must make the bound WORSE than the closed form alone, not better",
        )
        assertTrue(bound < 2.0, "the bound left the metronome end entirely: $bound")
    }

    /**
     * The quantum is the strap's, not ours. 1/1024 s is what the Bluetooth
     * Heart Rate Measurement characteristic specifies R-R intervals in, and
     * every tie-rate figure in this branch divides by it.
     */
    @Test
    fun `the reported interval quantum is one over ten twenty four of a second`() {
        assertEquals(1000.0 / 1024.0, HrTrust.RR_QUANTUM_MS, 1e-12)
        assertEquals(0.9765625, HrTrust.RR_QUANTUM_MS, 1e-12)
    }

    @Test
    fun `the cut sits where the harm asymmetry puts it`() {
        assertEquals(0.35, HrTrust.MIN_ACCOUNTED_FRACTION, 1e-9)
    }
}
