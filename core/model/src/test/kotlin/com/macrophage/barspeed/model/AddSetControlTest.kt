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

    /**
     * A squat block the lifter has already appended two sets to, then a row
     * block.
     *
     * An appended slot carries the next index in the block, which is what
     * makes the scan run THROUGH it -- indices 3 and 4 here are appended, and
     * the block still ends where the row's index 0 starts. Added by #206 as
     * characterization: removal has to find the same block, and the scan that
     * finds it is about to be extracted out of `placement`.
     */
    private fun blockWithTwoAppended() = listOf(
        AddSetSlotKey("back_squat", 0),
        AddSetSlotKey("back_squat", 1),
        AddSetSlotKey("back_squat", 2),
        AddSetSlotKey("back_squat", 3),
        AddSetSlotKey("seated_row", 0),
    )

    private fun at(blocks: List<AddSetSlotKey>, index: Int) =
        AddSetControl.placement(blocks, queueIndex = index, upcomingIndex = index)

    /**
     * Characterization (#206), green before and after the extraction: from
     * ANY set of a block carrying appended sets, the block ends at the same
     * index, and that index is where the next append goes.
     *
     * Stated over every anchor in the block rather than one, because the
     * block's extent is the thing being pinned and an anchor-by-anchor answer
     * is the only way to see that the scan does not stop at the first
     * appended slot it meets.
     */
    @Test
    fun `the block ends past its appended sets from every anchor in it`() {
        val blocks = blockWithTwoAppended()
        assertEquals(listOf(4, 4, 4, 4), (0..3).map { assertNotNull(at(blocks, it)).insertAt })
    }

    /**
     * Characterization (#206): the row block after it is its own block, and
     * an append anchored there lands at the end of the queue rather than
     * anywhere inside the squats.
     */
    @Test
    fun `the block after an appended-to block starts where its own index zero is`() {
        assertEquals(5, assertNotNull(at(blockWithTwoAppended(), 4)).insertAt)
    }

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

    // ---- #188: which set the control refers to -----------------------------

    /**
     * RED before the fix. During rest the anchor is the set just FINISHED.
     *
     * The control exists because a set showed the load was wrong, which is a
     * statement about a set that has happened. `RecordState.upcomingIndex` is
     * the set the next START will run, and taking the anchor from it makes the
     * control refer to a set the lifter has not done yet.
     *
     * Mid-block both indices sit in the same block, so the SET the control
     * refers to is wrong here while the insertion point happens to agree.
     */
    @Test
    fun `mid-block during rest the anchor is the set just finished`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 0, upcomingIndex = 1))
        assertEquals(0, p.anchorIndex)
        assertEquals(3, p.insertAt)
    }

    /**
     * RED before the fix, and the defect the owner reported. At a block
     * boundary the two indices name different EXERCISES.
     *
     * Finish the last squat set with the row coming up and the anchor must
     * still be the squat: the appended set is a squat set, and it goes
     * immediately after the block it belongs to, which is before the row.
     * Anchored on the upcoming slot instead, the rule walks the ROW block and
     * answers 5 -- the added set is a row set, queued after the row.
     */
    @Test
    fun `at a block boundary the anchor is the finished block, not the next one`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 2, upcomingIndex = 3))
        assertEquals(2, p.anchorIndex)
        assertEquals(3, p.insertAt)
    }

    /**
     * RED before the fix. The block the appended set joins has no sets left,
     * so "after the block's remaining sets" IS "next" -- and the caller has to
     * know, because the boxes on the rest screen were seeded for the exercise
     * that is no longer coming up.
     */
    @Test
    fun `a set added at a block boundary is the next set run`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 2, upcomingIndex = 3))
        assertTrue(p.becomesNextSet)
    }

    /**
     * RED before the fix. After the session's final set there is a set to add
     * one of, and it is the one just finished (#188 item 3).
     *
     * `upcomingIndex` is one past the end here, which is why the rule used to
     * refuse: the anchor it was reading did not exist. The anchor that matters
     * does.
     */
    @Test
    fun `a set can be added after the session's final set`() {
        val p = assertNotNull(AddSetControl.placement(twoBlocks(), queueIndex = 4, upcomingIndex = 5))
        assertEquals(4, p.anchorIndex)
        assertEquals(5, p.insertAt)
        assertTrue(p.becomesNextSet)
        assertFalse(p.carriesStandingStatements)
    }

    /**
     * RED before the fix. Where the two slots are different exercises the
     * label names the one being added AND where it lands, because that is
     * precisely the moment the lifter cannot tell them apart.
     */
    @Test
    fun `the label names the finished exercise at a block boundary`() {
        assertEquals(
            "Load was wrong? Add another Overhead press set before Lat pulldown",
            AddSetControl.label("Overhead press", "Lat pulldown"),
        )
    }
}
