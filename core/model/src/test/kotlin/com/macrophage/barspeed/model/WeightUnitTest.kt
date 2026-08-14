package com.macrophage.barspeed.model

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

    /**
     * `inputValue` is lossy: it quantises to 0.1 of the DISPLAY unit. Anything
     * that routes a load through a text field therefore rounds it, and the
     * record flow does exactly that between sets.
     */
    @Test
    fun `inputValue quantises to a tenth`() {
        assertEquals("100.1", WeightUnit.KG.inputValue(100.06))
        assertEquals("100", WeightUnit.KG.inputValue(100.04))
        assertEquals("0", WeightUnit.KG.inputValue(0.0))
        // Rounding happens AFTER the conversion, so the DISPLAY unit decides how
        // much is lost. A 175 lb load is 79.3786... kg in storage and survives an
        // LB display bit-identically, because the conversion cancels in a
        // same-unit round trip; the same load seeds "79.4" on a KG display.
        assertEquals("175", WeightUnit.LB.inputValue(175 / WeightUnit.LB_PER_KG))
        assertEquals("79.4", WeightUnit.KG.inputValue(175 / WeightUnit.LB_PER_KG))
    }

    /**
     * A blank field parses to null, not to zero. Callers that elvis a parse
     * result into a load are choosing what an empty box means, and the two
     * available meanings — "keep what was planned" and "nothing was added" —
     * are different numbers.
     */
    @Test
    fun `parseToKg returns null for a blank or non-numeric field`() {
        assertNull(WeightUnit.KG.parseToKg(""))
        assertNull(WeightUnit.KG.parseToKg("   "))
        assertNull(WeightUnit.KG.parseToKg("-"))
        assertEquals(0.0, WeightUnit.KG.parseToKg("0"))
    }

    @Test
    fun `other toggles`() {
        assertEquals(WeightUnit.LB, WeightUnit.KG.other())
        assertEquals(WeightUnit.KG, WeightUnit.LB.other())
    }
}
