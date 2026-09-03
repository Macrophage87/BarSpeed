package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The kg/lb chip is a DISPLAY action: what the load field says changes, what
 * the set records does not (#77).
 *
 * These are the clauses of [SetLoadPolicy.convertedLoad]'s contract that hold
 * before the fix as well as after -- the pass-through cases, the step table,
 * and double-tap stability, which the identity seam satisfies trivially. The
 * clauses that do NOT hold before the fix are pinned separately in
 * `SetLoadUnitToggleDifferentialTest`, which is red at its own commit.
 */
class SetLoadUnitToggleTest {
    @Test
    fun `the display step is a micro-plate, not a plate`() {
        assertEquals(0.25, SetLoadPolicy.displayStep(WeightUnit.KG))
        assertEquals(0.5, SetLoadPolicy.displayStep(WeightUnit.LB))
        // Distinct from the correction stepper's plate, which is 20x coarser.
        assertEquals(2.5, SetLoadPolicy.correctionStepKg(WeightUnit.KG))
    }

    @Test
    fun `a field naming no number is left exactly as the lifter left it`() {
        for (text in listOf("", "   ", "-", "abc", "1.2.3")) {
            val c = SetLoadPolicy.convertedLoad(text, WeightUnit.KG, WeightUnit.LB)
            assertEquals(text, c.text, "text `$text`")
            assertNull(c.kg, "kg for `$text`")
        }
    }

    @Test
    fun `converting to the unit already shown changes nothing`() {
        for (unit in WeightUnit.entries) {
            val c = SetLoadPolicy.convertedLoad("102.5", unit, unit)
            assertEquals("102.5", c.text)
            assertEquals(unit.toKg(102.5), c.kg)
        }
    }

    @Test
    fun `the kg reported back is the one the OLD text named`() {
        assertEquals(100.0, SetLoadPolicy.convertedLoad("100", WeightUnit.KG, WeightUnit.LB).kg)
        assertEquals(
            100.0 / WeightUnit.LB_PER_KG,
            SetLoadPolicy.convertedLoad("100", WeightUnit.LB, WeightUnit.KG).kg,
        )
    }

    /**
     * The hazard #77 named: the chip is one tap and the lifter can tap it
     * twice. Converting a value this function itself produced, out and back,
     * must return the identical string -- otherwise a lifter checking the other
     * unit and changing their mind is charged for the look.
     *
     * A hand-typed value that is not on the new unit's step lattice IS
     * quantised, once, on the first tap. That is the bounded loss the test
     * above pins. From the second tap onward there is none.
     */
    @Test
    fun `toggling twice returns the same text, exhaustively`() {
        var kg = 0.0
        while (kg <= 400.0) {
            for (from in WeightUnit.entries) {
                val to = from.other()
                val settled = SetLoadPolicy.convertedLoad("$kg", WeightUnit.KG, from).text
                val out = SetLoadPolicy.convertedLoad(settled, from, to).text
                val back = SetLoadPolicy.convertedLoad(out, to, from).text
                assertEquals(settled, back, "$settled ${from.suffix} -> $out ${to.suffix} -> $back")
            }
            kg += 0.25
        }
    }
}
