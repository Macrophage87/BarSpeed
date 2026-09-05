package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #244's wording table and its grid suggestion, every case, RED AT THIS
 * COMMIT.
 *
 * At the commit before this one `EffortScale.askFor` is a declared seam that
 * reads the exercise's declaration and discards it, `REPS_CAPTIONS` and
 * `FEEL_CAPTIONS` are empty maps so `headroomCaption` throws for either ask,
 * the timed captions still say "15-30 s longer" and "about a minute longer",
 * and `NextSetNudgePolicy.SUGGESTED_INDEX` is empty so nothing is suggested.
 *
 * NINE OF THE FOURTEEN METHODS FAIL HERE, each for one of those four reasons;
 * measured by running the suite at this commit, not asserted. The other five
 * are INVARIANCE pins and are green on both sides on purpose -- that an omitted declaration still asks what it
 * always asked, that no timed set is ever asked about load, that the load row
 * and the counted end do not move, and that nothing is suggested where nothing
 * is offered. They are worth nothing as differentials and everything as the
 * other half of the contract, which is why they are named as green rather than
 * counted among the reds.
 *
 * ## Which table this is
 *
 * NOT the one in #244's body. That table was superseded by three owner
 * comments dated 2026-09-04, and its reps row was wrong in a way the owner
 * named: it put "1-2 more reps" on rung 6, but the counted end already covers
 * one, two and three left, and one or two in reserve is the TARGET rather than
 * headroom. The rows asserted here are the corrected ones:
 *
 * | rung | weight | reps | time | none |
 * |---|---|---|---|---|
 * | 6 | one increment | about 3-4 left | about 15 s | comfortable |
 * | 4 | two increments | five or more left | about 30 s | easy |
 * | 1 | much more | many more | much longer | very easy |
 *
 * ## Two corrections this file makes to the issue, both forward
 *
 * THE FEEL ROW IS REORDERED. #244's body puts "easy, had plenty left" on rung
 * 6 and "comfortable, some left" on rung 4, which INVERTS the ordering the
 * same paragraph demands -- *"the rungs still sort (1 easier than 4 easier
 * than 6)"* -- because plenty left is easier than some left. The owner's three
 * phrases are kept and their rungs swapped so the row sorts. The words are
 * his; the order is the issue's own prose.
 *
 * THE REPS ROW OVERLAPS THE COUNTED END, deliberately, and it is the one place
 * this change contradicts [EffortScale]'s standing rule *"the gap between
 * ONE_INCREMENT and three reps left is a decision. Do not close it."* That
 * rule's justification is that a headroom question sitting where the rep count
 * is most reliable asks for a guess. On a reps-progression exercise the
 * headroom question IS a rep count, so it is the reliable instrument rather
 * than the guess, and the rule does not transfer. What survives is a real
 * ambiguity at the boundary: rpe 7 says "3 reps left" and rpe 6 says "About
 * 3-4 reps left", so a lifter with exactly three left has two tiles to choose
 * from. That is carried as a field item, not as a solved problem.
 */
class HeadroomScaleDifferentialTest {
    private fun caption(tier: HeadroomTier, ask: EffortAsk, unit: WeightUnit = WeightUnit.LB) =
        EffortScale.headroomCaption(tier, ask, unit)

    private fun headroom(timed: Boolean, progression: ProgressionKind?, unit: WeightUnit = WeightUnit.LB) =
        EffortScale.tiles(timed, explosive = false, unit = unit, ask = EffortScale.askFor(timed, progression))
            .filter { it.claim == EffortClaim.HEADROOM }
            .map { it.label }

    // ---- 1. the decision: four progressions x dynamic/timed, plus omitted ----

    /**
     * The whole table of the pure decision, in one assertion per cell.
     *
     * WEIGHT falls back to the set's KIND and the other three do not, and that
     * asymmetry is the design. `weight` is what an OMITTED key resolves to, so
     * a plan written before schema 1.11 declares it by saying nothing -- and a
     * hold from such a plan has always been asked in seconds. Letting the
     * declaration win there would move every legacy plank onto load headroom,
     * which is the defect this issue exists to remove, in the other direction.
     */
    @Test
    fun `the ask is the exercise's declaration, except that weight defers to the set kind`() {
        val expected =
            mapOf(
                (ProgressionKind.WEIGHT to false) to EffortAsk.LOAD,
                (ProgressionKind.WEIGHT to true) to EffortAsk.TIME,
                (ProgressionKind.REPS to false) to EffortAsk.REPS,
                (ProgressionKind.REPS to true) to EffortAsk.REPS,
                (ProgressionKind.TIME to false) to EffortAsk.TIME,
                (ProgressionKind.TIME to true) to EffortAsk.TIME,
                (ProgressionKind.NONE to false) to EffortAsk.FEEL,
                (ProgressionKind.NONE to true) to EffortAsk.FEEL,
            )
        expected.forEach { (key, ask) ->
            val (progression, timed) = key
            assertEquals(ask, EffortScale.askFor(timed, progression), "$progression, timed=$timed")
        }
    }

    /**
     * The omitted key, which is the case every plan written before schema 1.11
     * is in, and the ad-hoc set, which has no plan at all. Both are weight.
     */
    @Test
    fun `an omitted declaration asks about load on a rep set and time on a hold`() {
        assertEquals(EffortAsk.LOAD, EffortScale.askFor(timed = false, progression = null))
        assertEquals(EffortAsk.TIME, EffortScale.askFor(timed = true, progression = null))
    }

    /** No ask is ever a load question about a hold, whatever is declared. */
    @Test
    fun `no timed set is ever asked about load`() {
        (ProgressionKind.entries + listOf(null)).forEach {
            assertNotEquals(EffortAsk.LOAD, EffortScale.askFor(timed = true, progression = it), "declared $it")
        }
    }

    // ---- 2. the captions ----

    /**
     * The reps row. The counted end already covers one, two and three left, so
     * the headroom rungs start ABOVE it and the add-a-rep point is rung 4 --
     * the owner's *"One notch down is probably when you'd add a rep or two
     * though."*
     */
    @Test
    fun `a reps-progression exercise is asked in reps, starting above the counted end`() {
        assertEquals("About 3-4 reps left", caption(HeadroomTier.ONE_INCREMENT, EffortAsk.REPS))
        assertEquals("Five or more reps left", caption(HeadroomTier.TWO_INCREMENTS, EffortAsk.REPS))
        assertEquals("Many more reps left", caption(HeadroomTier.MUCH_MORE, EffortAsk.REPS))
    }

    /**
     * The time row, at the owner's 15 s and 30 s anchors. It REPLACES
     * "15-30 s longer" and "about a minute longer", which
     * `HeadroomScaleBaselineTest` transcribed at c0.
     */
    @Test
    fun `a hold is asked at fifteen and thirty seconds, not at a minute`() {
        assertEquals("Could have gone about 15 s longer", caption(HeadroomTier.ONE_INCREMENT, EffortAsk.TIME))
        assertEquals("Could have gone about 30 s longer", caption(HeadroomTier.TWO_INCREMENTS, EffortAsk.TIME))
        assertEquals("Could have gone much longer", caption(HeadroomTier.MUCH_MORE, EffortAsk.TIME))
    }

    /**
     * The feel row: three phrases, no quantity anywhere, and they SORT.
     *
     * The sort is asserted as the absence of every digit rather than as an
     * ordering, because an ordering over three authored strings is not
     * checkable by a machine; what is checkable is that nothing here promises
     * an amount the app will then refuse to offer, which is the defect on a
     * `"none"` exercise.
     */
    @Test
    fun `a none-progression exercise is asked for a feeling and promised no quantity`() {
        assertEquals("Comfortable — some left", caption(HeadroomTier.ONE_INCREMENT, EffortAsk.FEEL))
        assertEquals("Easy — plenty left", caption(HeadroomTier.TWO_INCREMENTS, EffortAsk.FEEL))
        assertEquals("Very easy", caption(HeadroomTier.MUCH_MORE, EffortAsk.FEEL))
        HeadroomTier.entries.forEach {
            val text = caption(it, EffortAsk.FEEL)
            assertTrue(text.none { ch -> ch.isDigit() }, "a feeling rung names a quantity: $text")
            assertTrue(" lb" !in text && " kg" !in text && " s " !in text, "a feeling rung names a unit: $text")
        }
    }

    /** The load row is untouched, and that is the point of asserting it here. */
    @Test
    fun `the load row is exactly what it was`() {
        assertEquals("Could have added 10-15 lb", caption(HeadroomTier.ONE_INCREMENT, EffortAsk.LOAD))
        assertEquals("Could have added 20-30 lb", caption(HeadroomTier.TWO_INCREMENTS, EffortAsk.LOAD))
        assertEquals("Could have added much more", caption(HeadroomTier.MUCH_MORE, EffortAsk.LOAD))
        assertEquals("Could have added 5 kg", caption(HeadroomTier.ONE_INCREMENT, EffortAsk.LOAD, WeightUnit.KG))
        assertEquals("Could have added 10 kg", caption(HeadroomTier.TWO_INCREMENTS, EffortAsk.LOAD, WeightUnit.KG))
    }

    // ---- 3. the tiles the screen actually draws ----

    /** The defect, end to end: a pull-up block is no longer asked about plates. */
    @Test
    fun `a reps-progression rep set draws rep headroom and never a weight`() {
        val labels = headroom(timed = false, progression = ProgressionKind.REPS)
        assertEquals(listOf("Many more reps left", "Five or more reps left", "About 3-4 reps left"), labels)
        labels.forEach { assertTrue(" lb" !in it && " kg" !in it, "a pull-up is asked about load: $it") }
    }

    /** And a `"none"` exercise promises nothing the grid will refuse to offer. */
    @Test
    fun `a none-progression set draws feelings and no increment`() {
        assertEquals(
            listOf("Very easy", "Easy — plenty left", "Comfortable — some left"),
            headroom(timed = false, progression = ProgressionKind.NONE),
        )
    }

    /**
     * The counted end and the failure tile do NOT move with the declaration.
     *
     * The near neighbour of this change: those seven rungs are the set KIND's
     * business, they are where the lifter's own report is accurate, and
     * redefining an anchor shifts the ratings people give. A `reps` pull-up
     * still says "3 reps left" at 7 and "Failed the set" at the bottom.
     */
    @Test
    fun `the counted end and the failure tile are untouched by the declaration`() {
        val weight = EffortScale.tiles(false, false, WeightUnit.LB, EffortAsk.LOAD)
        (ProgressionKind.entries + listOf(null)).forEach { declared ->
            val tiles =
                EffortScale.tiles(false, false, WeightUnit.LB, EffortScale.askFor(false, declared))
            assertEquals(
                weight.filter { it.claim != EffortClaim.HEADROOM },
                tiles.filter { it.claim != EffortClaim.HEADROOM },
                "declaring $declared moved a rung that is not headroom",
            )
            assertEquals(
                listOf(1, 4, 6, 7, 8, 9, 10),
                tiles.mapNotNull { it.rpe },
                "declaring $declared moved an anchor, which reinterprets every set stored at one",
            )
        }
    }

    // ---- 4. the suggested step ----

    private fun offered(progression: ProgressionKind, unit: WeightUnit = WeightUnit.LB) = NextSetNudgePolicy.options(
        tier = HeadroomTier.ONE_INCREMENT,
        failed = false,
        warmup = false,
        setsLeftInExercise = 3,
        progression = progression,
        unit = unit,
    )

    private fun suggested(tier: HeadroomTier, progression: ProgressionKind, unit: WeightUnit = WeightUnit.LB) =
        NextSetNudgePolicy.suggestedStep(tier, offered(progression, unit))?.label

    /**
     * Rung 6 the smallest step, rung 4 the middle, rung 1 the largest -- the
     * owner's third comment, which withdrew the narrowed offer his first two
     * asked for: *"Give the option to add more at each of the headroom
     * intervals."*
     *
     * The MIDDLE of an even-length row is the UPPER of the two centre entries,
     * `size / 2`, and that is a choice with a reason. It makes rung 6 to rung 4
     * a real move on every row -- +1 to +2 reps, +5 to +10 s, +5 to +20 lb --
     * which is the direction the owner named when he said the add point is
     * rung 4 and not rung 6. On the pound row it also lands the suggestion on
     * 20 lb, exactly the figure rung 4's own caption says was left.
     */
    @Test
    fun `the rung suggests the smallest, the middle and the largest step of the offered row`() {
        assertEquals("+5 lb", suggested(HeadroomTier.ONE_INCREMENT, ProgressionKind.WEIGHT))
        assertEquals("+20 lb", suggested(HeadroomTier.TWO_INCREMENTS, ProgressionKind.WEIGHT))
        assertEquals("+30 lb", suggested(HeadroomTier.MUCH_MORE, ProgressionKind.WEIGHT))

        assertEquals("+2.5 kg", suggested(HeadroomTier.ONE_INCREMENT, ProgressionKind.WEIGHT, WeightUnit.KG))
        assertEquals("+10 kg", suggested(HeadroomTier.TWO_INCREMENTS, ProgressionKind.WEIGHT, WeightUnit.KG))
        assertEquals("+15 kg", suggested(HeadroomTier.MUCH_MORE, ProgressionKind.WEIGHT, WeightUnit.KG))

        assertEquals("+5 s", suggested(HeadroomTier.ONE_INCREMENT, ProgressionKind.TIME))
        assertEquals("+10 s", suggested(HeadroomTier.TWO_INCREMENTS, ProgressionKind.TIME))
        assertEquals("+15 s", suggested(HeadroomTier.MUCH_MORE, ProgressionKind.TIME))
    }

    /**
     * The rep row is two long and there are three rungs, so two rungs must
     * share a suggestion. Written down rather than left as an accident: the
     * rung still distinguishes 6 from 4, which is the boundary the owner named,
     * and rung 1's difference from rung 4 is CUSTOM beside the tile rather than
     * a third step nobody offered.
     */
    @Test
    fun `the two-step rep row shares its largest suggestion between the bottom two rungs`() {
        assertEquals("+1 rep", suggested(HeadroomTier.ONE_INCREMENT, ProgressionKind.REPS))
        assertEquals("+2 reps", suggested(HeadroomTier.TWO_INCREMENTS, ProgressionKind.REPS))
        assertEquals("+2 reps", suggested(HeadroomTier.MUCH_MORE, ProgressionKind.REPS))
    }

    /**
     * Suggesting never narrows: the offered row is the full #214 row at every
     * rung, and the suggestion is a MEMBER of it.
     */
    @Test
    fun `the suggestion is always one of the tiles actually offered`() {
        for (progression in listOf(ProgressionKind.WEIGHT, ProgressionKind.REPS, ProgressionKind.TIME)) {
            for (unit in WeightUnit.entries) {
                val row = offered(progression, unit)
                HeadroomTier.entries.forEach { tier ->
                    val pick = NextSetNudgePolicy.suggestedStep(tier, row)
                    assertTrue(pick in row, "$progression/$unit/$tier suggests a tile that is not offered: $pick")
                }
            }
        }
    }

    /** Nothing is suggested where nothing is offered, and on an unrated set. */
    @Test
    fun `no suggestion where the grid does not draw`() {
        assertNull(NextSetNudgePolicy.suggestedStep(HeadroomTier.MUCH_MORE, offered(ProgressionKind.NONE)))
        assertNull(NextSetNudgePolicy.suggestedStep(null, offered(ProgressionKind.WEIGHT)))
        assertNull(NextSetNudgePolicy.suggestedStep(HeadroomTier.MUCH_MORE, emptyList()))
    }

    // The rule that a scale word is published only BESIDE a rating is asserted
    // where its consequence is, in `:core:data`'s RpeScalePublishedTest: it
    // takes a new `:core:model` symbol that does not exist at the commit
    // before this one, and a test referencing it would fail to COMPILE rather
    // than to assert -- which reds ktlint and the build before a single test
    // result exists, destroying every other differential in this file as
    // evidence.
}
