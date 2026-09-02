package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which appended set the removal control takes back, and what it says (#206).
 *
 * RED AT THE SHA THAT ADDS THIS FILE. `RemoveSetControl.target` and
 * `RemoveSetControl.label` are `TODO` in c1, so every test here fails with
 * `NotImplementedError` -- the rule is absent rather than wrong, which is the
 * distinction c1's bodies were chosen to keep readable.
 *
 * The eligibility rule under test has two halves and both are stated here.
 * `isAddedSet` decides WHETHER a slot may go at all, and `>= upcomingIndex`
 * decides whether it still can: a set that has RUN is a row of training
 * history with samples, a raw stream and an export entry, and no control on
 * the rest screen deletes one.
 */
class RemoveSetControlTest {
    private fun key(id: String, index: Int, added: Boolean = false) = AddSetSlotKey(id, index, added)

    /** Three prescribed squat sets, then two prescribed rows. Nothing appended. */
    private fun prescribedOnly() = listOf(
        key("back_squat", 0),
        key("back_squat", 1),
        key("back_squat", 2),
        key("seated_row", 0),
        key("seated_row", 1),
    )

    /** The same squat block with one set appended to the end of it. */
    private fun oneAppendedSquat() = listOf(
        key("back_squat", 0),
        key("back_squat", 1),
        key("back_squat", 2),
        key("back_squat", 3, added = true),
        key("seated_row", 0),
        key("seated_row", 1),
    )

    /** Two prescribed squat sets and two appended ones, then the rows. */
    private fun twoAppendedSquats() = listOf(
        key("back_squat", 0),
        key("back_squat", 1),
        key("back_squat", 2, added = true),
        key("back_squat", 3, added = true),
        key("seated_row", 0),
    )

    /**
     * Nothing appended, nothing to remove. The control is not offered rather
     * than offered and refused.
     */
    @Test
    fun `a block with no appended set has nothing to remove`() {
        assertNull(RemoveSetControl.target(prescribedOnly(), queueIndex = 1, upcomingIndex = 2))
        assertNull(RemoveSetControl.target(prescribedOnly(), queueIndex = 0, upcomingIndex = 0))
    }

    /**
     * The plain case: mid-rest in a block carrying one appended set, that set
     * is the one taken, and it is the set the next START would have run.
     */
    @Test
    fun `the appended set of the anchor's block is the one removed`() {
        val t = assertNotNull(RemoveSetControl.target(oneAppendedSquat(), queueIndex = 2, upcomingIndex = 3))
        assertEquals(3, t.removeAt)
        assertTrue(t.wasUpcoming)
        assertEquals(1, t.removableCount)
    }

    /**
     * #206 requirement 5. With several appended sets the LAST one goes, and
     * the control is told there is more than one so it can say so.
     *
     * Anchored mid-block here, so the removed set is not the coming one: the
     * two prescribed sets between are untouched and still run.
     */
    @Test
    fun `with several appended sets the last one is taken`() {
        val t = assertNotNull(RemoveSetControl.target(twoAppendedSquats(), queueIndex = 1, upcomingIndex = 2))
        assertEquals(3, t.removeAt)
        assertFalse(t.wasUpcoming)
        assertEquals(2, t.removableCount)
    }

    /**
     * #206 requirement 2, the removal half. Taking one back leaves the other
     * exactly where it was, and a second tap finds it.
     *
     * Stated as the queue a first removal leaves behind, which is what the
     * caller hands back.
     */
    @Test
    fun `removing is repeatable and the next tap takes the one before it`() {
        val afterOne = twoAppendedSquats().toMutableList().apply { removeAt(3) }
        val t = assertNotNull(RemoveSetControl.target(afterOne, queueIndex = 1, upcomingIndex = 2))
        assertEquals(2, t.removeAt)
        assertEquals(1, t.removableCount)
        val afterTwo = afterOne.toMutableList().apply { removeAt(2) }
        assertNull(RemoveSetControl.target(afterTwo, queueIndex = 1, upcomingIndex = 2))
    }

    /**
     * #206 requirement 1. A set the PLAN prescribed is never removable. The
     * squat block here has two of its three sets still to run and the control
     * still answers nothing, because none of them is a set the lifter added.
     */
    @Test
    fun `a prescribed set is never removable`() {
        assertNull(RemoveSetControl.target(prescribedOnly(), queueIndex = 0, upcomingIndex = 1))
        assertNull(RemoveSetControl.target(oneAppendedSquat(), queueIndex = 4, upcomingIndex = 5))
    }

    /**
     * THE BOUNDARY. An appended set that has already RUN is not removable:
     * that set is a row of training history with its samples, its raw stream
     * and its export entry, and this control does not delete one.
     *
     * Mid-rest after the appended set itself -- `queueIndex` is the slot just
     * finished and `upcomingIndex` is one past it, so the appended slot is
     * below the line and the block has nothing eligible left.
     */
    @Test
    fun `an appended set that has already run cannot be removed`() {
        assertNull(RemoveSetControl.target(oneAppendedSquat(), queueIndex = 3, upcomingIndex = 4))
    }

    /**
     * The same boundary with a second appended set still queued: the one that
     * ran is out of reach and the one that has not is taken. A single
     * "is there an appended set in this block" test would pass wrongly here.
     */
    @Test
    fun `a run appended set is skipped and a queued one is still taken`() {
        val t = assertNotNull(RemoveSetControl.target(twoAppendedSquats(), queueIndex = 2, upcomingIndex = 3))
        assertEquals(3, t.removeAt)
        assertEquals(1, t.removableCount)
    }

    /**
     * On READY the anchor and the coming set are the same slot, so an appended
     * set the lifter is standing in front of has still not run and can still
     * be taken back. This is the "added one and changed my mind" case #206
     * asks about.
     */
    @Test
    fun `on READY the appended set being set up can still be taken back`() {
        val t = assertNotNull(RemoveSetControl.target(oneAppendedSquat(), queueIndex = 3, upcomingIndex = 3))
        assertEquals(3, t.removeAt)
        assertTrue(t.wasUpcoming)
    }

    /**
     * #206 requirement 3, the half that makes the pair one decision. The
     * anchor is `queueIndex`, as the add's is: at a block boundary the coming
     * set is a different exercise, and taking a row set back because the lifter
     * finished the squats is the same defect #188 fixed one control over.
     */
    @Test
    fun `at a block boundary removal follows the finished block, not the coming one`() {
        val squatsThenAppendedRow =
            listOf(
                key("back_squat", 0),
                key("back_squat", 1),
                key("seated_row", 0),
                key("seated_row", 1, added = true),
            )
        assertNull(RemoveSetControl.target(squatsThenAppendedRow, queueIndex = 1, upcomingIndex = 2))
        val t = assertNotNull(RemoveSetControl.target(squatsThenAppendedRow, queueIndex = 2, upcomingIndex = 3))
        assertEquals(3, t.removeAt)
    }

    /**
     * A session running one movement in two consecutive blocks removes from
     * the block the lifter is IN. Both blocks here carry an appended set and
     * the answer differs by which one the anchor sits in.
     */
    @Test
    fun `two consecutive blocks of one exercise are two blocks here too`() {
        val twice =
            listOf(
                key("back_squat", 0),
                key("back_squat", 1, added = true),
                key("back_squat", 0),
                key("back_squat", 1, added = true),
            )
        assertEquals(1, assertNotNull(RemoveSetControl.target(twice, queueIndex = 0, upcomingIndex = 1)).removeAt)
        assertEquals(3, assertNotNull(RemoveSetControl.target(twice, queueIndex = 2, upcomingIndex = 3)).removeAt)
    }

    /** No slot, no removal: the refusal `AddSetControl.placement` makes. */
    @Test
    fun `an index outside the queue has nothing to remove`() {
        assertNull(RemoveSetControl.target(oneAppendedSquat(), queueIndex = 6, upcomingIndex = 6))
        assertNull(RemoveSetControl.target(emptyList(), queueIndex = 0, upcomingIndex = 0))
    }

    /**
     * One appended set: the label names it and the exercise, and says nothing
     * about ordering, because there is no ordering the lifter can see.
     */
    @Test
    fun `the label names the set it will take`() {
        assertEquals(
            "Added by mistake? Remove Set 4 of Back squat",
            RemoveSetControl.label("Back squat", setNumber = 4, several = false),
        )
    }

    /**
     * Several: the label says WHICH one, because "remove the set you added" is
     * ambiguous the moment there are two and the lifter cannot be left
     * guessing which the tap takes.
     */
    @Test
    fun `the label says it takes the last one when there are several`() {
        assertEquals(
            "Added by mistake? Remove the last added set, Set 5 of Back squat",
            RemoveSetControl.label("Back squat", setNumber = 5, several = true),
        )
    }
}
