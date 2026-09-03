package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #77 at the state boundary: the load an AD-HOC set is recorded with must not
 * change because the lifter looked at the other unit.
 *
 * `SetLoadPolicy.resolve` reads the typed field and nothing else for an ad-hoc
 * set, which is why ad-hoc is the case that bites -- the chip and the load
 * field sit in the same `AdHocForm`, so the sequence is a single tap. Plan sets
 * were never affected: `resolve` ignores the typed field for them.
 *
 * Both assertions fail at the commit that introduces them.
 */
class WeightUnitChangeDifferentialTest {
    private fun adHoc(loadInput: String, unit: WeightUnit) = RecordState(
        stage = Stage.READY,
        adHoc = true,
        loadInput = loadInput,
        weightUnit = unit,
    )

    @Test
    fun `the field is re-rendered in the unit the lifter switched to`() {
        assertEquals("220.5", unitChangedState(adHoc("100", WeightUnit.KG), WeightUnit.LB).loadInput)
        assertEquals("102", unitChangedState(adHoc("225", WeightUnit.LB), WeightUnit.KG).loadInput)
    }

    @Test
    fun `the load an ad-hoc set records survives the tap`() {
        val before = adHoc("100", WeightUnit.KG)
        val after = unitChangedState(before, WeightUnit.LB)
        val recorded = SetLoadPolicy.resolve(
            adHoc = true,
            plannedAddedKg = null,
            typedAddedKg = after.weightUnit.parseToKg(after.loadInput),
            statedAddedKg = after.statedLoadKg,
        )
        assertTrue(abs(recorded - 100.0) < 0.02, "recorded $recorded kg, expected ~100")
    }
}
