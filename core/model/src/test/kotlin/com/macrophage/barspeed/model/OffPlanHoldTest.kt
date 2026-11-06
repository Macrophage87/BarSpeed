package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    private fun slot(
        id: String,
        index: Int,
        plannedKg: Double? = 90.0,
        reps: Int? = 8,
        tempo: String? = "3010",
    ) = NextSetSlot(
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
    fun `only a one-set added slot is an off-plan slot`() {
        assertEquals(true, OffPlanHold.isOffPlan(isAddedSet = true, setIndexInExercise = 0, setsInExercise = 1))
        // ADD SET's appended set of a block is added but continues the block.
        assertEquals(false, OffPlanHold.isOffPlan(isAddedSet = true, setIndexInExercise = 3, setsInExercise = 4))
        assertEquals(false, OffPlanHold.isOffPlan(isAddedSet = false, setIndexInExercise = 0, setsInExercise = 1))
    }
}
