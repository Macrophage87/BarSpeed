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
 * dip to 496.1 ms is followed by 792.0 ms twice in a row -- a cadence-resent
 * duplicate, issue #81 -- then a recovery through 635.7, 629.9, 627.9 ms.
 * That set's expected RMSSD moves once the re-anchor fix lands, so pinning
 * it in this file would mean editing this file at the fix commit, which the
 * fix commit must not do. Set 1's own expectation is added separately,
 * alongside the fix, as a named differential rather than a pin that would
 * silently change underneath it.
 */
class HrvWornControlDischargeTest {
    /**
     * Through the ingest seam, NOT straight off the fixture.
     *
     * This read `flatMap { it.rrIntervalsMs }` and was a check that could not
     * fail: it pinned sixteen published figures to 1e-9 while bypassing the one
     * decision that determines all of them, so the whole file would have stayed
     * green while every number it names moved. Routing through
     * [RrIngest.newBeats] costs nothing today -- the batch form IS
     * `flatMap { it.rrIntervalsMs }` at this commit, so not one expected value
     * below changes -- and it means the commit that fixes issue #81 reds exactly
     * the figures it moves, in a diff that shows only real movement.
     */
    private fun rrOf(set: Int): List<Double> = RrIngest.newBeats(HrFixtures.worn(set))

    @Test
    fun `sixteen of the seventeen worn sets publish this rmssd, set one excluded on purpose`() {
        val unaffected =
            mapOf(
                2 to 7.447290974384101,
                3 to 9.47994374104263,
                4 to 9.351126746471332,
                5 to 6.949913575095632,
                6 to 9.02494378872303,
                7 to 8.17817812245475,
                8 to 6.05203994534074,
                9 to 6.480839028228052,
                10 to 8.38817710539753,
                11 to 9.741537638153204,
                12 to 8.944526384264627,
                13 to 9.767064838299959,
                14 to 6.22211674931492,
                15 to 8.312387904984147,
                16 to 6.566483999920514,
                17 to 6.910214808693723,
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
    fun `the worn control's total r-r interval count is what it has always been`() {
        val total = (1..HrFixtures.WORN_SETS).sumOf { rrOf(it).size }
        assertEquals(1930, total, "the worn control's r-r interval count changed")
    }
}
