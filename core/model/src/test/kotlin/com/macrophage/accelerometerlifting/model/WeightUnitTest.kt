package com.macrophage.accelerometerlifting.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightUnitTest {
    @Test
    fun `kg round trip is identity`() {
        assertEquals(120.0, WeightUnit.KG.toKg(120.0))
        assertEquals("120 kg", WeightUnit.KG.format(120.0))
        assertEquals("120", WeightUnit.KG.inputValue(120.0))
    }

    @Test
    fun `lb conversion is accurate`() {
        assertTrue(abs(WeightUnit.LB.fromKg(100.0) - 220.46) < 0.01)
        assertTrue(abs(WeightUnit.LB.toKg(225.0) - 102.06) < 0.01)
        assertEquals("264.6 lb", WeightUnit.LB.format(120.0))
    }

    @Test
    fun `parse input in selected unit returns kg`() {
        val kg = WeightUnit.LB.parseToKg("225")
        assertTrue(kg != null && abs(kg - 102.06) < 0.01)
        assertNull(WeightUnit.LB.parseToKg("abc"))
    }

    @Test
    fun `other toggles`() {
        assertEquals(WeightUnit.LB, WeightUnit.KG.other())
        assertEquals(WeightUnit.KG, WeightUnit.LB.other())
    }
}
