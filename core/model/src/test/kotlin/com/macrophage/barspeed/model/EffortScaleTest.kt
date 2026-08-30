package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The effort scale, pinned at the level a field session revises it.
 *
 * Two different things are asserted here and they are worth telling apart.
 * The ANCHORS -- which `rpe` a tile records -- are contract: moving one
 * reinterprets every set already recorded at that value, so a change must be
 * visible in a diff and argued in a version log. The CAPTIONS are data, and
 * the whole reason the table exists is that revising "10-15 lb" after a
 * session should be a data change rather than a redesign.
 *
 * The load-bearing test in this file is
 * `no caption is a unit conversion of the other unit's figure`. The
 * regression it guards is not hypothetical: [WeightUnit.format] is sitting
 * right there, it is what every other load in the app is rendered through,
 * and routing the kg column through it ships "Could have added 4.5 kg" --
 * a question with no answerable answer, on the one scale whose entire
 * justification is asking for numbers the lifter can supply.
 */
class EffortScaleTest {
    private fun repTiles(unit: WeightUnit = WeightUnit.LB) =
        EffortScale.tiles(timed = false, explosive = false, unit = unit)

    private fun timedTiles(unit: WeightUnit = WeightUnit.LB) =
        EffortScale.tiles(timed = true, explosive = false, unit = unit)

    private fun explosiveTiles(unit: WeightUnit = WeightUnit.LB) = EffortScale.tiles(false, explosive = true, unit)

    private fun allLadders() = listOf(WeightUnit.KG, WeightUnit.LB).flatMap { unit ->
        listOf(repTiles(unit), timedTiles(unit), explosiveTiles(unit))
    }

    /** Every number written in a caption, however it is punctuated. */
    private fun figuresIn(text: String): List<Int> =
        Regex("""\d+(\.\d+)?""").findAll(text).map { it.value }.map { it.toDouble() }
            .map { it.toInt() }.toList()

    @Test
    fun `the scale anchors at one, four, six and seven through ten`() {
        allLadders().forEach { tiles ->
            assertEquals(
                listOf(1, 4, 6, 7, 8, 9, 10),
                tiles.mapNotNull { it.rpe },
                "the anchors moved, which reinterprets every set already recorded at one of them",
            )
        }
    }

    @Test
    fun `the values between the anchors are valid and carry no tile`() {
        // The gaps exist for ORDERING, which is the owner's ruling that a
        // split categorical/numeric export is too hard to analyse. A reader
        // meeting a 5 from an older session must be able to read it as a real
        // value on the same ruler, so this pins that the unanchored values are
        // named rather than merely absent.
        val anchored = repTiles().mapNotNull { it.rpe }.toSet()
        assertEquals(
            emptySet(),
            anchored intersect EffortScale.UNANCHORED_RPE,
            "a value is both anchored and declared unanchored",
        )
        assertEquals(
            (1..10).toSet(),
            anchored + EffortScale.UNANCHORED_RPE,
            "the anchored and unanchored values do not cover the published 1-10 range",
        )
    }

    @Test
    fun `the tiles run easiest first with the failure tile last`() {
        allLadders().forEach { tiles ->
            val rpes = tiles.mapNotNull { it.rpe }
            assertEquals(rpes.sorted(), rpes, "the tiles are not in ascending effort order")
            assertEquals(EffortClaim.FAILED, tiles.last().claim, "the failure tile is not last")
            assertEquals(
                1,
                tiles.count { it.claim == EffortClaim.FAILED },
                "a ladder offers more than one way to fail",
            )
        }
    }

    @Test
    fun `the three lowest rungs ask headroom and the four highest ask proximity`() {
        allLadders().forEach { tiles ->
            tiles.filter { it.claim == EffortClaim.HEADROOM }.forEach {
                assertTrue(
                    it.rpe!! < EffortScale.PROXIMITY_FLOOR_RPE,
                    "a headroom tile sits at rpe ${it.rpe}, inside the counted band",
                )
            }
            tiles.filter { it.claim == EffortClaim.PROXIMITY }.forEach {
                assertTrue(
                    it.rpe!! >= EffortScale.PROXIMITY_FLOOR_RPE,
                    "a proximity tile sits at rpe ${it.rpe}, where the count is not supportable",
                )
            }
        }
    }

    @Test
    fun `no rung asks about five pounds, which is the rule the whole scale is chosen by`() {
        // "If you could have added 5 lbs, you're probably near a level that
        // you know the RIR for in most cases anyway." The absence is a
        // decision: a headroom tile there would sit exactly where the rep
        // count is most accurate. Asserted as the FLOOR of the offered
        // figures rather than as a missing string, because "10-15 lb"
        // contains the substring "5 lb".
        val lbFigures =
            repTiles(WeightUnit.LB).filter { it.claim == EffortClaim.HEADROOM }
                .flatMap { figuresIn(it.label) }
        assertEquals(10, lbFigures.min(), "the pound ladder offers a jump smaller than one plate pair")
        assertFalse(5 in repTiles().mapNotNull { it.rpe }, "rpe 5 gained a tile, closing the deliberate gap")
    }

    @Test
    fun `every headroom caption names a figure the lifter can actually add`() {
        // A conversion produces 4.5, 6.8, 9.1, 13.6 -- none of them a
        // multiple of 5, and none of them a weight in either gym.
        allLadders().flatten().filter { it.claim == EffortClaim.HEADROOM }.forEach { tile ->
            figuresIn(tile.label).forEach { figure ->
                assertEquals(
                    0,
                    figure % EffortScale.GYM_INCREMENT_MULTIPLE,
                    "\"${tile.label}\" names $figure, which is not a jump either gym offers",
                )
            }
        }
    }

    @Test
    fun `no caption is a unit conversion of the other unit's figure`() {
        // The exact regression: someone simplifies the table into
        // WeightUnit.format() and the kg column becomes the lb column
        // converted. These are the strings that would appear if they did.
        val converted =
            listOf(10.0, 15.0, 20.0, 30.0).map { WeightUnit.KG.format(WeightUnit.LB.toKg(it)) } +
                listOf(5.0, 10.0).map { WeightUnit.LB.format(it) }
        val captions = allLadders().flatten().map { it.label }
        converted.forEach { rendered ->
            assertTrue(
                captions.none { rendered in it },
                "a caption carries \"$rendered\", which is a conversion rather than an authored figure",
            )
        }
        // And the positive half, so the test cannot pass by the table being
        // empty: the kg rungs say what they are authored to say.
        assertEquals(
            "Could have added 5 kg",
            EffortScale.headroomCaption(HeadroomTier.ONE_INCREMENT, EffortAsk.LOAD, WeightUnit.KG),
        )
        assertEquals(
            "Could have added 10 kg",
            EffortScale.headroomCaption(HeadroomTier.TWO_INCREMENTS, EffortAsk.LOAD, WeightUnit.KG),
        )
    }

    @Test
    fun `the authored load table is one increment and two increments in each unit`() {
        assertEquals(
            "Could have added 10-15 lb",
            EffortScale.headroomCaption(HeadroomTier.ONE_INCREMENT, EffortAsk.LOAD, WeightUnit.LB),
        )
        assertEquals(
            "Could have added 20-30 lb",
            EffortScale.headroomCaption(HeadroomTier.TWO_INCREMENTS, EffortAsk.LOAD, WeightUnit.LB),
        )
        listOf(WeightUnit.KG, WeightUnit.LB).forEach { unit ->
            assertEquals(
                "Could have added much more",
                EffortScale.headroomCaption(HeadroomTier.MUCH_MORE, EffortAsk.LOAD, unit),
            )
        }
    }

    @Test
    fun `a hold asks for time and never for load`() {
        // Load headroom is meaningless on a plank. Same tiers, same anchors,
        // different noun -- and the noun is what this asserts, because the
        // anchors alone cannot tell a hold's ladder from a bench press's.
        val headroom = timedTiles().filter { it.claim == EffortClaim.HEADROOM }
        assertEquals(3, headroom.size, "a hold lost a headroom rung")
        headroom.forEach { tile ->
            assertFalse(" lb" in tile.label, "a hold is asked for load headroom: ${tile.label}")
            assertFalse(" kg" in tile.label, "a hold is asked for load headroom: ${tile.label}")
            assertTrue("longer" in tile.label, "a hold's headroom rung does not ask about time: ${tile.label}")
        }
        listOf(WeightUnit.KG, WeightUnit.LB).forEach { unit ->
            assertEquals(
                timedTiles(WeightUnit.KG).map { it.label },
                timedTiles(unit).map { it.label },
                "a hold's wording changes with the weight unit, which it cannot depend on",
            )
        }
    }

    @Test
    fun `a rep set asks for load in the unit the lifter reads`() {
        listOf(WeightUnit.KG to " kg", WeightUnit.LB to " lb").forEach { (unit, suffix) ->
            val counted =
                repTiles(unit).filter { it.claim == EffortClaim.HEADROOM && it.rpe != HeadroomTier.MUCH_MORE.rpe }
            assertTrue(counted.isNotEmpty(), "the $unit ladder offers no figure at all")
            counted.forEach { tile ->
                assertTrue(suffix in tile.label, "a $unit tile does not name the unit: ${tile.label}")
            }
        }
    }

    @Test
    fun `warm-up is not a rung of the effort scale`() {
        // It is what a set is FOR, not how it went. The tile recorded
        // `warmup = true` AND `rpe = null`, discarding the effort by
        // construction; it is a plan declaration now.
        allLadders().flatten().forEach { tile ->
            assertFalse("arm-up" in tile.label, "the warm-up tile is back on the effort scale: ${tile.label}")
            assertTrue(
                tile.rpe != null || tile.claim == EffortClaim.FAILED,
                "a tile stores no rpe and is not the failure tile: ${tile.label}",
            )
        }
    }

    @Test
    fun `the failure tile stores no rpe and says what failing means for that kind`() {
        assertEquals(EffortTile(null, EffortClaim.FAILED, "Failed the set"), repTiles().last())
        assertEquals(EffortTile(null, EffortClaim.FAILED, "Broke early - failed"), timedTiles().last())
        assertEquals(EffortTile(null, EffortClaim.FAILED, "Missed the lift"), explosiveTiles().last())
    }

    @Test
    fun `the counted rungs of a rep set are a rep count`() {
        assertEquals(
            listOf("3 reps left", "2 reps left", "1 rep left", "Nothing left"),
            repTiles().filter { it.claim == EffortClaim.PROXIMITY }.map { it.label },
        )
    }

    @Test
    fun `the timed and explosive proximity wording is the wording that shipped`() {
        // Characterization. Redefining an anchor predictably shifts the
        // ratings people give, so these four rungs each are pinned as they
        // are -- a reword becomes a visible diff on a test rather than a
        // quiet change to what a stored 9 means.
        assertEquals(
            listOf("Had more in me", "A little left", "Seconds left", "Hit my limit"),
            timedTiles().filter { it.claim == EffortClaim.PROXIMITY }.map { it.label },
        )
        assertEquals(
            listOf("Fast and crisp", "Speed dropping", "Grindy", "Barely made it"),
            explosiveTiles().filter { it.claim == EffortClaim.PROXIMITY }.map { it.label },
        )
    }
}
