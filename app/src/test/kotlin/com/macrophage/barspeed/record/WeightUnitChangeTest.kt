package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a change of display unit is allowed to touch in [RecordState].
 *
 * [unitChangedState] is the seam the settings collector now runs -- the only
 * place a new unit reaches the record screen's state, and therefore the only
 * place the load field can be converted; see `WeightUnitChange.kt`'s own KDoc
 * for the other four view models that also observe
 * `container.settings.weightUnit` without holding a typed load. These pins are
 * the parts of its contract that were already true of the collector body it
 * replaced; the load field's conversion, which was NOT, is pinned in
 * `WeightUnitChangeDifferentialTest`.
 */
class WeightUnitChangeTest {
    private fun setUp(loadInput: String, unit: WeightUnit, statedLoadKg: Double? = null) = RecordState(
        stage = Stage.READY,
        adHoc = true,
        loadInput = loadInput,
        statedLoadKg = statedLoadKg,
        weightUnit = unit,
    )

    @Test
    fun `the new unit is what the state reports`() {
        assertEquals(WeightUnit.LB, unitChangedState(setUp("100", WeightUnit.KG), WeightUnit.LB).weightUnit)
        assertEquals(WeightUnit.KG, unitChangedState(setUp("100", WeightUnit.LB), WeightUnit.KG).weightUnit)
    }

    /**
     * `statedLoadKg` IS ALREADY IN KILOGRAMS and a chip tap is not a keystroke.
     * Its KDoc says it is written only by `updateLoadInput` or by
     * `SetLoadPolicy.standingStatedAddedKg`; re-seeding it here would
     * manufacture a statement out of a display action, and a plan set would
     * then be recorded against a string left behind by an earlier exercise
     * instead of the plan's own load.
     */
    @Test
    fun `a stated load is neither converted nor invented`() {
        assertEquals(102.5, unitChangedState(setUp("100", WeightUnit.KG, 102.5), WeightUnit.LB).statedLoadKg)
        assertEquals(null, unitChangedState(setUp("100", WeightUnit.KG), WeightUnit.LB).statedLoadKg)
    }

    @Test
    fun `nothing but the unit and the load text moves`() {
        val before = setUp("100", WeightUnit.KG, 102.5)
        val after = unitChangedState(before, WeightUnit.LB)
        assertEquals(before, after.copy(weightUnit = before.weightUnit, loadInput = before.loadInput))
    }

    @Test
    fun `an empty load field stays empty`() {
        assertEquals("", unitChangedState(setUp("", WeightUnit.KG), WeightUnit.LB).loadInput)
    }
}
