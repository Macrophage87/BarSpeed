package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bar a plan can declare for itself (`bar_lb` / `bar_kg`, #253), and the
 * default that stands when it does not.
 *
 * The rounding case is the one worth having: a 35 lb bar is stored as
 * 15.8757... kg, and without rounding the display unit it comes back as
 * 34.99999999999999 lb and prints that way beside a rack.
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
    fun `a load under a declared bar is still below the bar`() {
        assertTrue(PlateMath.perSide(lb(50.0), WeightUnit.LB, lb(55.0)).belowBar)
        assertTrue(!PlateMath.perSide(lb(50.0), WeightUnit.LB, lb(35.0)).belowBar)
    }
}
