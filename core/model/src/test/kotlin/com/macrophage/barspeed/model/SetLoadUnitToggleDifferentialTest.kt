package com.macrophage.barspeed.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #77: tapping the kg/lb chip must CONVERT the typed load, not reinterpret it.
 *
 * Every assertion in this file fails at the commit that introduces it, against
 * the identity seam `SetLoadPolicy.convertedLoad` was landed as. The failures
 * are the defect itself, stated as numbers: a field reading `100` with a kg
 * chip goes on reading `100` after the tap, is re-parsed as 100 POUNDS by
 * `endSet`, and the set is recorded at 45.36 kg. Load is metadata the IMU
 * stream cannot reconstruct, so unlike a wrong derived figure that number is
 * not recoverable.
 */
class SetLoadUnitToggleDifferentialTest {
    @Test
    fun `a hundred kilos is two hundred and twenty and a half pounds`() {
        assertEquals("220.5", SetLoadPolicy.convertedLoad("100", WeightUnit.KG, WeightUnit.LB).text)
    }

    @Test
    fun `two hundred and twenty five pounds is a hundred and two kilos`() {
        assertEquals("102", SetLoadPolicy.convertedLoad("225", WeightUnit.LB, WeightUnit.KG).text)
    }

    /**
     * The bug, named as the number it produces. Before the fix the text is
     * still `100` and `WeightUnit.LB.parseToKg` reads it as 45.36 kg.
     */
    @Test
    fun `the load the field names does not move by a factor of the conversion`() {
        val converted = SetLoadPolicy.convertedLoad("100", WeightUnit.KG, WeightUnit.LB)
        val nowNames = WeightUnit.LB.parseToKg(converted.text)!!
        assertEquals(100.0, converted.kg)
        assertTrue(abs(nowNames - 100.0) < 0.02, "field now names $nowNames kg, expected ~100")
    }

    /**
     * The bound the KDoc claims, checked rather than asserted: half a step of
     * the unit being converted TO. 0.125 kg going to kg, 0.25 lb going to lb.
     */
    @Test
    fun `no conversion moves the load by more than half the new unit's step`() {
        var kg = 0.25
        while (kg <= 400.0) {
            for (from in WeightUnit.entries) {
                val to = from.other()
                val typed = if (from == WeightUnit.KG) "$kg" else "${from.fromKg(kg)}"
                val before = from.parseToKg(typed)!!
                val after = to.parseToKg(SetLoadPolicy.convertedLoad(typed, from, to).text)!!
                val boundKg = to.toKg(SetLoadPolicy.displayStep(to) / 2.0)
                assertTrue(
                    abs(after - before) <= boundKg + 1e-9,
                    "$typed ${from.suffix} -> ${to.suffix} moved ${abs(after - before)} kg, bound $boundKg",
                )
            }
            kg += 0.25
        }
    }

    /**
     * Assisted body-weight work declares a NEGATIVE added load, and a band
     * taking 20 kg off a pull-up is exactly the case a naive rounding gets
     * backwards.
     */
    @Test
    fun `an assisted load keeps its sign`() {
        assertEquals("-44", SetLoadPolicy.convertedLoad("-20", WeightUnit.KG, WeightUnit.LB).text)
        assertEquals("-20", SetLoadPolicy.convertedLoad("-44", WeightUnit.LB, WeightUnit.KG).text)
    }

    /**
     * NOT `WeightUnit.inputValue`, which quantises to 0.1 of the display unit
     * and cannot write a quarter at all: fed the exact conversion of 8 lb it
     * gives `3.6`, and fed the quarter-kilo answer it gives `3.8`. Neither is
     * 3.75. A quarter-kilo grid needs two decimal places to survive being
     * written down, and `inputValue` is the wrong renderer for it (#45).
     */
    @Test
    fun `a quarter-kilo result is not flattened to a tenth`() {
        assertEquals("3.75", SetLoadPolicy.convertedLoad("8", WeightUnit.LB, WeightUnit.KG).text)
        assertEquals("3.6", WeightUnit.KG.inputValue(8 / WeightUnit.LB_PER_KG))
        assertEquals("3.8", WeightUnit.KG.inputValue(3.75))
    }

    @Test
    fun `a whole number renders without a decimal point`() {
        assertEquals("132.5", SetLoadPolicy.convertedLoad("60", WeightUnit.KG, WeightUnit.LB).text)
        assertEquals("226", SetLoadPolicy.convertedLoad("102.5", WeightUnit.KG, WeightUnit.LB).text)
    }
}
