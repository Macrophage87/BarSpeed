package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which plates [PlateMath] actually offers, and which bar it assumes, pinned
 * BEFORE #253 decides where the "Up next" card's second line gets its loading
 * from.
 *
 * #253's body names an lb plate set of 45, 35, 25, 10, 5 and 2.5. The code has
 * shipped 45, 25, 10, 5 and 2.5 with NO 35 since plate math existed, and the
 * issue's own comment says to check the two against each other and pin
 * whichever is right rather than assume the body. This file is the
 * measurement that check is made against, and `every 2.5 lb step ... is
 * loadable exactly` is the evidence for keeping the shipped set: with no 35 lb
 * plate, every per-side weight a set WITH one could reach is still reached
 * exactly, so a 35 would change only which plates the card NAMES -- and it
 * would sometimes name a plate the rack may not have, which is an instruction
 * the lifter cannot follow. A 45/25/10/5/2.5 decomposition is loadable in any
 * gym that also stocks 35s; the reverse is not true.
 *
 * The kg set and both default bar weights DO match the issue's body, and are
 * pinned here for the same reason: #253 moves the caller, and nothing should
 * move underneath it unnoticed.
 */
class PlateSetCharacterizationTest {
    private fun lb(total: Double) = PlateMath.perSide(total / WeightUnit.LB_PER_KG, WeightUnit.LB)

    @Test
    fun `the pound set has no 35 and splits 35 per side into 25 plus 10`() {
        // 115 lb is 35 a side over a 45 lb bar: one 35 with the issue's set,
        // two plates without it. This case is the whole difference.
        assertEquals(listOf(25.0, 10.0), lb(115.0).platesPerSide)
        // 205 lb is 80 a side: 45 + 35 with the issue's set, three plates here.
        assertEquals(listOf(45.0, 25.0, 10.0), lb(205.0).platesPerSide)
    }

    @Test
    fun `the pound set is 45 25 10 5 and 2p5, heaviest first`() {
        // 220 lb is 87.5 a side: one of every plate in the set, in order.
        assertEquals(listOf(45.0, 25.0, 10.0, 5.0, 2.5), lb(220.0).platesPerSide)
        assertEquals(0.0, lb(220.0).leftoverPerSide)
    }

    @Test
    fun `the kilogram set is the one the issue names`() {
        // Each plate isolated by asking for exactly its weight a side. The
        // greedy loop takes unlimited plates of one size, so no single load
        // can name all seven -- a load worth one of each comes back as three
        // 25s and change.
        listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0, 25.0).forEach { plate ->
            val b = PlateMath.perSide(20.0 + 2 * plate, WeightUnit.KG)
            assertEquals(listOf(plate), b.platesPerSide, "$plate kg a side")
        }
    }

    @Test
    fun `the pound set is exactly five plates`() {
        listOf(2.5, 5.0, 10.0, 25.0, 45.0).forEach { plate ->
            assertEquals(listOf(plate), lb(45.0 + 2 * plate).platesPerSide, "$plate lb a side")
        }
    }

    @Test
    fun `the bars are 45 lb and 20 kg`() {
        assertEquals(45.0, lb(135.0).barWeight)
        assertEquals(20.0, PlateMath.perSide(100.0, WeightUnit.KG).barWeight)
    }

    @Test
    fun `every 2p5 lb step up to 100 a side is loadable exactly`() {
        var perSide = 0.0
        while (perSide <= 100.0) {
            val b = lb(45.0 + 2 * perSide)
            assertEquals(0.0, b.leftoverPerSide, "no exact loading for $perSide lb a side")
            assertTrue(
                Math.abs(b.platesPerSide.sum() - perSide) < 0.05,
                "plates ${b.platesPerSide} do not sum to $perSide lb a side",
            )
            perSide += 2.5
        }
    }

    @Test
    fun `every 1p25 kg step up to 60 a side is loadable exactly`() {
        var perSide = 0.0
        while (perSide <= 60.0) {
            val b = PlateMath.perSide(20.0 + 2 * perSide, WeightUnit.KG)
            assertEquals(0.0, b.leftoverPerSide, "no exact loading for $perSide kg a side")
            assertTrue(
                Math.abs(b.platesPerSide.sum() - perSide) < 0.05,
                "plates ${b.platesPerSide} do not sum to $perSide kg a side",
            )
            perSide += 1.25
        }
    }
}
