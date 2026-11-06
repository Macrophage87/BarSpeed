package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SetLoadPolicy.loadEntryAccepted]: when a load box's text may stand for a set. */
class SetLoadEntryTest {
    private fun adHoc(text: String, bodyweight: Boolean = false, unit: WeightUnit = WeightUnit.KG) =
        SetLoadPolicy.loadEntryAccepted(adHoc = true, typedText = text, unit = unit, bodyweight = bodyweight)

    private fun plan(text: String, bodyweight: Boolean = false) =
        SetLoadPolicy.loadEntryAccepted(adHoc = false, typedText = text, unit = WeightUnit.KG, bodyweight = bodyweight)

    @Test
    fun `an ad-hoc set with a blank load box does not start as 0 kg`() {
        assertFalse(adHoc(""))
        assertFalse(adHoc("   "))
    }

    @Test
    fun `an ad-hoc set with an unparseable load box does not start`() {
        assertFalse(adHoc("60kg"))
        assertFalse(adHoc("abc"))
        assertFalse(adHoc("NaN"))
    }

    @Test
    fun `an ad-hoc set starts on any number, an explicit 0 and a comma decimal included`() {
        assertTrue(adHoc("60"))
        assertTrue(adHoc("0"))
        assertTrue(adHoc("52,5"))
        assertTrue(adHoc("-20", bodyweight = true))
        assertTrue(adHoc("135", unit = WeightUnit.LB))
    }

    /** Nothing added is the ordinary body-weight set, and blank records exactly that. */
    @Test
    fun `an ad-hoc body-weight set starts with a blank box`() {
        assertTrue(adHoc("", bodyweight = true))
        assertFalse(adHoc("x", bodyweight = true))
    }

    /** A blank plan box states nothing, and the plan's load stands. */
    @Test
    fun `a plan set's blank box is accepted`() {
        assertTrue(plan(""))
        assertTrue(plan("", bodyweight = true))
    }

    @Test
    fun `a plan set's unparseable box is refused rather than running the plan's load`() {
        assertFalse(plan("100kg"))
        assertFalse(plan("abc"))
        assertTrue(plan("100"))
        assertTrue(plan("102,5"))
    }

    @Test
    fun `the refusal says what to do`() {
        assertEquals("Enter a load", SetLoadPolicy.ENTER_A_LOAD)
    }
}
