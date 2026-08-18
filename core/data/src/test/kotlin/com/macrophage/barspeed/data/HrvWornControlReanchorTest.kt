package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.Hrv
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Worn set 1 of session 26, real data rather than synthetic (issue #27).
 *
 * Its stream carries a real re-anchor event: a transient dip to 496.1 ms,
 * then 792.0 ms reported twice in a row -- a cadence-resent duplicate,
 * issue #81 -- then a recovery through 635.7, 629.9, 627.9 ms. Neither
 * 792.0 reading is used as data by the fix: the first is dropped as a lone
 * artifact (its only "confirmation" is its own duplicate, which the
 * distinctness guard refuses), and the second is dropped too, kept only as
 * evidence that a shift happened -- the new segment starts at 635.7, the
 * beat that actually confirmed it.
 *
 * 496.1 + 792.0 = 1288.1, close to double the local baseline of about 645
 * ms. That is the signature of a single misdetection splitting one true
 * beat into two implausible halves, not a heart-rate change, and it is why
 * neither the rejected candidate nor the beat it broke against is trusted
 * as data once a shift is confirmed elsewhere.
 *
 * [HrvWornControlDischargeTest] pins the sixteen sets this fix does not
 * touch; this is the one set it does, named separately so its number can
 * move without editing a pin.
 */
class HrvWornControlReanchorTest {
    private fun rrOfSetOne(): List<Double> = HrFixtures.worn(1).flatMap { it.rrIntervalsMs }

    /**
     * [Hrv.segments] is internal to :core:hrm and not reachable from here,
     * so the segment-shape assertion (anchor on the confirming beat, not
     * the rejected one) is made against synthetic data in HrvTest instead.
     * This is the real-data half: only the published number, rmssdMs.
     */
    @Test
    fun `the worn control's one confirmed re-anchor moves set one's published rmssd here`() {
        val rr = rrOfSetOne()
        assertEquals(20.720814775207405, Hrv.rmssdMs(rr)!!, 1e-9, "set 1's post-fix rmssd moved")
    }
}
