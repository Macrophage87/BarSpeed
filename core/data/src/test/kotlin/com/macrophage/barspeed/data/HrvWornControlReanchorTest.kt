package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.hrm.RrIngest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Worn set 1 of session 26, real data rather than synthetic (issue #27).
 *
 * Its stream carries a real re-anchor event: a transient dip to 496.1 ms,
 * then 792.0 ms reported twice in a row (issue #81) -- then a recovery through 635.7, 629.9, 627.9 ms.
 *
 * AS OF #81 THE SECOND 792.0 NEVER REACHES Hrv. It is removed at ingest, so
 * the distinctness guard inside segments no longer has anything to refuse
 * here and the 496.1 candidate is now a lone artifact on its own terms: the
 * beat that follows it is 792.0 once, and the new segment still starts at
 * 635.7, the beat that actually confirmed the shift. The outcome is
 * unchanged and the ROUTE to it is not, which is why this reads through the
 * ingest seam and why the number below moved.
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
    private fun rrOfSetOne(): List<Double> = RrIngest.newBeats(HrFixtures.worn(1))

    /**
     * [Hrv.segments] is internal to :core:hrm and not reachable from here,
     * so the segment-shape assertion (anchor on the confirming beat, not
     * the rejected one) is made against synthetic data in HrvTest instead.
     * This is the real-data half: only rmssdMs. Not a published number -- the
     * app exports no per-set HRV -- but it is what the ingest rule moves.
     */
    @Test
    fun `the worn control's one confirmed re-anchor moves set one's rmssd here`() {
        val rr = rrOfSetOne()
        assertEquals(23.564708075142093, Hrv.rmssdMs(rr)!!, 1e-9, "set 1's post-fix rmssd moved")
    }
}
