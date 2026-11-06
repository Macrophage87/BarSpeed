package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rest screen's load correction steps (UX review 2026-11-06, rest F5).
 *
 * The fine step is a pair of the smallest plate [PlateMath] loads, so the two
 * cannot drift; the larger step is one 45 lb or 20 kg plate, so a wrong big
 * plate is one tap rather than nine.
 */
class SetLoadBigStepTest {
    @Test
    fun `the fine step is a pair of the smallest plate plate math loads`() {
        for (unit in WeightUnit.entries) {
            assertEquals(
                unit.format(unit.toKg(2 * PlateMath.smallestPlate(unit))),
                unit.format(SetLoadPolicy.correctionStepKg(unit)),
                "$unit",
            )
        }
        assertEquals("5 lb", WeightUnit.LB.format(SetLoadPolicy.correctionStepKg(WeightUnit.LB)))
        assertEquals("2.5 kg", WeightUnit.KG.format(SetLoadPolicy.correctionStepKg(WeightUnit.KG)))
    }

    @Test
    fun `the big step is one bar plate in the unit the lifter reads`() {
        assertEquals("45 lb", WeightUnit.LB.format(SetLoadPolicy.correctionBigStepKg(WeightUnit.LB)))
        assertEquals("20 kg", WeightUnit.KG.format(SetLoadPolicy.correctionBigStepKg(WeightUnit.KG)))
    }

    @Test
    fun `a wrong 45 is one tap`() {
        val step = SetLoadPolicy.correctionBigStepKg(WeightUnit.LB)
        val corrected = SetLoadPolicy.correctedAddedKg(WeightUnit.LB.toKg(135.0), step, bodyweight = false)
        assertEquals("180 lb", WeightUnit.LB.format(corrected))
    }

    @Test
    fun `the big step buttons say which way and how much`() {
        assertEquals("+45 lb", SetLoadPolicy.correctionBigStepLabel(WeightUnit.LB, up = true))
        assertEquals("−45 lb", SetLoadPolicy.correctionBigStepLabel(WeightUnit.LB, up = false))
        assertEquals("+20 kg", SetLoadPolicy.correctionBigStepLabel(WeightUnit.KG, up = true))
    }
}
