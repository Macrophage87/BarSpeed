package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "Up next" card's second line, over the three implements, both units, an
 * exact and an inexact bar load, and a bar the plan declared itself (#253).
 *
 * These run the decision the card delegates to. What they do NOT run is the
 * card: no test in this repository draws Compose, so which text style and
 * colour the line gets, and where on the card it sits, are compile- and
 * lint-gated only.
 */
class ImplementLineTest {
    private fun lb(pounds: Double) = pounds / WeightUnit.LB_PER_KG

    private fun line(
        implement: Implement,
        addedKg: Double?,
        unit: WeightUnit = WeightUnit.LB,
        count: Int? = null,
        barKg: Double? = null,
    ) = ImplementLine.forCard(implement, addedKg, count, unit, barKg)

    @Test
    fun `other draws nothing, whatever it is loaded with`() {
        assertNull(line(Implement.OTHER, lb(195.0)))
        assertNull(line(Implement.OTHER, lb(80.0), count = 2))
        assertNull(line(Implement.OTHER, null))
    }

    @Test
    fun `an undeclared implement is other`() {
        assertEquals(Implement.OTHER, Implement.ofPlan(null))
        assertNull(line(Implement.ofPlan(null), lb(195.0)))
    }

    @Test
    fun `an unrecognised word is other rather than a guess`() {
        assertEquals(Implement.OTHER, Implement.ofPlan("barbel"))
        assertEquals(Implement.OTHER, Implement.ofPlan(""))
    }

    @Test
    fun `the declared words are the three implements, in any case`() {
        assertEquals(Implement.BARBELL, Implement.ofPlan("barbell"))
        assertEquals(Implement.BARBELL, Implement.ofPlan("Barbell"))
        assertEquals(Implement.DUMBBELL, Implement.ofPlan("dumbbell"))
        assertEquals(Implement.OTHER, Implement.ofPlan("other"))
    }

    @Test
    fun `a barbell at 195 lb is 45 plus 25 plus 5 a side`() {
        assertEquals("45 + 25 + 5 per side", line(Implement.BARBELL, lb(195.0)))
    }

    @Test
    fun `a barbell in kilograms comes off the 20 kg bar`() {
        assertEquals("25 + 15 per side", line(Implement.BARBELL, 100.0, unit = WeightUnit.KG))
    }

    @Test
    fun `an inexact bar load names what one side is missing`() {
        // 191 lb is 73 a side over a 45 lb bar: 45 + 25 + 2.5 leaves half a
        // pound that no plate covers.
        assertEquals(
            "45 + 25 + 2.5 per side, 0.5 lb short per side",
            line(Implement.BARBELL, lb(191.0)),
        )
    }

    @Test
    fun `a declared bar replaces the default and is named`() {
        // 195 lb on a 35 lb bar is 80 a side, and the bar is worth saying
        // because it is not the one the reader assumes.
        assertEquals(
            "45 + 25 + 10 per side, 35 lb bar",
            line(Implement.BARBELL, lb(195.0), barKg = lb(35.0)),
        )
        assertEquals(
            "25 + 5 per side, 15 kg bar",
            line(Implement.BARBELL, 75.0, unit = WeightUnit.KG, barKg = 15.0),
        )
    }

    @Test
    fun `a bar with nothing on it says so`() {
        assertEquals("Empty bar", line(Implement.BARBELL, lb(45.0)))
        assertEquals("Empty 35 lb bar", line(Implement.BARBELL, lb(35.0), barKg = lb(35.0)))
    }

    @Test
    fun `a load under the bar names the bar`() {
        assertEquals("Below the 45 lb bar", line(Implement.BARBELL, lb(30.0)))
        assertEquals("Below the 20 kg bar", line(Implement.BARBELL, 15.0, unit = WeightUnit.KG))
    }

    @Test
    fun `a bar with a remainder and no plate that fits keeps the remainder`() {
        // 47 lb is 1 lb a side, under the smallest plate. The line this
        // replaced rendered "Plates/side:  (+1 short)" here, an empty plate
        // list and all.
        assertEquals("Empty bar, 1 lb short per side", line(Implement.BARBELL, lb(47.0)))
    }

    @Test
    fun `a barbell with no load draws nothing`() {
        assertNull(line(Implement.BARBELL, null))
        assertNull(line(Implement.BARBELL, 0.0))
        assertNull(line(Implement.BARBELL, -20.0))
    }

    @Test
    fun `a dumbbell is a pair without the plan saying so`() {
        assertEquals("2 × 40 lb dumbbells", line(Implement.DUMBBELL, lb(80.0)))
        assertEquals(2, ImplementLine.resolvedCount(Implement.DUMBBELL, null))
    }

    @Test
    fun `a dumbbell count the plan states beats the implied pair`() {
        assertEquals("3 × 20 lb dumbbells", line(Implement.DUMBBELL, lb(60.0), count = 3))
        assertEquals(3, ImplementLine.resolvedCount(Implement.DUMBBELL, 3))
    }

    @Test
    fun `a dumbbell pair splits in kilograms too`() {
        assertEquals("2 × 22.5 kg dumbbells", line(Implement.DUMBBELL, 45.0, unit = WeightUnit.KG))
    }

    @Test
    fun `a dumbbell with no load draws nothing`() {
        assertNull(line(Implement.DUMBBELL, null))
        assertNull(line(Implement.DUMBBELL, 0.0))
    }

    @Test
    fun `only a dumbbell implies a count`() {
        assertEquals(1, ImplementLine.resolvedCount(Implement.OTHER, null))
        assertEquals(1, ImplementLine.resolvedCount(Implement.BARBELL, null))
        assertEquals(2, ImplementLine.resolvedCount(Implement.OTHER, 2))
    }
}
