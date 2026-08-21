package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.hrm.RrIngest
import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HrTrust] run against the sessions 26 and 28 captures, before it is wired
 * to anything, plus two assertions that reach every worn stream held here.
 *
 * The worn control is the point of this file. Two earlier candidate rules for
 * this defect looked correct against the unworn capture and were withdrawn only
 * once they were run against session 26, where each fired on 16 of 17 real
 * sets. The discharge is executable here rather than quoted in a commit message
 * so that it is re-run on every push instead of being true once.
 *
 * The bound on what the WORN CONTROL can show is asserted below rather than
 * left to a reader: session 26 has NO sample under 70 bpm, so it is silent
 * about a resting athlete. It can demonstrate that a rule costs nothing on a
 * working lifter. It cannot demonstrate that a rule is safe at rest, and one
 * earlier rule was accepted on exactly that confusion. Two assertions here do
 * reach every worn stream in this directory, including the captures that go
 * below 70 bpm, but they read SIGMA and the residual from them; what those
 * captures show about heart RATE is asserted in [FieldHrRestingBandTest].
 */
class FieldHrTrustDischargeTest {
    /** [SessionRepository.recordSet]'s summary as it stands before this change. */
    private fun ungated(samples: List<HrSample>): Triple<Int?, Int?, Int?> {
        val bpm = samples.map { it.bpm }
        return Triple(
            samples.lastOrNull()?.bpm,
            if (bpm.isEmpty()) null else bpm.average().toInt(),
            bpm.maxOrNull(),
        )
    }

    // ---- the worn control: what the rule costs a real session --------------

    @Test
    fun `not one sample of the worn control is rejected`() {
        val perSet = HrFixtures.allWorn().map { set -> set.count { !HrTrust.isTrusted(it) } }
        assertEquals(List(HrFixtures.WORN_SETS) { 0 }, perSet, "a worn sample was called untrustworthy")

        val total = HrFixtures.allWorn().sumOf { it.size }
        assertEquals(1930, total, "the worn control changed size")
    }

    @Test
    fun `no worn set loses its summary, and none loses its end-of-set reading`() {
        HrFixtures.allWorn().forEachIndexed { index, set ->
            val summary = HrTrust.summarize(set)
            assertNotNull(summary.avgBpm, "worn set ${index + 1} lost its mean")
            assertNotNull(summary.maxBpm, "worn set ${index + 1} lost its maximum")
            assertNotNull(summary.minBpm, "worn set ${index + 1} lost its minimum")
            assertNotNull(summary.endOfSetBpm, "worn set ${index + 1} lost its end-of-set reading")
            assertEquals(set.size, summary.trustedSamples, "worn set ${index + 1} lost samples")
        }
    }

    /**
     * And the seventeen worn sets publish exactly what they published before.
     * This is the assertion that would have stopped the two rules withdrawn
     * before this one; it is cheap to write and it is the whole gate.
     */
    @Test
    fun `every worn figure is identical to the one published today`() {
        HrFixtures.allWorn().forEachIndexed { index, set ->
            val summary = HrTrust.summarize(set)
            assertEquals(
                ungated(set),
                Triple(summary.endOfSetBpm, summary.avgBpm, summary.maxBpm),
                "worn set ${index + 1} would be published differently",
            )
        }
    }

    /**
     * The blind spot, pinned so that it is re-read rather than remembered.
     *
     * What actually trips this is [HrFixtures.WORN_SETS], not the resources
     * directory. `allWorn()` iterates `1..WORN_SETS`, so dropping an
     * eighteenth capture into `src/test/resources` and stopping there changes
     * nothing here: the file is never opened, this stays green, and the new
     * capture is a silent no-op. Adding a worn capture means adding the file
     * AND incrementing WORN_SETS, and only the second half is what makes this
     * assertion speak.
     *
     * Written the other way round first -- "if a capture containing
     * resting-rate heart rate is ever added to these resources, this assertion
     * fails" -- which was a statement about what would happen rather than
     * about what the code checks. It was false: a resting fixture was dropped
     * into the resources directory under two different names and this test
     * stayed green both times. In the one file whose declared job is honesty
     * about its own limits, that is the worst place to have got it wrong.
     */
    @Test
    fun `the worn control contains no resting heart rate at all`() {
        val bpm = HrFixtures.allWorn().flatten().map { it.bpm }
        assertEquals(78, bpm.min(), "the worn control's slowest sample")
        assertEquals(134, bpm.max())
        assertEquals(0, bpm.count { it < 70 }, "the worn control now has resting-rate samples")
    }

    // ---- the elapsed-time budget, discharged against the whole control -----

    /**
     * WHAT THE RULE COSTS THE WORN CONTROL: nothing, and this is the assertion
     * that decides whether it may ship at all. Two rules before it were
     * withdrawn only once run against these seventeen sets.
     */
    @Test
    fun `every worn set accounts for its own elapsed time, and none is withheld`() {
        val fractions = HrFixtures.allWorn().map { HrTrust.accountedFraction(it)!! }
        assertEquals(0.858, fractions.min(), 0.002, "the worst worn set")
        assertEquals(0.963, fractions.max(), 0.002, "the best worn set")
        assertTrue(
            fractions.all { it >= HrTrust.MIN_ACCOUNTED_FRACTION },
            "a worn set would be silenced: ${fractions.filter { it < HrTrust.MIN_ACCOUNTED_FRACTION }}",
        )
        assertEquals(
            List(HrFixtures.WORN_SETS) { true },
            HrFixtures.allWorn().map { HrTrust.tracksAHeart(it) },
        )
        assertEquals(2.45, fractions.min() / HrTrust.MIN_ACCOUNTED_FRACTION, 0.02, "the margin the cut buys")
    }

    /**
     * And what it reaches: the one unworn set that still publishes. Firing
     * evidence is n=1, which is the whole of what this corpus can offer.
     */
    @Test
    fun `the unworn set that still publishes accounts for almost none of its time`() {
        val fraction = HrTrust.accountedFraction(HrFixtures.unworn(3))!!
        assertEquals(0.171, fraction, 0.002)
        assertTrue(fraction < HrTrust.MIN_ACCOUNTED_FRACTION)
        assertFalse(HrTrust.tracksAHeart(HrFixtures.unworn(3)))
        assertEquals(2.05, HrTrust.MIN_ACCOUNTED_FRACTION / fraction, 0.03, "the margin on the other side")
    }

    /**
     * THE MECHANISM CLAIM THAT WAS HERE IS DELETED, not reworded.
     *
     * It asserted that the fastest worn sets score worst, because a shortfall
     * needed dropped beats and a drop needed a heart above 120 bpm. Ten of the
     * seventeen worn sets contain no interval under 500 ms at all -- no
     * collision is possible in them -- and every one still falls short. This
     * pins the falsification so the claim cannot come back.
     */
    @Test
    fun `sets in which no beat collision is possible still fall short`() {
        val noCollision =
            HrFixtures.allWorn().filter { set ->
                set.filter(HrTrust::isTrusted).none { it.rrIntervalsMs.single() < 500.0 }
            }
        assertEquals(10, noCollision.size, "how many worn sets cannot drop a beat at all")
        assertTrue(
            noCollision.all { HrTrust.accountedFraction(it)!! < 1.0 },
            "a set with no possible collision accounted for all its time, so drops could explain it",
        )
    }

    /**
     * THE MECHANISM THAT LOOKS RIGHT AND IS NOT, pinned so its numbers cannot
     * drift into looking better than they are.
     *
     * Tie-removal alone -- the obvious replacement for the falsified drop
     * story -- predicts the MEAN shortfall almost exactly and the ORDERING not
     * at all. Worn set 08 is among the least variable sets, so it is predicted
     * to fall shortest; it falls shortest by the least. A mechanism
     * whose average is right and whose ordering is backwards is a fit wearing a
     * derivation's clothes, and this is here because the numbers are tempting.
     */
    @Test
    fun `tie removal predicts the mean shortfall and not the ordering`() {
        val observed = mutableListOf<Double>()
        val predicted = mutableListOf<Double>()
        HrFixtures.allWorn().forEach { set ->
            val trusted = set.filter(HrTrust::isTrusted)
            val beats = RrIngest.newBeats(trusted)
            val tieRate = HrTrust.RR_QUANTUM_MS / (HrFixtures.sigmaOf(set) * kotlin.math.sqrt(2.0))
            val span = (trusted.last().timestampMs - trusted.first().timestampMs) + beats.first()
            observed += 1.0 - beats.sum() / span
            predicted += (trusted.size - 1) * tieRate * beats.average() / span
        }
        assertEquals(0.0880, observed.average(), 0.001, "mean observed shortfall")
        assertEquals(0.0811, predicted.average(), 0.001, "mean predicted by tie removal alone")

        val mo = observed.average()
        val mp = predicted.average()
        val cov = observed.indices.sumOf { (observed[it] - mo) * (predicted[it] - mp) }
        val denom =
            kotlin.math.sqrt(observed.sumOf { (it - mo) * (it - mo) } * predicted.sumOf { (it - mp) * (it - mp) })
        assertEquals(0.145, cov / denom, 0.01, "correlation of ordering, which is the part that fails")
    }

    /**
     * THE GUARD'S THREE NUMBERS, PINNED, AND THE DEFINITION OF "WINDOW" FIXED
     * WITH THEM.
     *
     * They were prose in a KDoc and they are definition-dependent: a window
     * whose span is measured between its own first and last DISTINCT beats
     * gives one pair of figures, and one measured over the trusted samples the
     * window was cut from gives another -- 0.3551/0.4547 against 0.3521/0.4028.
     * The guard is sound under both readings, which is the point of asserting
     * both rather than picking the flattering one.
     *
     * What matters is the ordering, not the digits: two distinct beats can
     * bring a worn window within a hair of the cut, three cannot.
     */
    @Test
    fun `two distinct beats come within a hair of the cut and three do not`() {
        fun worstWindow(size: Int): Double {
            var worst = Double.MAX_VALUE
            HrFixtures.allWorn().forEach { set ->
                val trusted = set.filter(HrTrust::isTrusted)
                val beats = RrIngest.newBeats(trusted)
                val stamps =
                    trusted.filterIndexed { i, s ->
                        i == 0 || s.rrIntervalsMs != trusted[i - 1].rrIntervalsMs
                    }
                for (start in 0..beats.size - size) {
                    val window = beats.subList(start, start + size)
                    val span =
                        (stamps[start + size - 1].timestampMs - stamps[start].timestampMs) + window.first()
                    if (span > 0) worst = minOf(worst, window.sum() / span)
                }
            }
            return worst
        }
        val two = worstWindow(2)
        val three = worstWindow(3)
        assertTrue(two < 0.36, "the worst two-beat window is no longer near the cut: $two")
        assertTrue(three > two, "three beats stopped being safer than two")
        assertTrue(
            three > HrTrust.MIN_ACCOUNTED_FRACTION * 1.1,
            "the worst three-beat window lost its margin over the cut: $three",
        )
        assertEquals(3, HrTrust.MIN_DISTINCT_BEATS, "the guard moved off the size these numbers justify")
    }

    /**
     * THE HOLD, AS A GATE RATHER THAN AS A PROMISE.
     *
     * What bounds this rule's false positives is the variability of a worn
     * stream, and [HrTrust.silencingSigmaMs] is the sigma at which one would be
     * silenced. The lowest any set of session 26 reaches is 6.58 ms.
     *
     * An earlier version of this test asserted only that no sample is below
     * 70 bpm -- a strict subset of what this class already asserts, so it added
     * nothing and would have stayed GREEN when the resting capture arrived. The
     * hold it was supposed to enforce would have been theatre.
     *
     * This one asserts sigma AND the fraction together, and it does so over
     * SESSION 26 only. Dropping a capture into this directory does not reach
     * it; the captures that discharged the hold are gated in
     * [FieldHrRestingBandTest].
     */
    @Test
    fun `every worn set clears both the variability floor and the cut`() {
        HrFixtures.allWorn().forEachIndexed { index, set ->
            val sigma = HrFixtures.sigmaOf(set)
            val fraction = HrTrust.accountedFraction(set)!!
            assertTrue(sigma > 5.0, "set ${index + 1} sigma $sigma is approaching the metronome end")
            assertTrue(fraction >= HrTrust.MIN_ACCOUNTED_FRACTION, "set ${index + 1} would be silenced")
            assertTrue(
                fraction > HrTrust.tieOnlyFraction(sigma) - HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY,
                "set ${index + 1} falls further short than its own variability explains",
            )
        }
        assertEquals(
            6.58,
            HrFixtures.allWorn().minOf(HrFixtures::sigmaOf),
            0.02,
            "the lowest variability any worn set reaches",
        )
    }

    /**
     * THE CONSTANT THAT CARRIES THE DROP TERM, PINNED FROM BOTH SIDES, OVER
     * EVERY WORN STREAM HELD HERE AND NOT ONLY SESSION 26.
     *
     * [HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY] is the only figure between
     * [HrTrust.tieOnlyFraction] -- a centre -- and [HrTrust.silencingSigmaMs],
     * which is the number the resting argument rests on. A one-sided assertion
     * would let it be made safe by being made meaningless: set it to 0.60 and
     * every stream clears it, the bound goes to infinity and nothing reds.
     *
     * So it is bounded above as well as below. It may not sit under the largest
     * residual the committed corpus shows, and it may not be padded more than a
     * hundredth above it.
     *
     * The corpus it is measured over widened from 17 streams to 57, and the
     * residual widened with it: 0.0649 across session 26, 0.1808 once the two
     * 0.1.40 captures are included. Session 26 alone was not a bound on how far
     * a real worn stream falls below the closed form; it was the worst of
     * seventeen.
     */
    @Test
    fun `the closed form's residual is bounded by the constant that carries it`() {
        val residuals =
            HrFixtures.everyWornStream().map {
                HrTrust.tieOnlyFraction(HrFixtures.sigmaOf(it)) - HrTrust.accountedFraction(it)!!
            }
        assertEquals(0.1808, residuals.max(), 0.0005, "the worst the closed form does against the corpus")
        assertEquals(
            0.0649,
            HrFixtures.allWorn().maxOf {
                HrTrust.tieOnlyFraction(HrFixtures.sigmaOf(it)) - HrTrust.accountedFraction(it)!!
            },
            0.0005,
            "and the worst it did against session 26 alone, which is what made the wider figure a surprise",
        )
        assertTrue(
            HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY >= residuals.max(),
            "the constant sits below a residual a real stream shows: ${residuals.max()}",
        )
        assertTrue(
            HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY <= residuals.max() + 0.01,
            "the constant is padded past the data it is meant to carry",
        )
    }

    /**
     * THE BOUND AGAINST THE CORPUS THAT HAS TO CLEAR IT, which is the
     * comparison the whole resting argument is. Neither side means anything
     * alone.
     *
     * The bound got WORSE when the resting captures arrived, from 1.19 ms to
     * 1.47 ms, because the drop term it carries is measured and the wider
     * corpus measured a larger one. The corpus minimum sigma did not move --
     * 6.58 ms, still session 26's fourteenth set -- so the margin fell from
     * 5.5x to 4.5x on the bound moving alone.
     */
    @Test
    fun `the bound the resting argument rests on, against the corpus that has to clear it`() {
        val bound = HrTrust.silencingSigmaMs()
        val lowest = HrFixtures.everyWornStream().minOf(HrFixtures::sigmaOf)
        assertEquals(6.58, lowest, 0.02, "the least variable stream of any capture held here")
        assertEquals(1.47, bound, 0.01, "the variability at which a genuine wearer would be silenced")
        assertEquals(4.5, lowest / bound, 0.1, "the margin between the bound and the least variable real stream")
        assertTrue(lowest > bound, "a real worn stream reached the silencing variability")
    }

    /**
     * THE HEADLINE SENTENCE OF THIS RULE WAS FALSE UNDER THE RULE'S OWN
     * DEFINITION OF VARIABILITY, and this is where that is pinned so it cannot
     * come back.
     *
     * [HrTrust.MIN_ACCOUNTED_FRACTION] opened by saying that a strap which has
     * lost contact holds one interval and re-sends it, so its series "has
     * almost none" of a heart's variability. Measured on the DE-DUPLICATED
     * series -- the only series anything here takes a sigma over -- the unworn
     * set is the MOST variable stream in this directory: 474.96 ms, against a
     * worn maximum of 391.01 and a worn minimum of 6.58.
     *
     * The de-duplication is the mechanism. The strap held three values for
     * 24 seconds and [RrIngest.newBeats] removes every repeat, leaving
     * 1902.3, 1607.4 and 1003.9 ms, whose successive differences are enormous.
     * "Almost no variability" is true of the RAW notification stream and false
     * of the series the rule reads.
     *
     * Nothing about the rule moves. It silences on the FRACTION, 0.171 against
     * a 0.35 cut, and the variability argument is about why a GENUINE stream
     * cannot reach that fraction -- not about how this one does. But the
     * discriminant is coverage, and a sentence saying it is variability had
     * survived three rounds unchallenged because no test could reach it.
     */
    @Test
    fun `the unworn set is the most variable stream held here, not the least`() {
        val unworn = HrFixtures.sigmaOf(HrFixtures.unworn(3))
        assertEquals(474.96, unworn, 0.05, "the unworn set's de-duplicated variability")
        assertTrue(
            unworn > HrFixtures.everyWornStream().maxOf(HrFixtures::sigmaOf),
            "the unworn set stopped being the most variable stream held here",
        )
        // The consequence, and it is stronger than "a weaker discriminant": any
        // rule of the form "silence when sigma < X" that reaches the unworn set
        // needs X above 474.96, and every worn stream is below 391.02, so it
        // silences the entire corpus. Variability cannot separate these two
        // populations in EITHER direction.
        assertTrue(
            HrFixtures.everyWornStream().all { HrFixtures.sigmaOf(it) < unworn },
            "a variability floor reaching the unworn set would spare some worn stream",
        )
        // And why the withdrawn sentence looked true: it is true of the series
        // the file does not measure. RMSSD over the raw notifications is 99.04
        // ms, low against a worn corpus of 6.58 to 391.02; the de-duplication
        // is what turns it into the largest figure in the directory.
        val raw = HrFixtures.unworn(3).filter(HrTrust::isTrusted).map { it.rrIntervalsMs.single() }
        val diffs = (1 until raw.size).map { raw[it] - raw[it - 1] }
        assertEquals(
            99.04,
            kotlin.math.sqrt(diffs.sumOf { it * it } / diffs.size),
            0.05,
            "the raw notification series, which is the one the withdrawn sentence described",
        )
        assertEquals(
            listOf(1902.3, 1607.4, 1003.9),
            RrIngest.newBeats(HrFixtures.unworn(3).filter(HrTrust::isTrusted)),
            "the three values a strap on a table held for 24 seconds",
        )
        assertTrue(
            HrTrust.accountedFraction(HrFixtures.unworn(3))!! < HrTrust.MIN_ACCOUNTED_FRACTION,
            "and it is still silenced, on coverage rather than on variability",
        )
    }

    /**
     * The fraction is NOT scale-free, which was asserted before it was
     * measured. Truncated to their first three distinct beats the worn sets
     * spread far wider than whole sets do. Real worn sets carry at least 62
     * distinct beats against the unworn set's 3, so the operating margin is the
     * whole-set one -- but the short-stream weakness is pinned rather than
     * described.
     */
    @Test
    fun `the fraction is noisier on short streams, and the control is never short`() {
        // Counted the way accountedFraction counts: trusted samples first, THEN
        // de-duplicated. Over ALL samples the unworn set yields 6 rather than
        // 3, and measuring it the other way would describe a different stream
        // from the one the rule reads.
        fun distinctBeats(set: List<HrSample>) = RrIngest.newBeats(set.filter(HrTrust::isTrusted)).size
        val worn = HrFixtures.allWorn().map(::distinctBeats)
        assertEquals(62, worn.min(), "the shortest worn set, in distinct beats")
        assertEquals(3, distinctBeats(HrFixtures.unworn(3)), "and the unworn set the rule reaches")
        assertTrue(worn.min() > 20 * distinctBeats(HrFixtures.unworn(3)), "the two populations stopped being far apart")
    }

    // ---- the unworn capture: what the rule actually reaches ----------------

    @Test
    fun `the rule reaches all of the first two unworn sets and half of the third`() {
        val trusted = HrFixtures.allUnworn().map { set -> set.count { HrTrust.isTrusted(it) } }
        assertEquals(listOf(0, 0, 47), trusted)
        assertEquals(listOf(72, 91, 91), HrFixtures.allUnworn().map { it.size })
    }

    /**
     * THE DIFFERENTIAL. The unworn set published avgBpm 46, maxBpm 46 and
     * minBpm 46 -- three mutually agreeing, entirely believable resting figures
     * from a strap on a table. It now publishes none of them.
     *
     * Three, not two, and the third is mine: #79 left avgBpm and maxBpm
     * reachable, and #90 part B then added minBpm over the same trusted list.
     * My change enlarged this issue by one field.
     *
     * The counts stay, because they are not published anywhere and they are how
     * a reader sees the decision was made over the same 47 samples as before --
     * the samples are still trusted individually; it is the STREAM that cannot
     * show it was measuring a heart.
     */
    @Test
    fun `the unworn set publishes none of its three figures`() {
        val summary = HrTrust.summarize(HrFixtures.unworn(3))
        assertNull(summary.avgBpm, "an unworn strap still published a mean")
        assertNull(summary.maxBpm, "an unworn strap still published a maximum")
        assertNull(summary.minBpm, "an unworn strap still published a minimum")
        assertNull(summary.endOfSetBpm)
        assertEquals(47, summary.trustedSamples, "the sample-level decision is unchanged")
        assertEquals(91, summary.totalSamples)
    }

    /**
     * And the other two unworn sets publish nothing already, so the whole of
     * this issue is one set. The firing evidence for any rule here is n=1.
     */
    @Test
    fun `the other two unworn sets already publish nothing`() {
        for (set in listOf(1, 2)) {
            val summary = HrTrust.summarize(HrFixtures.unworn(set))
            assertNull(summary.avgBpm, "unworn set $set")
            assertNull(summary.maxBpm, "unworn set $set")
            assertNull(summary.minBpm, "unworn set $set")
        }
    }

    /**
     * Set 3 is not closed by this change and the number that survives is
     * recorded here so the remainder is not mistaken for a fix. 47 of its 91
     * samples carry plausible-band R-R, so a mean, a maximum AND a minimum
     * still come out -- all three from the same 47 identical readings, so a
     * set-level minBpm inherits this exact, already-published gap rather than
     * creating a new one: this set already publishes avgBpm 46 and maxBpm 46
     * today. Issue #83.
     */
    @Test
    fun `the third unworn set no longer summarises to anything`() {
        val summary = HrTrust.summarize(HrFixtures.unworn(3))
        assertNull(summary.avgBpm)
        assertNull(summary.maxBpm)
        assertNull(summary.minBpm)
        assertTrue(summary.endOfSetBpm == null)
        assertEquals(47, summary.trustedSamples, "the sample-level decision is unchanged")
        assertEquals(91, summary.totalSamples)
    }
}
