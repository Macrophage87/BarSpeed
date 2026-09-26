package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlateMathTest {
    @Test
    fun `pound loading uses 45 bar and standard plates`() {
        // 190 lb total → (190-45)/2 = 72.5/side = 45 + 25 + 2.5
        val b = PlateMath.perSide(190.0 / WeightUnit.LB_PER_KG, WeightUnit.LB)
        assertEquals(45.0, b.barWeight)
        assertEquals(listOf(45.0, 25.0, 2.5), b.platesPerSide)
        assertEquals(0.0, b.leftoverPerSide)
    }

    @Test
    fun `kg loading uses 20 bar`() {
        // 100 kg → 40/side = 25 + 15
        val b = PlateMath.perSide(100.0, WeightUnit.KG)
        assertEquals(20.0, b.barWeight)
        assertEquals(listOf(25.0, 15.0), b.platesPerSide)
        assertEquals(0.0, b.leftoverPerSide)
    }

    @Test
    fun `empty bar and below-bar loads`() {
        val empty = PlateMath.perSide(45.0 / WeightUnit.LB_PER_KG, WeightUnit.LB)
        assertEquals(emptyList(), empty.platesPerSide)
        assertTrue(!empty.belowBar)
        assertTrue(PlateMath.perSide(10.0, WeightUnit.KG).belowBar)
    }

    @Test
    fun `unloadable remainder is reported`() {
        // 48 lb over the bar per side... 191 lb → 73/side = 45+25+2.5 leaves 0.5
        val b = PlateMath.perSide(191.0 / WeightUnit.LB_PER_KG, WeightUnit.LB)
        assertEquals(listOf(45.0, 25.0, 2.5), b.platesPerSide)
        assertTrue(b.leftoverPerSide in 0.4..0.6, "leftover ${b.leftoverPerSide}")
    }
}
