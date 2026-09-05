package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bar a plan can declare for itself (`bar_lb` / `bar_kg`, #253), and the
 * default that stands when it does not.
 *
 * THE ROUNDING CASE IS THE ONE WORTH HAVING, and the first version of it was
 * a test that could not fail. It asserted a pound bar surviving the trip
 * through kilograms and back, on the assumption that the last digit drifts:
 * removing the rounding from PlateMath killed NOTHING, because 35, 33 and 55
 * all round-trip exactly in IEEE doubles. (Some do not -- 49.25 lb comes back
 * 49.25000000000001 -- but no case here used one.) The real case is a bar
 * declared in ONE unit and displayed in the OTHER, where there is no round
 * trip to survive: a 15 kg bar is 33.0693339327 lb, and unrounded that is
 * what prints beside the plates. Both are pinned below and the second is what
 * kills the mutation.
 */
class PlateMathBarOverrideTest {
    private fun lb(pounds: Double) = pounds / WeightUnit.LB_PER_KG

    @Test
    fun `the default bar is unchanged when no override is passed`() {
        assertEquals(45.0, PlateMath.defaultBar(WeightUnit.LB))
        assertEquals(20.0, PlateMath.defaultBar(WeightUnit.KG))
        assertEquals(45.0, PlateMath.perSide(lb(195.0), WeightUnit.LB).barWeight)
        assertEquals(20.0, PlateMath.perSide(100.0, WeightUnit.KG).barWeight)
    }

    @Test
    fun `a declared bar decides what is left for the plates`() {
        // 195 lb on a 35 lb bar leaves 80 a side, not 75.
        val b = PlateMath.perSide(lb(195.0), WeightUnit.LB, barKgOverride = lb(35.0))
        assertEquals(35.0, b.barWeight)
        assertEquals(listOf(45.0, 25.0, 10.0), b.platesPerSide)
        assertEquals(0.0, b.leftoverPerSide)
    }

    @Test
    fun `a declared bar survives the round trip through kilograms exactly`() {
        listOf(35.0, 33.0, 55.0).forEach { pounds ->
            assertEquals(pounds, PlateMath.perSide(lb(200.0), WeightUnit.LB, lb(pounds)).barWeight)
        }
        assertEquals(15.0, PlateMath.perSide(80.0, WeightUnit.KG, 15.0).barWeight)
    }

    @Test
    fun `a bar declared in one unit and read in the other is rounded for the screen`() {
        // 15 kg is 33.0693339327 lb. Two decimal places, because a bar is a
        // real object whose weight is worth reading, and eleven of them are
        // not.
        assertEquals(33.07, PlateMath.perSide(lb(200.0), WeightUnit.LB, 15.0).barWeight)
        assertEquals(15.88, PlateMath.perSide(100.0, WeightUnit.KG, lb(35.0)).barWeight)
    }

    @Test
    fun `a load under a declared bar is still below the bar`() {
        assertTrue(PlateMath.perSide(lb(50.0), WeightUnit.LB, lb(55.0)).belowBar)
        assertTrue(!PlateMath.perSide(lb(50.0), WeightUnit.LB, lb(35.0)).belowBar)
    }
}
