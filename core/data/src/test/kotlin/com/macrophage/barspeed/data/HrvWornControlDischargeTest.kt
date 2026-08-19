package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.hrm.RrIngest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Hrv.rmssdMs] run against the same worn positive control
 * [FieldHrTrustDischargeTest] uses for [HrTrust]: a rule that looks at more
 * than one R-R interval is exactly the class that failed twice before on
 * this strap's cadence behaviour (issue #83), so it is checked against all
 * seventeen real worn sets and the result is pinned as a number rather than
 * asserted once and left to memory.
 *
 * Sixteen of the seventeen sets are pinned here, deliberately excluding set
 * 1. Set 1 carries a real, confirmed re-anchor event (issue #27): a transient
 * dip to 496.1 ms is followed by 792.0 ms reported twice in a row -- then a recovery through 635.7, 629.9, 627.9 ms.
 * That set's expected RMSSD moves once the re-anchor fix lands, so pinning
 * it in this file would mean editing this file at the fix commit, which the
 * fix commit must not do. Set 1's own expectation is added separately,
 * alongside the fix, as a named differential rather than a pin that would
 * silently change underneath it.
 */
class HrvWornControlDischargeTest {
    /*
     * ON WORDING, because an earlier version of this file got it wrong in four
     * places including two test names: the app publishes NO per-set HRV. A
     * set's exported `hr` block carries endOfSetBpm, avgBpm and maxBpm, and the
     * only HRV reaching an export is the single session figure. The sixteen
     * numbers below are what Hrv.rmssdMs computes from one set's stream; they
     * are pinned because they are sensitive to the ingest rule, not because a
     * reader ever sees them. Calling them published inflated the stakes of
     * every change to them.
     */

    /**
     * Through the ingest seam, NOT straight off the fixture.
     *
     * This read `flatMap { it.rrIntervalsMs }` and was a check that could not
     * fail: it pinned sixteen figures to 1e-9 while bypassing the one
     * decision that determines all of them, so the whole file would have stayed
     * green while every number it names moved. Routing through
     * [RrIngest.newBeats] means the commit that changes the ingest rule reds
     * exactly the figures it moves, in a diff showing only real movement.
     */
    private fun rrOf(set: Int): List<Double> = RrIngest.newBeats(HrFixtures.worn(set))

    @Test
    fun `sixteen of the seventeen worn sets reduce to this rmssd, set one excluded on purpose`() {
        val unaffected =
            mapOf(
                2 to 8.570289660499519,
                3 to 10.929162011722632,
                4 to 10.549493740545955,
                5 to 7.450533387697412,
                6 to 10.139696377256616,
                7 to 9.041316100291867,
                8 to 6.588626564011647,
                9 to 7.228104025598835,
                10 to 9.273478711217688,
                11 to 10.600881294049392,
                12 to 9.963177575824266,
                13 to 10.67836597986789,
                14 to 6.577948902561185,
                15 to 8.870875410891276,
                16 to 7.004721314203385,
                17 to 7.407553044377281,
            )
        assertEquals(
            HrFixtures.WORN_SETS - 1,
            unaffected.size,
            "sixteen of seventeen worn sets -- set one is not here on purpose",
        )
        unaffected.forEach { (set, expected) ->
            assertEquals(expected, Hrv.rmssdMs(rrOf(set))!!, 1e-9, "worn set $set's rmssd changed")
        }
    }

    @Test
    fun `the worn control yields 1603 beats from 1930 notifications`() {
        val total = (1..HrFixtures.WORN_SETS).sumOf { rrOf(it).size }
        assertEquals(1603, total, "the worn control's beat count changed")
    }
}
