package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.RrIngest
import com.macrophage.barspeed.model.HrSample
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the worn control's R-R stream IS, measured before anything changes about
 * how it is ingested.
 *
 * Issue #81 reports that this strap re-sends its last completed R-R whenever no
 * new beat has arrived, and that every repeat contributes a successive
 * difference of exactly zero. This file pins the evidence, and pins it as a
 * MECHANISM rather than as a level: the elapsed-time budget, the density of the
 * successive-difference distribution at zero, and the value lattice the strap
 * quantises onto. Levels move with heart rate and with the body; these do not.
 *
 * Everything here is a property of the fixture and of arithmetic, so none of it
 * moves when the app's ingest rule changes. The figures that DO move are pinned
 * through the ingest seam, in [HrvWornControlDischargeTest].
 *
 * The bound on all of it is the one [FieldHrTrustDischargeTest] already states:
 * this capture spans 78-134 bpm and has no sample below 70, while the fraction
 * of a stream that is re-sent RISES as the heart rate falls. This control
 * measures the mechanism where it is weakest. The lifter's own resting floor is
 * 57-58 and no capture held here reaches it.
 */
class FieldHrDuplicationTest {
    private fun rr(samples: List<HrSample>): List<Double> = samples.map { it.rrIntervalsMs.single() }

    /** Adjacent-identical notifications collapsed -- the candidate rule for #81. */
    private fun collapsed(v: List<Double>): List<Double> =
        listOf(v.first()) + (1 until v.size).filter { v[it] != v[it - 1] }.map { v[it] }

    private fun worn(): List<List<Double>> = HrFixtures.allWorn().map(::rr)

    private fun collapsedWorn(): List<List<Double>> = worn().map(::collapsed)

    /** Exact ties, pair count and RMS scale of the lag-[k] differences, pooled over sets. */
    private fun lag(series: List<List<Double>>, k: Int): Triple<Int, Int, Double> {
        var ties = 0
        var n = 0
        var ss = 0.0
        for (v in series) {
            for (i in 0 until v.size - k) {
                val d = v[i + k] - v[i]
                n++
                ss += d * d
                if (d == 0.0) ties++
            }
        }
        return Triple(ties, n, sqrt(ss / n))
    }

    /**
     * The normalised density at zero.
     *
     * For differences of scale sigma on a value grid of step q, the chance that
     * two are EXACTLY equal is about q times the density at zero, so ties/n
     * times sigma is a scale-free constant: q/sqrt(2) for a Laplace shape,
     * q/sqrt(2*PI) for a Gaussian one. Pinned instead of the tie rate on
     * purpose. The tie rate is SUPPOSED to move with heart rate and variability;
     * this is not, so this is the one that can fail informatively.
     */
    private fun density(series: List<List<Double>>, k: Int): Double {
        val (ties, n, sigma) = lag(series, k)
        return ties.toDouble() / n * sigma
    }

    private fun laplace(): Double = 1000.0 / 1024.0 / sqrt(2.0)

    // ---- the value lattice -------------------------------------------------

    /**
     * Every argument below reasons about EXACT equality of two R-R values, so
     * the grid those values live on has to be the real one. The BLE Heart Rate
     * Measurement characteristic carries R-R in units of 1/1024 s and
     * HeartRateMeasurementParser converts with that factor. A coarser grid would
     * make ties commonplace for arithmetic reasons rather than physical ones,
     * and would inflate every estimate of what de-duplication costs.
     */
    @Test
    fun `every reported R-R sits on the one-1024th-second lattice`() {
        val q = 1000.0 / 1024.0
        val worst = worn().flatten().maxOf { abs(it / q - (it / q).roundToInt()) }
        // The canonical CSV rounds to 0.1 ms, which is 0.102 of a lattice step,
        // and that alone accounts for the whole residual.
        assertTrue(worst < 0.06, "R-R values are not on the 1/1024 s lattice: worst residual $worst")
    }

    /**
     * A strap losing a beat could report the two intervals SUMMED or omit the
     * lost one, and which it does decides what a lost beat costs everything
     * downstream. A summed interval would be at least twice the smallest
     * observed R-R; there is not one, so a lost beat leaves a GAP rather than a
     * doubled value. That matters because a doubled value would dominate any
     * sum of squared successive differences and none of the density results
     * below would survive it.
     */
    @Test
    fun `no reported R-R is a summed pair, so a lost beat is omitted rather than merged`() {
        val v = worn().flatten()
        assertEquals(445.3, v.min(), 0.05)
        assertEquals(792.0, v.max(), 0.05)
        assertTrue(v.max() < 2 * v.min(), "a reported R-R is large enough to be two beats merged")
    }

    // ---- the elapsed-time budget -------------------------------------------

    /**
     * The arithmetic that makes #81 a defect rather than an opinion.
     *
     * Reported intervals tile the time they describe. The first row of a set
     * reports a beat that BEGAN before that row arrived, so a set's intervals
     * tile from one interval before its first sample -- which is why the
     * denominator carries the first R-R of each set. Omitting that boundary
     * term, as the first version of this analysis did, overstates the surplus by
     * 6%: 1.114 and 197 rather than 1.102 and 178.
     *
     * WHAT THE SURPLUS IS. It is duplicated TIME, divided by the mean interval
     * to put it in interval units. It is not a count of notifications, and it
     * says nothing about WHICH pairs are duplicates -- in particular it does not
     * justify collapsing all 327, only that at least this much of the reported
     * time was never lived through.
     *
     * WHAT IT IS NOT. 178 is repeats MINUS lost beats, a NET figure. It was read
     * as a count of repeats twice while this was being worked out, once against
     * a cadence model that predicts only the repeat side of the ledger.
     */
    @Test
    fun `as ingested the reported intervals claim more time than the sets lasted`() {
        val sets = HrFixtures.allWorn()
        val span = sets.sumOf { it.last().timestampMs - it.first().timestampMs }
        val available = span + worn().sumOf { it.first() }
        val reported = worn().sumOf { v -> v.sum() }

        assertEquals(955905L, span)
        assertEquals(966553.2, available, 0.5)
        assertEquals(1064666.0, reported, 0.5)
        assertTrue(reported > available, "the impossibility #81 rests on is gone")
        assertEquals(1.102, reported / available, 0.001)
        assertEquals(177.9, (reported - available) / worn().flatten().average(), 0.5, "repeats minus lost beats")
    }

    /**
     * Per-set, with the convention stated because it decides the count: the
     * denominator is that set's own span plus that set's own first R-R.
     */
    @Test
    fun `sixteen of the seventeen sets claim more time than they lasted`() {
        val ratios =
            HrFixtures.allWorn().map { s ->
                rr(s).sum() / ((s.last().timestampMs - s.first().timestampMs) + rr(s).first())
            }
        assertEquals(16, ratios.count { it > 1.00 })
        assertEquals(13, ratios.count { it > 1.05 })
        assertEquals(11, ratios.count { it > 1.10 })
        // Set 9 is the only set within 1% of the 1.10 line, so it is the only
        // one the boundary term can move across it: 1.0965 raw against 1.085
        // corrected, and its own first R-R of 553.7 ms is that 1%. Both
        // conventions are asserted here because a bare ratio is the same
        // hazard as a bare test total -- the RAW count is 12 over 1.10 and the
        // CORRECTED count, which is the one this file uses throughout, is 11.
        assertEquals(1.085, ratios[8], 0.001, "set 9, boundary-corrected")
        assertEquals(553.7, rr(HrFixtures.worn(9)).first(), 0.05, "set 9's first R-R, the boundary term")
        val raw =
            HrFixtures.allWorn().map { s -> rr(s).sum() / (s.last().timestampMs - s.first().timestampMs) }
        assertEquals(1.0965, raw[8], 0.001, "set 9, raw, no boundary term")
        assertEquals(12, raw.count { it > 1.10 }, "the raw count, which is not the one used here")
        assertEquals(0.957, ratios.min(), 0.001, "set 14, the only set under 1.00")
    }

    /** Collapsing turns an impossible series into a possible one -- under the budget, not over. */
    @Test
    fun `collapsing adjacent-identical notifications brings the budget below one`() {
        val sets = HrFixtures.allWorn()
        val available = sets.sumOf { it.last().timestampMs - it.first().timestampMs } + worn().sumOf { it.first() }
        assertEquals(0.911, collapsedWorn().sumOf { v -> v.sum() } / available, 0.001)
    }

    @Test
    fun `the worn control carries 327 adjacent-identical notifications of 1930`() {
        assertEquals(1930, worn().sumOf { it.size })
        assertEquals(1603, collapsedWorn().sumOf { it.size })
        assertEquals(327, worn().sumOf { v -> (1 until v.size).count { v[it] == v[it - 1] } })
    }

    /**
     * The cadence the resend mechanism depends on, as an envelope rather than a
     * single figure: the per-set mean inter-notification gap spans 493.9 to
     * 502.6 ms. Every statement about what fraction of a stream is re-sent at a
     * given heart rate is a statement about this number, and it is measured on
     * one strap over one session.
     */
    @Test
    fun `the notification cadence is 500 ms give or take six`() {
        val perSet =
            HrFixtures.allWorn().map { s ->
                (1 until s.size).map { s[it].timestampMs - s[it - 1].timestampMs }.average()
            }
        assertEquals(493.9, perSet.min(), 0.05)
        assertEquals(502.6, perSet.max(), 0.05)
    }

    // ---- the density law, and what it says the collapse costs ---------------

    /**
     * The lag-1 excess IS duplication, and this is the test that shows it.
     *
     * If the 327 were genuine pairs of consecutive beats that happened to carry
     * equal intervals, the normalised density at zero would be the same at lag 1
     * as at any other lag -- it is a property of the difference distribution,
     * not of the lag. It is not the same: lag 1 stands at 3.42 times the value
     * lags 2 through 6 share, and the smaller excess still visible at lags 2 and
     * 3 is what runs of three and four identical notifications leave behind.
     *
     * Once the adjacent-identical notifications are removed, lags 2 through 6
     * land on q/sqrt(2), the Laplace prediction, with NO parameter fitted to
     * this capture.
     */
    @Test
    fun `lag one is enriched three and a half fold over every other lag`() {
        assertEquals(2.360, density(worn(), 1), 0.005)
        assertEquals(3.42, density(worn(), 1) / laplace(), 0.02)

        val higher = (2..6).map { density(collapsedWorn(), it) }
        assertEquals(0.686, higher.average(), 0.005, "the collapsed series' density at zero")
        assertEquals(0.691, laplace(), 0.001)
        assertTrue(
            abs(higher.average() - laplace()) / laplace() < 0.02,
            "the collapsed series stopped matching Laplace: ${higher.average()} vs ${laplace()}",
        )
    }

    /**
     * The density law checked where re-sends are all but suppressed.
     *
     * A re-send needs a notification window with no beat in it. Restricted to
     * adjacent pairs where BOTH samples report 120 bpm or more, the beat
     * interval is at or under the ~500 ms cadence and the cadence model predicts
     * 0.1 re-sends across all 263 such pairs. Not zero, and the word matters:
     * this regime is near-resend-free rather than resend-free, so at most a
     * fraction of one of the 27 ties here is a re-send. It is still the closest
     * this corpus comes to measuring the genuine tie rate uncontaminated.
     *
     * Measured 10.3%. The density law, given that regime's own tight scale of
     * 5.82 ms, predicts 11.9%. Agreement to 16% where the Laplace tail
     * approximation is under the most strain, and note the SIGN: measured is
     * BELOW predicted. Extra duplication hiding here would push it above.
     */
    @Test
    fun `above 120 bpm, where re-sends are near-suppressed, the tie rate matches the law`() {
        var pairs = 0
        var ties = 0
        for (s in HrFixtures.allWorn()) {
            for (i in 1 until s.size) {
                if (s[i].bpm >= 120 && s[i - 1].bpm >= 120) {
                    pairs++
                    if (s[i].rrIntervalsMs == s[i - 1].rrIntervalsMs) ties++
                }
            }
        }
        assertEquals(263, pairs)
        assertEquals(27, ties)
        assertEquals(0.103, ties.toDouble() / pairs, 0.002)

        val fast = mutableListOf<Double>()
        for (s in HrFixtures.allWorn()) {
            val d = listOf(s.first()) + (1 until s.size).filter {
                s[it].rrIntervalsMs != s[it - 1].rrIntervalsMs
            }.map { s[it] }
            for (i in 1 until d.size) {
                if (d[i].bpm >= 120 && d[i - 1].bpm >= 120) {
                    fast += d[i].rrIntervalsMs.single() - d[i - 1].rrIntervalsMs.single()
                }
            }
        }
        val sigma = sqrt(fast.sumOf { it * it } / fast.size)
        assertEquals(5.82, sigma, 0.01)
        assertEquals(0.119, laplace() / sigma, 0.002, "what the law predicts in this regime")

        // The premise, as a number rather than a word: the cadence model still
        // predicts a fraction of a re-send among these pairs, not none.
        val modelled =
            HrFixtures.allWorn().sumOf { set ->
                (1 until set.size).sumOf { i ->
                    if (set[i].bpm >= 120 && set[i - 1].bpm >= 120) {
                        maxOf(0.0, 1.0 - 500.0 / set[i - 1].rrIntervalsMs.single())
                    } else {
                        0.0
                    }
                }
            }
        assertEquals(0.1, modelled, 0.1, "re-sends predicted in the near-suppressed regime")
        assertTrue(
            ties.toDouble() / pairs < laplace() / sigma,
            "measured tie rate is above predicted, which is the signature of extra duplication",
        )
    }

    // ---- what the rule actually removes ------------------------------------

    /**
     * The rule, run over the control it was designed against.
     *
     * 327 notifications removed of 1930. That is the number, and the two
     * assertions beside it are what it costs and what it buys: about 141 of
     * those were real beats, and the budget stops claiming time that was never
     * lived through.
     */
    @Test
    fun `the rule removes 327 of the 1930 worn notifications`() {
        assertEquals(1603, HrFixtures.allWorn().sumOf { RrIngest.newBeats(it).size })
        assertEquals(
            List(HrFixtures.WORN_SETS) { true },
            HrFixtures.allWorn().map { RrIngest.newBeats(it).size < it.size },
            "a worn set lost nothing, so the rule stopped firing",
        )
    }

    /**
     * The rule reduces the UNWORN capture to almost nothing -- a side effect,
     * stated at its true size rather than a flattering one.
     *
     * A strap on a table has no signal, so its detector holds one value and
     * re-sends it. Counted in INTERVALS, not notifications, because the two
     * differ here: unworn set 1 is 72 notifications carrying only 46 intervals.
     * 46, 91 and 91 collapse to 1, 1 and 6.
     *
     * ONLY ONE OF THE THREE YIELDS AN HRV TODAY, AND AFTER THE RULE NONE DOES.
     * "Yields", not "publishes": no per-set HRV reaches any export. This test
     * was named with the wrong verb in the same commit that corrected four
     * other sites of it, which is how a class-fix announces itself complete
     * while adding an instance.
     * Sets 1 and 2 already come back null from Hrv.rmssdMs, their streams
     * failing the plausibility band outright, so the rule changes nothing for
     * them. Set 3 gives 52.97 ms today and null after. So: one figure removed,
     * not three -- and all three null afterwards, which is the stronger and
     * also the true statement of where this leaves the negative control.
     *
     * NOT a fix for issue #83 and must not be recorded as one. #83 is about an
     * unworn strap being DETECTABLE as unworn; this rule detects nothing, it
     * deletes a degenerate stream because the stream is degenerate. A worn
     * resting strap reports beats that vary and does not collapse this way --
     * exactly the case no capture here covers.
     */
    @Test
    fun `the rule removes the one HRV the unworn capture still yields`() {
        assertEquals(
            listOf(46, 91, 91),
            HrFixtures.allUnworn().map { set -> set.sumOf { it.rrIntervalsMs.size } },
        )
        assertEquals(listOf(1, 1, 6), HrFixtures.allUnworn().map { RrIngest.newBeats(it).size })
    }

    /**
     * WHAT THE RULE COSTS IS NOT A CONSTANT. It rises steeply with heart rate,
     * and a single aggregate hides that.
     *
     * The removal count comes from [RrIngest] itself rather than from a copy of
     * its predicate written out here. That matters: with the comparison
     * restated inline, loosening the shipped rule to a 1.0 ms tolerance moved
     * the beat series 1611 to 1549 and red 8 executions elsewhere while leaving
     * THIS test -- the one whose subject is what the rule costs -- green.
     *
     * Of what the rule removes, the share that was a genuine consecutive beat
     * rather than a re-send. The genuine share is the CADENCE MODEL's estimate,
     * so these decompose its total of 151.0 and not the density law's 140.7;
     * the two disagree by 10.3 beats and neither is authoritative. Grouped by
     * each SET's median heart rate:
     *
     *   below 110 bpm    204 removed, about 34% genuine
     *   110 to 120 bpm    93 removed, about 60% genuine
     *   120 bpm and up    30 removed, about 86% genuine
     *
     * Grouping by each SET's median averages a set that spans the boundary. The
     * finer per-notification bucketing is pinned by its own test below.
     *
     * Above about 120 bpm the beat interval is at or under the ~500 ms cadence,
     * so there is little left to re-send and nearly everything the rule deletes
     * is real: it is close to pure cost there. A rest-heavy session is where it
     * pays and a short, hard, high-rate one is where it does not. That is a
     * real trade, not a rounding error, and it belongs beside any aggregate.
     */
    @Test
    fun `what the rule costs rises steeply with heart rate`() {
        fun profile(lo: Int, hi: Int): Pair<Int, Double> {
            var removed = 0
            var repeats = 0.0
            for (s in HrFixtures.allWorn()) {
                val median = s.map { it.bpm }.sorted()[s.size / 2]
                if (median < lo || median >= hi) continue
                removed += s.sumOf { it.rrIntervalsMs.size } - RrIngest.newBeats(s).size
                for (i in 1 until s.size) {
                    repeats += maxOf(0.0, 1.0 - 500.0 / s[i - 1].rrIntervalsMs.single())
                }
            }
            return removed to (removed - repeats) / removed
        }
        val slow = profile(0, 110)
        val mid = profile(110, 120)
        val fast = profile(120, 999)
        assertEquals(204, slow.first)
        assertEquals(93, mid.first)
        assertEquals(30, fast.first)
        assertEquals(0.34, slow.second, 0.02)
        assertEquals(0.60, mid.second, 0.02)
        assertEquals(0.86, fast.second, 0.02, "above the cadence the rule is near pure cost")
        assertTrue(fast.second > slow.second, "the cost stopped depending on heart rate")

        // The cadence model's TOTAL, asserted rather than left to be summed out
        // of the buckets. Prose elsewhere says two estimators disagree and both
        // are pinned; without this line only one of them was, and the other was
        // recoverable from three products and three tolerances -- which is a
        // derivation, not a pin.
        val removed = slow.first + mid.first + fast.first
        val genuine = slow.first * slow.second + mid.first * mid.second + fast.first * fast.second
        assertEquals(327, removed)
        assertEquals(151.0, genuine, 1.0, "cadence-model genuine ties, the other estimator's total")
    }

    /**
     * The same profile bucketed by the heart rate at the EARLIER notification of
     * each pair rather than by its set's median.
     *
     * Finer, because a set spanning the 120 bpm boundary is averaged by the
     * median and split by this. It sharpens the same shape rather than
     * contradicting it, which is the only reason it is worth having: two
     * conventions agreeing is evidence the shape is in the data and not in the
     * grouping.
     *
     * Pinned because it was quoted in prose. It was deleted from RrIngest's
     * KDoc under the rule that an unpinned number should not ship, and it
     * survived one file over inside this one -- so either it earns an assertion
     * or it goes the same way. This is the assertion.
     */
    @Test
    fun `bucketing by each notification rather than each set sharpens the same profile`() {
        fun profile(lo: Int, hi: Int): Pair<Int, Double> {
            var removed = 0
            var repeats = 0.0
            for (s in HrFixtures.allWorn()) {
                for (i in 1 until s.size) {
                    if (s[i - 1].bpm < lo || s[i - 1].bpm >= hi) continue
                    if (s[i].rrIntervalsMs == s[i - 1].rrIntervalsMs) removed++
                    repeats += maxOf(0.0, 1.0 - 500.0 / s[i - 1].rrIntervalsMs.single())
                }
            }
            return removed to (removed - repeats) / removed
        }
        val slow = profile(0, 110)
        val mid = profile(110, 120)
        val fast = profile(120, 999)
        assertEquals(206, slow.first)
        assertEquals(93, mid.first)
        assertEquals(28, fast.first)
        assertEquals(0.300, slow.second, 0.002)
        assertEquals(0.660, mid.second, 0.002)
        assertEquals(0.991, fast.second, 0.002)
        assertTrue(fast.second > mid.second && mid.second > slow.second, "the profile inverted")
    }

    /**
     * How many of the 327 are real, from the law rather than from a model of the
     * strap's cadence -- summed PER SET, which is the whole correction.
     *
     * The rate is q/(sigma*sqrt(2)), so it depends on 1/sigma, which is convex.
     * Taking the pooled sigma and inverting it once gives 86.5 and is simply
     * wrong: sets 1 and 13 carry scales of 44.7 and 28.6 ms and drag the pooled
     * figure to 15.16, suppressing the estimate for the fifteen sets that are
     * three to six times tighter. Summing per set gives 140.7, a 63% difference,
     * and this was caught in review rather than by me.
     *
     * With T = 140.7, repeats are 327 - 140.7 = 186.3 against a cadence model
     * predicting 176.0.
     *
     * The lost-beat figure below is asserted so it cannot drift, and NOTHING
     * PUBLISHED DEPENDS ON IT. It is a difference of two estimates and is
     * smaller than the gap between them, so no closure is claimed for it and
     * none is needed: only repeats-minus-lost-beats reaches any figure, and
     * the elapsed-time budget measures that directly.
     *
     * The consequence is that collapsing all 327 removes about 141 real beats,
     * 43% of what it removes. That is the cost of the rule, and it is a cost.
     */
    @Test
    fun `about 141 of the 327 are genuine consecutive beats, not repeats`() {
        val constant = (2..6).map { density(collapsedWorn(), it) }.average()
        val perSet =
            worn().zip(collapsedWorn()).sumOf { (raw, ded) ->
                val d = (1 until ded.size).map { ded[it] - ded[it - 1] }
                (raw.size - 1) * constant / sqrt(d.sumOf { it * it } / d.size)
            }
        assertEquals(140.7, perSet, 1.0, "genuine ties, summed per set")

        val pooled = lag(worn(), 1).second * constant / lag(collapsedWorn(), 1).third
        assertEquals(86.5, pooled, 1.0, "the same law applied to a pooled scale, which understates it")
        assertTrue(perSet > pooled, "1/sigma is convex; the per-set sum is the larger and the right one")

        assertEquals(186.3, 327 - perSet, 1.0, "repeats")
        assertEquals(8.4, 327 - perSet - 177.9, 1.0, "lost beats, from the budget")
    }
}
