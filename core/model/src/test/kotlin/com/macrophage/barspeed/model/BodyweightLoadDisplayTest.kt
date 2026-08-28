package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a body-weight set's load is shown and asked for.
 *
 * This class starts as the guards that are already right — the zero of the
 * notation, and the two answers the load box already gives loaded work. The
 * sign, the prefix and the body-weight box label are differentials, in their
 * own commit, so the red is a durable artifact rather than a sentence.
 */
class BodyweightLoadDisplayTest {
    @Test
    fun `a set with nothing added is bare BW`() {
        // Absence and a declared zero share this answer, and only this one:
        // both mean the lifter is the load. Neither renders "0 kg", which
        // would be a weight nobody lifted.
        assertEquals("BW", BodyweightLoadDisplay.label(null, WeightUnit.KG))
        assertEquals("BW", BodyweightLoadDisplay.label(0.0, WeightUnit.KG))
        assertEquals("BW", BodyweightLoadDisplay.label(null, WeightUnit.LB))
        assertEquals("BW", BodyweightLoadDisplay.label(0.0, WeightUnit.LB))
    }

    @Test
    fun `every label is either bare BW or names the unit it is in`() {
        // Weak on purpose, and it says so: this passes on a rendering with no
        // sign and no prefix at all. What it forbids is a magnitude with no
        // unit beside it, in either direction and at either display unit --
        // "BW + 10" is a number a lifter cannot load a bar from.
        val loads = listOf(-100.0, -50.0, -20.0, -0.5, 0.0, 0.5, 10.0, 100.0)
        for (unit in WeightUnit.entries) {
            for (kg in loads) {
                val text = BodyweightLoadDisplay.label(kg, unit)
                assertTrue(
                    text == "BW" || text.endsWith(" ${unit.suffix}"),
                    "$kg in $unit rendered \"$text\", which names no unit",
                )
            }
        }
    }

    @Test
    fun `loaded work keeps the two box labels it already had`() {
        // Behaviour-preserving: these are RecordScreen's landed answers, moved
        // here so the body-weight case can be added to them under test.
        assertEquals("Load (kg)", BodyweightLoadDisplay.fieldLabel(false, null, WeightUnit.KG))
        assertEquals("Load (lb)", BodyweightLoadDisplay.fieldLabel(false, 1, WeightUnit.LB))
        assertEquals("Total load (kg)", BodyweightLoadDisplay.fieldLabel(false, 2, WeightUnit.KG))
        assertEquals("Total load (lb)", BodyweightLoadDisplay.fieldLabel(false, 3, WeightUnit.LB))
    }

    @Test
    fun `a loaded box has no sign convention to explain`() {
        // Absence, not an empty string: a barbell box takes a positive number,
        // so it gets no line rather than a blank one holding space open.
        assertNull(BodyweightLoadDisplay.fieldHint(false))
    }

    @Test
    fun `a nonsense implement count does not change what the box takes`() {
        // ImplementLoad.count coerces 0 and negatives to one, and the label
        // asks it rather than testing the declaration itself, so a count the
        // validator should have rejected cannot make the box claim a split.
        assertEquals("Load (kg)", BodyweightLoadDisplay.fieldLabel(false, 0, WeightUnit.KG))
        assertEquals("Load (kg)", BodyweightLoadDisplay.fieldLabel(false, -2, WeightUnit.KG))
    }
}
