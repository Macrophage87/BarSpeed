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

    /**
     * NOT A NO-OP CASE TO SKIP OVER. The settings flow replays the current unit
     * to every new subscriber, so `RecordViewModel`'s collector runs this on
     * launch with `from` equal to `to` -- and if the identity were left to fall
     * out of the arithmetic instead of being stated, opening the app would snap
     * whatever the lifter had typed onto the step grid and strip their spacing.
     * Hence the off-grid `102.3` and the padded string rather than a value the
     * conversion would have returned unchanged anyway.
     */
    @Test
    fun `converting to the unit already shown changes nothing`() {
        for (unit in WeightUnit.entries) {
            val c = SetLoadPolicy.convertedLoad("102.3", unit, unit)
            assertEquals("102.3", c.text)
            assertEquals(unit.toKg(102.3), c.kg)
            assertEquals("  60  ", SetLoadPolicy.convertedLoad("  60  ", unit, unit).text)
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
                // Seeded THROUGH the renderer, both hops, so the starting
                // text is one this function could itself have produced. A raw
                // "$kg" is not: Kotlin writes 0.0 as `0.0` and no conversion
                // ever emits a trailing `.0`.
                val viaOther = SetLoadPolicy.convertedLoad("$kg", WeightUnit.KG, from.other()).text
                val settled = SetLoadPolicy.convertedLoad(viaOther, from.other(), from).text
                val out = SetLoadPolicy.convertedLoad(settled, from, to).text
                val back = SetLoadPolicy.convertedLoad(out, to, from).text
                assertEquals(settled, back, "$settled ${from.suffix} -> $out ${to.suffix} -> $back")
            }
            kg += 0.25
        }
    }
}
