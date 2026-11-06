package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the lifter said about a block survives an off-plan set run in the
 * middle of it (whole-release review, off-plan set mid-block).
 *
 * The case: block A planned at 90 kg, he corrects set 1 to 100, and during the
 * rest picks "Different exercise..." for one set of leg curls. Before this, the
 * rest after the curl asked [NextSetInputs.of] about (curl, A set 2), found no
 * block, and re-seeded A set 2 from the plan's 90, which records unless he
 * notices; removing the curl did the same.
 */
class OffPlanHoldTest {
    private fun slot(id: String, index: Int, plannedKg: Double? = 90.0, reps: Int? = 8, tempo: String? = "3010") =
        NextSetSlot(
            exerciseId = id,
            setIndexInExercise = index,
            bodyweight = false,
            warmup = false,
            plannedLoadKg = plannedKg,
            plannedReps = reps,
            plannedDurationS = null,
            plannedTempo = tempo,
            loadKg = plannedKg,
            reps = reps,
            durationS = null,
            tempo = tempo,
        )

    private val a2 = slot("leg_press", 1)
    private val curl = slot("leg_curl", 0, plannedKg = null, reps = null, tempo = null)
    private val standing = NextSetStatements(loadKg = 100.0, reps = 6, durationS = null, tempo = "4010")

    @Test
    fun `before the hold, the slot after an off-plan set re-seeds from the plan`() {
        // The defect, as NextSetInputs alone answers it.
        val lost = NextSetInputs.of(last = curl, next = a2, stated = NextSetStatements.NONE)
        assertNull(lost.statedKg)
        assertEquals(90.0, lost.loadKg)
    }

    @Test
    fun `the block's statements come back for the slot they were made for`() {
        val held = assertNotNull(OffPlanHold.hold(displaced = a2, stated = standing, side = "left"))
        val back = assertNotNull(OffPlanHold.resumed(held, next = a2))
        assertEquals(100.0, back.inputs.statedKg)
        assertEquals(100.0, back.inputs.loadKg)
        assertEquals(6, back.inputs.statedReps)
        assertEquals(6, back.inputs.reps)
        assertEquals("4010", back.inputs.statedTempo)
        assertEquals("left", back.side)
    }

    @Test
    fun `a slot with nothing stated for it is not held, and the plan stands`() {
        assertNull(OffPlanHold.hold(displaced = a2, stated = NextSetStatements.NONE, side = null))
        assertNull(OffPlanHold.hold(displaced = null, stated = standing, side = null))
    }

    @Test
    fun `a stated side alone is held`() {
        val held = assertNotNull(OffPlanHold.hold(displaced = a2, stated = NextSetStatements.NONE, side = "right"))
        val back = assertNotNull(OffPlanHold.resumed(held, next = a2))
        assertNull(back.inputs.statedKg)
        assertEquals(90.0, back.inputs.loadKg)
        assertEquals("right", back.side)
    }

    @Test
    fun `statements typed for the first set of a block come back too`() {
        val a1 = slot("leg_press", 0)
        val held = assertNotNull(OffPlanHold.hold(displaced = a1, stated = standing, side = null))
        assertEquals(100.0, assertNotNull(OffPlanHold.resumed(held, next = a1)).inputs.statedKg)
    }

    @Test
    fun `nothing comes back for a different slot`() {
        val held = assertNotNull(OffPlanHold.hold(displaced = a2, stated = standing, side = null))
        assertNull(OffPlanHold.resumed(held, next = slot("leg_press", 2)))
        assertNull(OffPlanHold.resumed(held, next = slot("bench_press", 1)))
        assertNull(OffPlanHold.resumed(held, next = null))
        assertNull(OffPlanHold.resumed(null, next = a2))
    }

    @Test
    fun `an added set of another lift than the held slot's gives the hold back as it leaves`() {
        val held = assertNotNull(OffPlanHold.hold(displaced = a2, stated = standing, side = null))
        // The off-plan curl, and ADD SET's second curl (set index 1): both added, both another lift.
        assertTrue(OffPlanHold.departs(isAddedSet = true, departingExerciseId = "leg_curl", held = held))
        // ADD SET's appended set of the held slot's own lift continues that block.
        assertFalse(OffPlanHold.departs(isAddedSet = true, departingExerciseId = "leg_press", held = held))
        assertFalse(OffPlanHold.departs(isAddedSet = false, departingExerciseId = "leg_curl", held = held))
        assertFalse(OffPlanHold.departs(isAddedSet = true, departingExerciseId = "leg_curl", held = null))
    }

    @Test
    fun `a displaced planned slot is held, and a displaced added slot keeps the hold standing`() {
        val standingHold = HeldStatements(a2, standing, side = null)
        val corrected = NextSetStatements(loadKg = 110.0, reps = null, durationS = null, tempo = null)
        val heldA = OffPlanHold.holdOnDisplace(a2, displacedIsAdded = false, corrected, null, standing = null)
        assertEquals(110.0, assertNotNull(heldA).stated.loadKg)
        assertEquals(
            standingHold,
            OffPlanHold.holdOnDisplace(curl, displacedIsAdded = true, NextSetStatements.NONE, null, standingHold),
        )
    }

    /**
     * Review of fullrev-fix-data: A0, off-plan curl, A1. The curl runs and A1
     * gets 100 back (hold spent); then "Add another" curl lands in front of A1
     * and wiped the 100, and after it (an added set at index 1, not one-set)
     * nothing carried, so A1 recorded 90.
     */
    @Test
    fun `a second set of the off-plan lift added in front of the block keeps the block's load`() {
        val held = OffPlanHold.holdOnDisplace(a2, displacedIsAdded = false, standing, null, standing = null)
        assertTrue(OffPlanHold.departs(isAddedSet = true, departingExerciseId = "leg_curl", held = held))
        val firstBack = assertNotNull(OffPlanHold.resumed(held, next = a2))
        assertEquals(100.0, firstBack.inputs.statedKg)
        // Spent; ADD SET of the curl now displaces A1 with 100 standing for it.
        val stated =
            NextSetStatements(
                firstBack.inputs.statedKg,
                firstBack.inputs.statedReps,
                null,
                firstBack.inputs.statedTempo,
            )
        val again = OffPlanHold.holdOnDisplace(a2, displacedIsAdded = false, stated, null, standing = null)
        assertTrue(OffPlanHold.departs(isAddedSet = true, departingExerciseId = "leg_curl", held = again))
        assertEquals(100.0, assertNotNull(OffPlanHold.resumed(again, next = a2)).inputs.statedKg)
    }
}
