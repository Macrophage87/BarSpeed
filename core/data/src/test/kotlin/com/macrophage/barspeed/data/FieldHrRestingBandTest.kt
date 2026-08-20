package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.hrm.RrIngest
import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two 0.1.40 captures this branch was held for, run against the rule.
 *
 * WHAT THE HOLD WAS. Issue #83's rule silences a stream whose de-duplicated
 * beats account for less than 35% of its own elapsed time. Its safety rests on
 * one comparison -- [HrTrust.silencingSigmaMs] against the lowest variability
 * any real worn stream reaches -- and session 26 could not test the slow end of
 * it, because not one of its 1,930 samples is below 70 bpm. The branch would
 * not land until a capture with a slower heart existed.
 *
 * WHAT ARRIVED, STATED AT ITS REACH AND NOT AT ITS LABEL. Forty streams over
 * two sessions: twenty in-set and twenty rest-before-set, 8,044 samples. The
 * rest streams are the reason both sessions are here, because that is where the
 * heart rate is low. It is 67 bpm at its lowest, and 16 of the 8,044 samples
 * are below 70. The strap on the table published 46. So these captures do NOT
 * measure the band that defect sat in; they move the measured floor from
 * 78 bpm to 67 bpm, which is 11 beats and not 32. Everything below 67 remains
 * an extrapolation and is named as one in [HrTrust].
 *
 * WHAT THEY DO SETTLE. The rule withholds nothing, anywhere, in any of the
 * forty. The margin at its narrowest is 2.3x on the fraction. And they falsify
 * an assumption the branch was carrying: inter-set rest is NOT several times
 * more variable than the set it precedes.
 *
 * Sibling of [FieldHrTrustDischargeTest], which owns sessions 26 and 28 and
 * whose blind-spot test explains why a fixture dropped into the resources
 * directory without a matching count in [HrFixtures] is a file nothing opens.
 */
class FieldHrRestingBandTest {
    private fun allNew(): List<List<HrSample>> = HrFixtures.allInSet() + HrFixtures.allRestBefore()

    private fun fractionOf(set: List<HrSample>) = HrTrust.accountedFraction(set)!!

    // ---- what the rule costs the captures it was held for ------------------

    /**
     * THE ANSWER THE HOLD WAS WAITING FOR, and it is a count of zero.
     *
     * Every one of the forty streams produces a budget -- none falls under
     * [HrTrust.MIN_DISTINCT_BEATS] -- and every one clears the cut. The
     * narrowest is the rest before session 30's first set, which is also the
     * shortest stream in the corpus at 17 distinct beats.
     */
    @Test
    fun `not one of the forty streams is withheld`() {
        val fractions = allNew().map(::fractionOf)
        assertEquals(40, fractions.size, "the capture changed size")
        assertEquals(0.8175, fractions.min(), 0.0005, "the narrowest margin anywhere in the two captures")
        assertEquals(0.9983, fractions.max(), 0.0005, "the widest")
        assertEquals(
            emptyList(),
            allNew().filterNot(HrTrust::tracksAHeart),
            "a stream from a worn strap would have been silenced",
        )
        assertEquals(
            2.3,
            fractions.min() / HrTrust.MIN_ACCOUNTED_FRACTION,
            0.05,
            "how far the worst real stream sits above the cut",
        )
    }

    /** And the consequence at the level the lifter sees: every set still publishes its figures. */
    @Test
    fun `every set of both captures still publishes a heart rate`() {
        HrFixtures.allInSet().forEachIndexed { index, set ->
            val summary = HrTrust.summarize(set)
            assertNotNull(summary.avgBpm, "in-set stream ${index + 1} lost its mean")
            assertNotNull(summary.maxBpm, "in-set stream ${index + 1} lost its maximum")
            assertNotNull(summary.minBpm, "in-set stream ${index + 1} lost its minimum")
            assertNotNull(summary.endOfSetBpm, "in-set stream ${index + 1} lost its end-of-set reading")
        }
    }

    // ---- the variability floor, which is what the bound is compared with ---

    /**
     * THE COMPARISON THE WHOLE ARGUMENT IS, on the side that could have broken
     * it. If any of these forty streams were less variable than session 26's
     * least variable set, the margin quoted in [HrTrust] would be wrong and the
     * branch would need changing rather than landing.
     *
     * None is. The lowest sigma in the two captures is 7.91 ms, on an in-set
     * stream, and the corpus minimum stays where it was.
     */
    @Test
    fun `the resting captures do not lower the variability floor`() {
        assertEquals(7.91, allNew().minOf(HrFixtures::sigmaOf), 0.02, "the least variable of the forty")
        assertEquals(
            8.23,
            HrFixtures.allRestBefore().minOf(HrFixtures::sigmaOf),
            0.02,
            "the least variable REST stream, which is the regime the hold was about",
        )
        assertEquals(
            6.58,
            HrFixtures.allWorn().minOf(HrFixtures::sigmaOf),
            0.02,
            "and session 26 still owns the corpus minimum",
        )
        assertTrue(
            allNew().minOf(HrFixtures::sigmaOf) > HrFixtures.allWorn().minOf(HrFixtures::sigmaOf),
            "a 0.1.40 stream went below the corpus minimum, which moves the margin in HrTrust",
        )
    }

    /**
     * THE ASSUMPTION THIS FALSIFIES, and it was load-bearing prose in two
     * files: resting variability is several times working variability.
     *
     * Paired against the set each rest period precedes, the median ratio is
     * 1.26 and four of the twenty rest streams are LESS variable than their own
     * set. The mean is 1.9, pulled up by four streams above 2.0, and the worst
     * of those contains a 300 ms interval followed immediately by a 1,600 ms
     * one. That pattern is what a premature beat with a compensatory pause
     * looks like, and it is also what a detector artifact looks like; nothing
     * here can tell them apart, so the mean is reported and not interpreted.
     * Several times is not what this measures.
     *
     * It does not break the rule -- the floor is what the bound is compared
     * with, and the floor does not fall -- but the argument may no longer be
     * made this way, and [HrTrust] no longer makes it.
     */
    @Test
    fun `inter-set rest is not several times more variable than the set it precedes`() {
        val ratios =
            (1..HrFixtures.S30_SETS).map { HrFixtures.restBefore(30, it) to HrFixtures.inSet(30, it) }
                .plus((1..HrFixtures.S31_SETS).map { HrFixtures.restBefore(31, it) to HrFixtures.inSet(31, it) })
                .map { (rest, set) -> HrFixtures.sigmaOf(rest) / HrFixtures.sigmaOf(set) }
                .sorted()
        val median = (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2.0
        assertEquals(20, ratios.size)
        assertEquals(1.26, median, 0.02, "the median rest-to-set variability ratio")
        assertEquals(4, ratios.count { it < 1.0 }, "rest streams LESS variable than their own set")
        assertTrue(median < 2.0, "rest turned out to be several times more variable after all")
    }

    // ---- how low the heart rate actually goes, stated as a limit -----------

    /**
     * THE LIMIT OF WHAT THESE CAPTURES REACH, asserted rather than described,
     * because the thing this branch keeps getting wrong is a claim wider than
     * its evidence.
     *
     * The unworn strap published 46 bpm. The slowest sample in either capture
     * is 67. A rule wrong only below 67 bpm would pass everything in this file.
     */
    @Test
    fun `the captures reach 67 bpm and no lower`() {
        val bpm = allNew().flatten().map { it.bpm }
        assertEquals(8044, bpm.size, "the two captures changed size")
        assertEquals(67, bpm.min(), "the slowest sample in either capture")
        assertEquals(133, bpm.max())
        assertEquals(16, bpm.count { it < 70 }, "how much of this corpus is below the worn control's floor")
        assertEquals(
            1,
            allNew().count { set -> set.any { it.bpm < 70 } },
            "and it is all one stream, so n=1 on the sub-70 evidence",
        )
    }

    /**
     * THE LOWEST HEART RATE IN THE CORPUS, TAKEN ON ITS OWN.
     *
     * Whole-stream figures average over the whole stream, so a slow passage
     * inside a faster one cannot be read off them. This cuts the run below
     * 75 bpm in the rest before session 30's third set -- 96 samples over
     * 47.5 s at 67 to 74 bpm -- and asks the rule about that alone. That
     * stream is the only one in the corpus carrying any sample below 70, which
     * is what makes it the slowest passage; it is NOT the longest, and an
     * earlier version of this comment said it was. s30-rest05 holds a longer
     * run, 102 samples over 50.5 s, and never goes under 70.
     *
     * It accounts for 97% of its own time. This is the most direct measurement
     * of the slow end that exists anywhere in this repository, and it is still
     * 21 bpm above the figure the unworn strap published.
     */
    @Test
    fun `the slowest passage in the corpus accounts for almost all of its own time`() {
        val window = longestRunBelow(HrFixtures.restBefore(30, 3), 75)
        assertEquals(96, window.size, "the sub-75 bpm run in field-hrm-worn-s30-rest03")
        assertEquals(47.5, (window.last().timestampMs - window.first().timestampMs) / 1000.0, 0.1)
        assertEquals(67, window.minOf { it.bpm })
        assertEquals(74, window.maxOf { it.bpm })
        assertEquals(0.973, fractionOf(window), 0.002, "what the slowest passage accounts for")
        assertEquals(26.75, HrFixtures.sigmaOf(window), 0.05, "and how variable it is")
        assertTrue(HrTrust.tracksAHeart(window), "the slowest passage in the corpus would have been silenced")
    }

    private fun longestRunBelow(stream: List<HrSample>, bpm: Int): List<HrSample> {
        var bestFrom = 0
        var bestTo = -1
        var start = -1
        stream.indices.forEach { i ->
            if (stream[i].bpm < bpm) {
                if (start < 0) start = i
                if (i - start > bestTo - bestFrom) {
                    bestFrom = start
                    bestTo = i
                }
            } else {
                start = -1
            }
        }
        return stream.subList(bestFrom, bestTo + 1)
    }

    // ---- what a prior reading of session 30 got wrong ----------------------

    /**
     * NINE SAMPLES OF THE 8,044 ARE NOT TRUSTED, and a prior analysis of
     * session 30 reported zero. That reading is wrong and the correction lives
     * here, next to the number.
     *
     * Every one of the nine reports a plausible bpm beside an R-R interval of
     * exactly 0.0 ms, which is the sample-level impossibility
     * [HrTrust.isTrusted] exists for. They are spread over three rest streams
     * and not one is in an in-set stream.
     *
     * It changes nothing about the verdict -- they are dropped before the budget
     * is taken, and all forty streams clear the cut anyway -- but zero untrusted
     * samples was offered as evidence that the captures were clean, and it was
     * not true.
     */
    @Test
    fun `nine samples are untrusted, every one of them reporting a zero interval`() {
        val untrusted = allNew().flatten().filterNot(HrTrust::isTrusted)
        assertEquals(9, untrusted.size, "the two captures' untrusted sample count")
        assertTrue(untrusted.all { it.rrIntervalsMs == listOf(0.0) }, "an untrusted sample failed on something else")
        assertTrue(untrusted.all { it.bpm > 0 }, "one of them would have been caught by bpm alone")
        assertEquals(
            0,
            HrFixtures.allInSet().sumOf { set -> set.count { !HrTrust.isTrusted(it) } },
            "an in-set stream carried an untrusted sample",
        )
    }

    /**
     * WHY CARRYING ONE WORST-CASE RESIDUAL IS CONSERVATIVE AT THE END THAT
     * MATTERS, which is prose in [HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY] and a
     * measurement here.
     *
     * The residual is largest where sigma is largest, so the corpus-wide
     * maximum is drawn from streams nothing like the ones near the bound. The
     * bound is evaluated around sigma 1 ms; among the streams under 15 ms the
     * closed form does far better than 0.181.
     *
     * Without this the constant could be justified by a figure no test holds,
     * which is the shape of every claim this branch has had to withdraw.
     */
    @Test
    fun `the closed form's residual grows with variability, not against it`() {
        val pairs =
            HrFixtures.everyWornStream().map { set ->
                val sigma = HrFixtures.sigmaOf(set)
                sigma to HrTrust.tieOnlyFraction(sigma) - HrTrust.accountedFraction(set)!!
            }
        val mx = pairs.sumOf { it.first } / pairs.size
        val my = pairs.sumOf { it.second } / pairs.size
        val cov = pairs.sumOf { (it.first - mx) * (it.second - my) }
        val den =
            kotlin.math.sqrt(
                pairs.sumOf { (it.first - mx) * (it.first - mx) } *
                    pairs.sumOf { (it.second - my) * (it.second - my) },
            )
        assertEquals(0.57, cov / den, 0.01, "correlation of variability with the residual")
        val near = pairs.filter { it.first < 15.0 }
        assertEquals(36, near.size, "streams in the decade the bound is evaluated in")
        assertEquals(0.0778, near.maxOf { it.second }, 0.0005, "the worst residual near the bound")
        assertTrue(
            near.maxOf { it.second } < HrTrust.MAX_SHORTFALL_BELOW_TIE_ONLY,
            "the constant stopped being conservative where it is applied",
        )
    }

    /**
     * The three-beat guard never binds in either capture, so the whole of the
     * evidence above is about the fraction and not about the cannot-say branch.
     */
    @Test
    fun `the shortest of the forty streams still carries seventeen distinct beats`() {
        val beats = allNew().map { RrIngest.newBeats(it.filter(HrTrust::isTrusted)).size }
        assertEquals(17, beats.min(), "the shortest stream, in distinct beats")
        assertTrue(beats.min() > HrTrust.MIN_DISTINCT_BEATS, "the guard started binding on real worn data")
    }
}
