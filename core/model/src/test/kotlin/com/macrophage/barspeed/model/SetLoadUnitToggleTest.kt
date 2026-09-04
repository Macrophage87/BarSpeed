package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
     * BLIND SPOT, NAMED RATHER THAN LEFT IMPLICIT: `settled` here is always
     * built by hopping off the KG lattice (`viaOther`, then back through
     * `from`), so this sweep only ever asks the kg-lattice-seeded question.
     * It structurally cannot see an LB-lattice text that never had a kg value
     * pass through it -- `SetLoadPolicy.convertedLoad`'s own KDoc names 93 of
     * 1001 lb-lattice values, checked over 0-500 lb by 0.5, that do not
     * survive lb-to-kg-to-lb. `a value seeded directly on the lb lattice
     * settles by the second tap, not the first`, below, pins that case
     * instead: it is stable one tap later than the kg-lattice case is, not
     * on the same tap a since-corrected claim here used to assert.
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

    /**
     * The case the sweep above cannot see: a text seeded directly on the LB
     * lattice, with no kg value ever entering it. `105` lb is one of 93 (of
     * 1001 checked, 0-500 lb by 0.5) that do not survive lb-to-kg-to-lb,
     * because 0.25 kg (the KG step) is 0.5512 lb, wider than the 0.5 lb
     * step, so two adjacent LB-lattice values can snap to the same KG value.
     *
     * Tap 1 (LB -> KG) already lands on a KG-lattice value, `47.75`, and from
     * there the KG side is a fixed point: tap 3 repeats tap 1 exactly. But
     * tap 1's own LB ancestor, `105`, is NOT reproduced by tap 2 (KG -> LB):
     * tap 2 gives `105.5`, and tap 4 repeats tap 2. So the LB text drifts
     * once more after the tap that looked, from the KG side, already settled
     * -- two taps to a fixed point, not one.
     *
     * ONE WORKED VALUE, NOT A SWEEP. The general property -- every LB-lattice
     * text reaches a fixed point by tap 2 -- was checked offline at this SHA
     * over 0-900 lb by 0.5 (1801 values, 0 fail) and is not pinned here.
     */
    @Test
    fun `a value seeded directly on the lb lattice settles by the second tap, not the first`() {
        val tap1 = SetLoadPolicy.convertedLoad("105", WeightUnit.LB, WeightUnit.KG).text
        val tap2 = SetLoadPolicy.convertedLoad(tap1, WeightUnit.KG, WeightUnit.LB).text
        val tap3 = SetLoadPolicy.convertedLoad(tap2, WeightUnit.LB, WeightUnit.KG).text
        val tap4 = SetLoadPolicy.convertedLoad(tap3, WeightUnit.KG, WeightUnit.LB).text
        assertEquals("47.75", tap1)
        assertEquals("105.5", tap2)
        assertNotEquals("105", tap2, "the lb text drifts past its own ancestor")
        assertEquals(tap1, tap3, "the kg side is already a fixed point by tap 1")
        assertEquals(tap2, tap4, "the lb side needs tap 2 to reach its fixed point")
    }
}
