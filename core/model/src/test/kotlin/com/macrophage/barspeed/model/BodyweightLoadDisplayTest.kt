package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a body-weight set's load is shown and asked for.
 *
 * The guards that were already right came first — the zero of the notation,
 * and the two answers the load box already gives loaded work. The sign, the
 * prefix and the body-weight box label are the differentials, added in their
 * own commit and left to run red so the red is a durable artifact rather than
 * a sentence in a report.
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
    fun `a plate on a dip belt is shown as added to the lifter`() {
        // The whole of the ask. "10 kg" beside a pull-up is a claim that ten
        // kilograms is what moved; the lifter and their belt moved rather more
        // than that, and the export's load_kg says so while the screen does
        // not.
        assertEquals("BW + 10 kg", BodyweightLoadDisplay.label(10.0, WeightUnit.KG))
        assertEquals("BW + 2.5 kg", BodyweightLoadDisplay.label(2.5, WeightUnit.KG))
    }

    @Test
    fun `assistance is shown as taken off rather than dropped`() {
        // The case that renders as NOTHING today, because every load render in
        // this app guards on takeIf { it > 0 }. A band or an assist machine is
        // the reason the plan contract permits a negative here at all, and it
        // is the single most common bodyweight prescription for a lifter who
        // cannot yet do the movement unassisted.
        assertEquals("BW − 50 kg", BodyweightLoadDisplay.label(-50.0, WeightUnit.KG))
        assertEquals("BW − 20 kg", BodyweightLoadDisplay.label(-20.0, WeightUnit.KG))
    }

    @Test
    fun `the notation is in the unit the lifter reads, converted and not reinterpreted`() {
        // 10 kg is 22 lb, both ways, and the sign survives the conversion. The
        // kg/lb chip converts; it does not relabel -- so the magnitude here
        // must move with the unit rather than staying put beside a new suffix.
        assertEquals("BW + 22 lb", BodyweightLoadDisplay.label(10.0, WeightUnit.LB))
        assertEquals("BW − 110.2 lb", BodyweightLoadDisplay.label(-50.0, WeightUnit.LB))
        assertEquals("BW + 10 lb", BodyweightLoadDisplay.label(10.0 / WeightUnit.LB_PER_KG, WeightUnit.LB))
    }

    @Test
    fun `an added load too small to render is bare BW and never a zero`() {
        // WeightUnit.format quantises to a tenth of the display unit, so a
        // load under half of that has no digits to show. Bare BW is then the
        // honest render: "BW + 0 kg" states an addition of nothing, and
        // "BW − 0 kg" states assistance of nothing. The zero of the notation
        // already has a spelling.
        assertEquals("BW", BodyweightLoadDisplay.label(0.04, WeightUnit.KG))
        assertEquals("BW", BodyweightLoadDisplay.label(-0.04, WeightUnit.KG))
        assertEquals("BW", BodyweightLoadDisplay.label(0.02, WeightUnit.LB))
    }

    @Test
    fun `every label that carries a magnitude states which way it runs`() {
        // The invariant behind the two differentials above: there is no load
        // for which the notation shows a number without saying whether the
        // lifter carried it or was carried by it. Exactly one of the two signs,
        // never both, never neither.
        val loads = listOf(-100.0, -50.0, -20.0, -1.0, 1.0, 20.0, 50.0, 100.0)
        for (unit in WeightUnit.entries) {
            for (kg in loads) {
                val text = BodyweightLoadDisplay.label(kg, unit)
                assertTrue(
                    text.startsWith("BW + ") != text.startsWith("BW − "),
                    "$kg in $unit rendered \"$text\", which states no direction",
                )
                assertTrue(
                    text.startsWith("BW + ") == (kg > 0),
                    "$kg in $unit rendered \"$text\", which runs the wrong way",
                )
            }
        }
    }

    @Test
    fun `a body-weight box says whose weight the number is added to`() {
        // "Load (kg)" on a pull-up invites the whole loaded weight, which is
        // then recorded as the ADDED load and has body weight added to it
        // again at SetLoadPolicy.totalKg. The box has to say what it takes.
        assertEquals("Added to body weight (kg)", BodyweightLoadDisplay.fieldLabel(true, null, WeightUnit.KG))
        assertEquals("Added to body weight (lb)", BodyweightLoadDisplay.fieldLabel(true, 1, WeightUnit.LB))
    }

    @Test
    fun `a pair of dumbbells does not make a body-weight box say Total`() {
        // Two words that must not meet. "Total" is about the objects held; on
        // body-weight work the box takes the added load whether it is on a
        // belt or in two hands, and "Total load" beside a dip would read as
        // the whole loaded weight -- the exact misreading the label exists to
        // stop. The split still shows UNDER the box, where it is derived.
        assertEquals("Added to body weight (kg)", BodyweightLoadDisplay.fieldLabel(true, 2, WeightUnit.KG))
    }

    @Test
    fun `a body-weight box says which way the sign runs`() {
        // A signed box is unusable unless the lifter knows the direction, and
        // nothing else on the screen says it.
        val hint = BodyweightLoadDisplay.fieldHint(true)
        assertEquals("Negative for a band or assist machine taking weight off", hint)
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
