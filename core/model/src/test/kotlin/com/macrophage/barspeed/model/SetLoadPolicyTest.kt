package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterization of the load rules exactly as the record flow applies them
 * today, defect included. Two of these say something the app is wrong to do
 * and are named `(pre-fix)`; they are inverted, by name, in the commit that
 * introduces the failing differentials.
 */
class SetLoadPolicyTest {
    @Test
    fun `resolve uses the plan's declared load`() {
        assertEquals(100.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = 100.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve honours a plan set that declares zero`() {
        // "load_kg": 0 is explicit bodyweight, distinct from declaring nothing.
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = 0.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve keeps a negative declared load for assisted work`() {
        assertEquals(-20.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = -20.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve uses the typed load for an ad-hoc set`() {
        assertEquals(60.0, SetLoadPolicy.resolve(adHoc = true, plannedAddedKg = null, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve treats an unparseable typed load as zero`() {
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = true, plannedAddedKg = null, typedAddedKg = null))
    }

    @Test
    fun `resolve reads the typed field for a loadless plan set (pre-fix)`() {
        assertEquals(60.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = null, typedAddedKg = 60.0))
    }

    @Test
    fun `seedAddedKg prefers the next slot's declared load`() {
        assertEquals(
            100.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = 100.0, lastAddedKg = 60.0),
        )
    }

    @Test
    fun `seedAddedKg carries the last load forward when the plan has run out`() {
        assertEquals(
            60.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = false, nextDeclaredAddedKg = null, lastAddedKg = 60.0),
        )
    }

    @Test
    fun `seedAddedKg has nothing to seed from at the end of an ad-hoc queue`() {
        assertNull(SetLoadPolicy.seedAddedKg(hasPlannedNext = false, nextDeclaredAddedKg = null, lastAddedKg = null))
    }

    @Test
    fun `seedAddedKg carries the last load forward for a loadless next slot (pre-fix)`() {
        assertEquals(
            100.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = 100.0),
        )
    }
}
