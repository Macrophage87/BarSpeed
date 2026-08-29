package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PlanValueCaption] as it stands on arrival: the notation it renders the
 * plan's load in, and the three captions it does not yet draw.
 *
 * Every pin here is green at the commit that adds it. The differentials that
 * demand the captions live in [PlanValueCaptionContractTest] and are red until
 * the fix.
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

    /**
     * Shipped behaviour, written down: the three boxes the lifter adjusts most
     * say nothing about the plan. #175. Replaced by
     * [PlanValueCaptionContractTest] once the captions land.
     */
    @Test
    fun `today the load box says nothing about the plan`() {
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 90.0,
                shownAddedKg = 100.0,
                standsForLaterSets = true,
            ),
        )
    }

    @Test
    fun `today the reps box says nothing about the plan`() {
        assertNull(PlanValueCaption.reps(adHoc = false, plannedReps = 8, shownReps = 10, standsForLaterSets = true))
    }

    @Test
    fun `today the hold box says nothing about the plan`() {
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                plannedDurationS = 45,
                shownDurationS = 30,
                standsForLaterSets = true,
            ),
        )
    }
}
