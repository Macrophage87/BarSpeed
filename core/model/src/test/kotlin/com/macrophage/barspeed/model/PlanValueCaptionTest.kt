package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The notation [PlanValueCaption] renders the plan's declared load in.
 *
 * What the three boxes SAY is [PlanValueCaptionContractTest]'s, and the three
 * pins that used to sit here saying they say nothing have been deleted rather
 * than reworded: they characterized the pre-#175 behaviour, that file demands
 * the opposite, and a reworded false claim is how this repository has produced
 * fresh ones.
 */
class PlanValueCaptionTest {
    @Test
    fun `a loaded plan set names its load in the display unit`() {
        assertEquals(
            "90 kg",
            PlanValueCaption.plannedLoadText(bodyweight = false, unit = WeightUnit.KG, plannedAddedKg = 90.0),
        )
        assertEquals(
            "198.4 lb",
            PlanValueCaption.plannedLoadText(bodyweight = false, unit = WeightUnit.LB, plannedAddedKg = 90.0),
        )
    }

    /**
     * #160's notation, because that is what the box beside the caption takes:
     * the box's own label reads "Added (lb)" on this population.
     */
    @Test
    fun `a body-weight plan set names its load in the BW notation`() {
        assertEquals(
            "BW + 10 lb",
            PlanValueCaption.plannedLoadText(
                bodyweight = true,
                unit = WeightUnit.LB,
                plannedAddedKg = 10 / WeightUnit.LB_PER_KG,
            ),
        )
        assertEquals(
            "BW − 20 kg",
            PlanValueCaption.plannedLoadText(bodyweight = true, unit = WeightUnit.KG, plannedAddedKg = -20.0),
        )
    }

    /** BW is the zero of the notation, so a pull-up at body weight has said something. */
    @Test
    fun `a body-weight plan set that declared no load still names one`() {
        assertEquals(
            "BW",
            PlanValueCaption.plannedLoadText(bodyweight = true, unit = WeightUnit.KG, plannedAddedKg = null),
        )
    }

    /** Loaded work with the weight left to the lifter has no prescription to name. */
    @Test
    fun `a loaded plan set that declared no load names nothing`() {
        assertNull(PlanValueCaption.plannedLoadText(bodyweight = false, unit = WeightUnit.KG, plannedAddedKg = null))
    }
}
