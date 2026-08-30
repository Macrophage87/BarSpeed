package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a set the lifter appends goes, and what the control that appends it
 * says.
 *
 * Every placement pin below moved here verbatim in meaning from `:app`'s
 * `PlanQueueTest`, where the rule lived as `addedSetIndex` (#177). It moved
 * because #188 needs the rule to answer a question about a SECOND index -- the
 * set just finished, not only the set coming up -- and a second copy of "what
 * is a block" written beside the button would be a second rule. `:app` keeps
 * its `timedVerdicts` pins and loses these.
 *
 * The moved cases are all stated with `queueIndex == upcomingIndex`, which is
 * the READY case and the one where the two candidate anchors coincide. They
 * are characterization: they hold before #188's fix and after it.
 */
class AddSetControlTest {
    /** Three sets of one exercise, then two of another. */
    private fun twoBlocks() = listOf(
        AddSetSlotKey("back_squat", 0),
        AddSetSlotKey("back_squat", 1),
        AddSetSlotKey("back_squat", 2),
        AddSetSlotKey("seated_row", 0),
        AddSetSlotKey("seated_row", 1),
    )

    private fun at(blocks: List<AddSetSlotKey>, index: Int) =
        AddSetControl.placement(blocks, queueIndex = index, upcomingIndex = index)

    /**
     * On the LAST set of a block, "after the exercise's remaining sets" and
     * "immediately next" are the same index, and both rules must say so.
     */
    @Test
    fun `a set appended on the last set of a block goes immediately after it`() {
        assertEquals(3, assertNotNull(at(twoBlocks(), 2)).insertAt)
    }

    /** The same, on the last slot of the whole queue: the answer is the end. */
    @Test
    fun `a set appended on the final slot of the queue goes at the end`() {
        assertEquals(5, assertNotNull(at(twoBlocks(), 4)).insertAt)
    }

    /**
     * No slot, no placement: an index outside the queue is not a set anything
     * can be appended to.
     *
     * `PlanQueueTest` pinned this as "appends at the end", because
     * `addedSetIndex` returned an insertion index for every input and the
     * caller was what refused. The refusal is stated here now, where a caller
     * that forgot it cannot exist.
     */
    @Test
    fun `an index outside the queue has no placement`() {
        assertNull(at(twoBlocks(), 5))
        assertNull(at(emptyList(), 0))
    }

    /**
     * Appending on set ONE of a three-set block puts the new set after set
     * three, not after set one (#177 item 3).
     *
     * The lifter is adding to the block: they found the working weight on the
     * opener and want one more set of the same exercise, and the two sets the
     * plan already asked for are still wanted. Jumping the queue would run the
     * appended set second and push the prescribed remainder behind it, which
     * is a different session from the one anyone asked for.
     */
    @Test
    fun `a set appended mid-block goes after the block's remaining sets`() {
        assertEquals(3, assertNotNull(at(twoBlocks(), 0)).insertAt)
        assertEquals(3, assertNotNull(at(twoBlocks(), 1)).insertAt)
    }

    /**
     * Adding two sets is adding one twice: the second lands after the first
     * (#177 item 4).
     *
     * The queue here is what the first append leaves behind -- the appended
     * slot carries the next index in the block, so it reads as a continuation
     * of it rather than as the start of a new one.
     */
    @Test
    fun `appending twice puts the second set after the first`() {
        val afterOneAppend = twoBlocks().toMutableList().apply { add(3, AddSetSlotKey("back_squat", 3)) }
        assertEquals(4, assertNotNull(at(afterOneAppend, 0)).insertAt)
        val afterTwoAppends = afterOneAppend.toMutableList().apply { add(4, AddSetSlotKey("back_squat", 4)) }
        assertEquals(5, assertNotNull(at(afterTwoAppends, 0)).insertAt)
    }

    /**
     * A session running one movement in two consecutive blocks appends to the
     * block the lifter is IN, not to the far end of both.
     */
    @Test
    fun `two consecutive blocks of one exercise are two blocks`() {
        val twice =
            listOf(
                AddSetSlotKey("back_squat", 0),
                AddSetSlotKey("back_squat", 1),
                AddSetSlotKey("back_squat", 0),
                AddSetSlotKey("back_squat", 1),
            )
        assertEquals(2, assertNotNull(at(twice, 0)).insertAt)
        assertEquals(4, assertNotNull(at(twice, 2)).insertAt)
    }

    /**
     * FOUND BY MUTATION TESTING at `2f16199d07e8f4093b07a2e82693bafd065463fa`,
     * and the only pin that covers the exercise-id half of the rule.
     *
     * Deleting the `exerciseId` test from the walk left the whole suite green
     * there, because every other fixture has each block starting at
     * `setIndexInExercise == 0`, so the index test alone stops the walk.
     *
     * A REAL QUEUE CAN BREAK THAT. `jumpToExercise` pulls the chosen
     * exercise's remaining sets forward and copies only `isExerciseChange`, so
     * the slots keep the indices the plan gave them: a lifter who switches to
     * the row after doing its first set leaves a row block whose first slot is
     * index 1. Appending to the squat block that now precedes it must stop at
     * that boundary. Without the id test it walks straight through, and the
     * added squat set is queued after the ROW block instead.
     */
    @Test
    fun `a block whose first slot survived a switch still ends the walk`() {
        val afterSwitch =
            listOf(
                AddSetSlotKey("back_squat", 0),
                AddSetSlotKey("back_squat", 1),
                AddSetSlotKey("seated_row", 1),
                AddSetSlotKey("seated_row", 2),
            )
        assertEquals(2, assertNotNull(at(afterSwitch, 0)).insertAt)
        assertEquals(4, assertNotNull(at(afterSwitch, 2)).insertAt)
    }

    /**
     * Mid-block during rest, the load and reps the lifter is standing on are
     * statements about the anchor's own exercise, so the appended set may
     * start from them.
     */
    @Test
    fun `standing statements carry within a block`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 0, upcomingIndex = 1))
        assertTrue(p.carriesStandingStatements)
    }

    /**
     * On READY the anchor and the coming set are the same slot, so whatever
     * has been typed is a statement about it. This is #177's own behaviour and
     * the reason the control was offered on READY at all.
     */
    @Test
    fun `standing statements carry on the screen before set one`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 0, upcomingIndex = 0))
        assertTrue(p.carriesStandingStatements)
    }

    /**
     * Across a block boundary they do not. A load typed for the exercise the
     * lifter is about to do is not a statement about the one they just
     * finished -- `jumpedState`'s rule, one control over.
     */
    @Test
    fun `standing statements do not carry across a block boundary`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 2, upcomingIndex = 3))
        assertFalse(p.carriesStandingStatements)
    }

    /** Mid-block the two names agree, so there is no doubt for the label to remove. */
    @Test
    fun `the label names the exercise when both slots are the same one`() {
        assertEquals(
            "Load was wrong? Add another Back squat set",
            AddSetControl.label("Back squat", "Back squat"),
        )
    }

    /** After the session's last set there is no upcoming exercise to name. */
    @Test
    fun `the label names the anchor when no set is coming up`() {
        assertEquals(
            "Load was wrong? Add another Back squat set",
            AddSetControl.label("Back squat", null),
        )
    }
}
