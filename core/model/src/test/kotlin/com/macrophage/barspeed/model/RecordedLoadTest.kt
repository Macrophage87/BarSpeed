package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SetLoadPolicy.recordedLoad], the three load figures a finished set is
 * written with, lifted out of `RecordViewModel.endSet` (#65).
 *
 * Each case is chosen so that the mutations #65 measured surviving in `:app`
 * produce a different number here: the typed field read on a plan set, the
 * total written where the ADDED load belongs, the live slot load paired where
 * the frozen declaration belongs, and the body-weight term dropped.
 */
class RecordedLoadTest {
    private fun load(
        adHoc: Boolean = false,
        plannedAddedKg: Double? = null,
        typedAddedKg: Double? = null,
        statedAddedKg: Double? = null,
        bodyweight: Boolean = false,
        bodyWeightKg: Double? = null,
        declaredPlannedAddedKg: Double? = null,
    ) = SetLoadPolicy.recordedLoad(
        adHoc = adHoc,
        plannedAddedKg = plannedAddedKg,
        typedAddedKg = typedAddedKg,
        statedAddedKg = statedAddedKg,
        bodyweight = bodyweight,
        bodyWeightKg = bodyWeightKg,
        declaredPlannedAddedKg = declaredPlannedAddedKg,
    )

    @Test
    fun `a plan set records the plan's load and ignores a stale typed field`() {
        assertEquals(
            RecordedLoad(addedKg = 100.0, loadKg = 100.0, plannedLoadKg = 100.0),
            load(plannedAddedKg = 100.0, typedAddedKg = 60.0, declaredPlannedAddedKg = 100.0),
        )
    }

    @Test
    fun `a stated load beats the plan's and the frozen declaration is still what it is paired with`() {
        assertEquals(
            RecordedLoad(addedKg = 105.0, loadKg = 105.0, plannedLoadKg = 100.0),
            load(plannedAddedKg = 102.5, statedAddedKg = 105.0, declaredPlannedAddedKg = 100.0),
        )
    }

    @Test
    fun `an ad-hoc set records the typed field`() {
        assertEquals(
            RecordedLoad(addedKg = 60.0, loadKg = 60.0, plannedLoadKg = null),
            load(adHoc = true, plannedAddedKg = 100.0, typedAddedKg = 60.0, statedAddedKg = 70.0),
        )
    }

    @Test
    fun `body-weight work records the added load apart from the total, and pairs the plan on the total's scale`() {
        assertEquals(
            RecordedLoad(addedKg = -20.0, loadKg = 60.0, plannedLoadKg = 55.0),
            load(
                plannedAddedKg = -20.0,
                bodyweight = true,
                bodyWeightKg = 80.0,
                declaredPlannedAddedKg = -25.0,
            ),
        )
    }

    @Test
    fun `a slot that declares no load records zero added and pairs nothing`() {
        val recorded = load(bodyweight = true, bodyWeightKg = 80.0)
        assertEquals(0.0, recorded.addedKg)
        assertEquals(80.0, recorded.loadKg)
        assertNull(recorded.plannedLoadKg, "no declaration is not a declared zero")
    }

    @Test
    fun `the composition is the three functions it calls, on every case above`() {
        val cases = listOf(
            listOf(false, 100.0, 60.0, null, false, null, 100.0),
            listOf(false, 102.5, null, 105.0, false, null, 100.0),
            listOf(true, 100.0, 60.0, 70.0, false, null, null),
            listOf(false, -20.0, null, null, true, 80.0, -25.0),
        )
        for (c in cases) {
            val adHoc = c[0] as Boolean
            val planned = c[1] as Double?
            val typed = c[2] as Double?
            val stated = c[3] as Double?
            val bw = c[4] as Boolean
            val bodyWeightKg = c[5] as Double?
            val declared = c[6] as Double?
            val added = SetLoadPolicy.resolve(adHoc, planned, typed, stated)
            assertEquals(
                RecordedLoad(
                    added,
                    SetLoadPolicy.totalKg(bw, bodyWeightKg, added),
                    SetLoadPolicy.recordedPlannedLoadKg(bw, bodyWeightKg, declared),
                ),
                load(adHoc, planned, typed, stated, bw, bodyWeightKg, declared),
                "$c",
            )
        }
    }
}
