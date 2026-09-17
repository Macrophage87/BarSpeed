package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which upcoming set the skip control drops, and what it says about it (#300).
 *
 * RED AT THE SHA THAT ADDS THIS FILE for every test naming
 * `SkipSetControl.target`: that body is `TODO` in the commit before this one,
 * so each of them fails with `NotImplementedError` -- the rule is ABSENT
 * rather than wrong, which is what makes the failure readable.
 *
 * THE PINS THAT DO NOT FAIL HERE are the label, the confirmation and the
 * record, and they are named as such at the tests themselves. Those members
 * are implemented already; a test that passes inside a red commit passes for a
 * reason worth writing down, and #159's export differentials are where this
 * file takes the practice from.
 *
 * THE CROSS PRODUCT the issue asks for is prescribed against appended, the
 * upcoming slot against a later one and against one that has already run, and
 * a block boundary. It closes on two refusals rather than on a menu of
 * outcomes: an appended upcoming slot belongs to [RemoveSetControl], and a
 * block OPENER is the exercise rather than a set of it.
 */
class SkipSetControlTest {
    private fun key(id: String, index: Int, added: Boolean = false) = AddSetSlotKey(id, index, added)

    /** Four prescribed squat sets, then two prescribed rows. Nothing appended. */
    private fun fourSquatsThenRows() = listOf(
        key("back_squat", 0),
        key("back_squat", 1),
        key("back_squat", 2),
        key("back_squat", 3),
        key("seated_row", 0),
        key("seated_row", 1),
    )

    /** Three prescribed squat sets with one appended on the end of the block. */
    private fun squatsWithOneAppended() = listOf(
        key("back_squat", 0),
        key("back_squat", 1),
        key("back_squat", 2),
        key("back_squat", 3, added = true),
        key("seated_row", 0),
    )

    /**
     * The four-squat block with set 2 already skipped, so the numbers the plan
     * gave the remaining sets stand and the queue positions no longer match
     * them.
     */
    private fun squatsWithSetTwoGone() = listOf(
        key("back_squat", 0),
        key("back_squat", 2),
        key("back_squat", 3),
        key("seated_row", 0),
    )

    // ---- the set that goes --------------------------------------------------

    /**
     * The plain case, and the one the owner described: mid-rest inside a block,
     * the set the next START would run is the set that goes. DIFFERENTIAL.
     */
    @Test
    fun `the upcoming set of the block is the one skipped`() {
        val t = assertNotNull(SkipSetControl.target(fourSquatsThenRows(), upcomingIndex = 2))
        assertEquals(2, t.skipAt)
        assertEquals(3, t.setNumber)
        assertFalse(t.lastOfBlock, "a fourth squat set still follows this one")
    }

    /**
     * The block's final prescribed set says so, because skipping it ends the
     * exercise rather than shortening it. DIFFERENTIAL.
     */
    @Test
    fun `the last prescribed set of a block is named as the last one left`() {
        val t = assertNotNull(SkipSetControl.target(fourSquatsThenRows(), upcomingIndex = 3))
        assertEquals(3, t.skipAt)
        assertEquals(4, t.setNumber)
        assertTrue(t.lastOfBlock, "nothing of this block follows set 4")
    }

    /**
     * The number recorded is the PLAN's, not the queue position. DIFFERENTIAL,
     * and the one case where the two are different numbers.
     *
     * After one skip the block's indices run 0, 2, 3 -- the remaining sets keep
     * the numbers the plan gave them, which is the whole of what makes a skip
     * readable against the plan afterwards. The slot at queue index 2 is the
     * plan's set 4, and a rule deriving the set number from `skipAt + 1` would
     * record set 3: a set that was performed.
     */
    @Test
    fun `the set number is the plan's own, not the position in the queue`() {
        val t = assertNotNull(SkipSetControl.target(squatsWithSetTwoGone(), upcomingIndex = 2))
        assertEquals(2, t.skipAt)
        assertEquals(4, t.setNumber)
        assertTrue(t.lastOfBlock)
    }

    /**
     * A set that has already RUN is not reachable from here, and it is
     * unreachable by construction rather than by a filter. DIFFERENTIAL.
     *
     * The upcoming index is the only slot this rule will name, so the two
     * squat sets already recorded cannot be named however the control is
     * tapped -- the boundary [RemoveSetControl] draws with `>= upcomingIndex`,
     * reached here by having nothing else to offer. Removing a recorded set
     * would delete a row of training history with its samples, its raw stream
     * and its export entry, and no control on the rest screen does that.
     */
    @Test
    fun `a set that has already run is never the set named`() {
        val t = assertNotNull(SkipSetControl.target(fourSquatsThenRows(), upcomingIndex = 2))
        assertTrue(t.skipAt >= 2, "the skip named a slot that has already been recorded: ${t.skipAt}")
    }

    /**
     * An appended set still queued inside the block is a set of that block, so
     * the prescribed set before it is not the last one left. DIFFERENTIAL.
     *
     * The near neighbour of the `lastOfBlock` rule: reading it off the
     * PRESCRIBED sets alone would tell the lifter that skipping set 3 ends the
     * squats while a fourth squat set they added themselves is still queued.
     */
    @Test
    fun `an appended set queued after it means the prescribed set is not the last`() {
        val t = assertNotNull(SkipSetControl.target(squatsWithOneAppended(), upcomingIndex = 2))
        assertEquals(2, t.skipAt)
        assertEquals(3, t.setNumber)
        assertFalse(t.lastOfBlock, "an appended squat set is still queued after this one")
    }

    /**
     * Whatever it names is inside the block the add extends and the removal
     * shortens. DIFFERENTIAL.
     *
     * The three controls sit together and have to be about the same exercise,
     * which is #206 requirement 3 one control further on. This is asserted
     * against [AddSetControl.blockRange] itself rather than restated, so the
     * three cannot drift apart over what a block is.
     */
    @Test
    fun `the set skipped is always inside the block the add extends`() {
        listOf(fourSquatsThenRows(), squatsWithOneAppended(), squatsWithSetTwoGone()).forEach { blocks ->
            blocks.indices.forEach { upcoming ->
                val t = SkipSetControl.target(blocks, upcoming) ?: return@forEach
                val block = assertNotNull(AddSetControl.blockRange(blocks, upcoming - 1))
                assertTrue(t.skipAt in block, "skipping ${t.skipAt} reaches outside the anchor's block $block")
            }
        }
    }

    // ---- what it refuses ----------------------------------------------------

    /**
     * At a block boundary the coming set is the next exercise's FIRST set, and
     * it is refused. DIFFERENTIAL.
     *
     * Dropping the opener of a block is dropping the exercise, not a set of it:
     * `setIndexInExercise == 0` is what [AddSetControl.blockRange] and
     * [SetLoadPolicy.sameExerciseBlock] both read to find where a block begins,
     * and what `isExerciseChange` draws the move-the-sensor card from. A lifter
     * who does not want the rows at all reroutes or finishes.
     */
    @Test
    fun `the first set of the next exercise is not skippable`() {
        assertNull(SkipSetControl.target(fourSquatsThenRows(), upcomingIndex = 4))
    }

    /** The session's very first set is a block opener too. DIFFERENTIAL. */
    @Test
    fun `the session's opening set is not skippable`() {
        assertNull(SkipSetControl.target(fourSquatsThenRows(), upcomingIndex = 0))
    }

    /**
     * An APPENDED upcoming set is refused, because the other control already
     * takes it back and takes it back better. DIFFERENTIAL.
     *
     * Offering it here would record a [SkippedSet] for a set no plan
     * prescribed -- a deviation from a prescription that never existed -- and
     * would put two controls on one screen doing the same thing to the same
     * slot.
     */
    @Test
    fun `an appended upcoming set is left to the removal control`() {
        assertNull(SkipSetControl.target(squatsWithOneAppended(), upcomingIndex = 3))
    }

    /**
     * Past the last set of the session there is nothing coming up.
     * DIFFERENTIAL.
     *
     * `upcomingIndex` is `queueIndex + 1` throughout rest, so after the final
     * set it is legitimately one past the end. That is the state the rest
     * screen's last-set branch draws, and a rule reading the slot without
     * checking would index out of the list.
     */
    @Test
    fun `there is nothing to skip past the session's last set`() {
        val blocks = fourSquatsThenRows()
        assertNull(SkipSetControl.target(blocks, upcomingIndex = blocks.size))
        assertNull(SkipSetControl.target(blocks, upcomingIndex = blocks.size + 3))
        assertNull(SkipSetControl.target(blocks, upcomingIndex = -1))
    }

    /** An empty queue offers nothing. DIFFERENTIAL. */
    @Test
    fun `an empty queue has nothing to skip`() {
        assertNull(SkipSetControl.target(emptyList(), upcomingIndex = 0))
    }

    // ---- the words, and the record ------------------------------------------

    /**
     * What the control says, in the register the other two use. NOT a
     * differential: [SkipSetControl.label] is implemented in the commit that
     * declared it, and this pins the words rather than waiting for them.
     *
     * The issue's own wording is the short form. The long form is said only
     * where it is true, which is the rule [AddSetControl.label] follows for its
     * "before" clause: with another set of the exercise still queued, "the last
     * one left" would be false.
     */
    @Test
    fun `the control asks about the decision and names the set`() {
        assertEquals(
            "Not doing it? Skip Set 4 of Back squat",
            SkipSetControl.label("Back squat", setNumber = 4, lastOfBlock = false),
        )
        assertEquals(
            "Not doing it? Skip Set 4 of Back squat, the last one left",
            SkipSetControl.label("Back squat", setNumber = 4, lastOfBlock = true),
        )
    }

    /**
     * The confirmation names the same set and says what the skip costs. NOT a
     * differential.
     *
     * The body's second sentence is the whole reason there is a confirmation
     * here and none on the removal control: ADD SET can queue another set of
     * the exercise, and it is not this set coming back -- an appended set
     * publishes no `plannedReps` and reads as a set the plan never asked for.
     */
    @Test
    fun `the confirmation names the set and says the prescription goes with it`() {
        assertEquals("Skip Set 4 of Back squat?", SkipSetControl.confirmTitle("Back squat", setNumber = 4))
        val body = SkipSetControl.confirmBody("Back squat")
        assertTrue("Nothing is recorded for it" in body, "the confirmation does not say nothing is recorded: $body")
        assertTrue("ADD SET" in body, "the confirmation does not say a set can be added back: $body")
        assertTrue("no prescription" in body, "the confirmation does not say the added set carries none: $body")
    }

    /**
     * The record carries the exercise ID and the plan's set number. NOT a
     * differential.
     *
     * The ID rather than the display name, because `ExerciseExport.exercise`
     * publishes the ID and a reader joins on it; a record carrying "Back squat"
     * would be unjoinable with the sets recorded beside it.
     */
    @Test
    fun `the record names the exercise by id and the set by the plan's number`() {
        val target = SkipSetTarget(skipAt = 2, setNumber = 4, lastOfBlock = true)

        assertEquals(SkippedSet(exercise = "back_squat", setNumber = 4), SkipSetControl.recordOf("back_squat", target))
    }
}
