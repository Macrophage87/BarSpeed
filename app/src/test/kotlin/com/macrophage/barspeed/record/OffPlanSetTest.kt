package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.RecordedLift
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The off-plan set inside a plan session (record-setup F2) and the ad-hoc
 * picker's prefill on the state (record-setup F3), on the `:app` side of
 * `AdHocPicker`: where the set lands in the queue, that it is marked as
 * added, and what the boxes are re-seeded to.
 *
 * Compile- and CI-gated only from the container this was written in, which
 * has no Android SDK; the decisions it leans on are pinned in `:core:model`'s
 * `AdHocPickerTest`, which does run there.
 */
class OffPlanSetTest {
    private val squat = ExerciseDef.resolvedById("back_squat")

    private fun planned(setIndex: Int) = PlannedSlot(
        exercise = squat,
        geometry = SetGeometryPolicy.describe(squat, null),
        setIndexInExercise = setIndex,
        setsInExercise = 3,
        reps = 5,
        loadKg = 100.0,
        plannedLoadKg = 100.0,
        tempo = null,
    )

    private val queue = listOf(planned(0), planned(1), planned(2))

    private val lastCurl =
        RecordedLift("leg_curl", 40.0, 12, null, "3010", null, warmup = false, voided = false, endedAtMs = 1)

    private fun resting(queueIndex: Int) = RecordState(
        stage = Stage.RESTING,
        queue = queue,
        queueIndex = queueIndex,
        adHoc = false,
        recentLifts = listOf(lastCurl),
    )

    @Test
    fun `an off-plan set is put next, marked as added, with every planned set still queued`() {
        val s = assertNotNull(offPlanAddedState(resting(queueIndex = 0), "leg_curl"))
        assertEquals(4, s.queue.size)
        val added = s.queue[1]
        assertEquals("leg_curl", added.exercise.id)
        assertTrue(added.isAddedSet)
        assertFalse(added.warmup)
        assertNull(added.plannedLoadKg)
        assertNull(added.plannedReps)
        assertEquals(0, added.setIndexInExercise)
        assertEquals(1, added.setsInExercise)
        assertEquals(added, s.nextSlot)
        assertEquals(listOf(queue[0]), s.queue.take(1))
        assertEquals(queue.drop(1).map { it.setIndexInExercise }, s.queue.drop(2).map { it.setIndexInExercise })
    }

    @Test
    fun `the off-plan set and the boxes take the last time's numbers`() {
        val s = assertNotNull(offPlanAddedState(resting(queueIndex = 0), "leg_curl"))
        val added = s.queue[1]
        assertEquals(40.0, added.loadKg)
        assertEquals(12, added.reps)
        assertEquals("3010", added.tempo)
        assertEquals("40", s.loadInput)
        assertEquals("12", s.repsInput)
        assertNull(s.statedLoadKg)
    }

    @Test
    fun `both sides of an off-plan set are marked as an exercise change`() {
        val s = assertNotNull(offPlanAddedState(resting(queueIndex = 0), "leg_curl"))
        assertTrue(s.queue[1].isExerciseChange)
        assertTrue(s.queue[2].isExerciseChange)
    }

    @Test
    fun `after the last planned set the off-plan set is appended and becomes next`() {
        val s = assertNotNull(offPlanAddedState(resting(queueIndex = 2), "leg_curl"))
        assertEquals(4, s.queue.size)
        assertEquals("leg_curl", s.queue[3].exercise.id)
        assertEquals(3, s.upcomingIndex)
    }

    @Test
    fun `an off-plan set on READY becomes the set being set up`() {
        val ready = RecordState(stage = Stage.READY, queue = queue, queueIndex = 0, adHoc = false)
        val s = assertNotNull(offPlanAddedState(ready, "dead_hang"))
        val added = assertNotNull(s.currentSlot)
        assertEquals("dead_hang", added.exercise.id)
        // A hold never done opens on zero added and a hold target, not 60.
        assertEquals(0.0, added.loadKg)
        assertEquals(60, added.durationS)
        assertTrue(added.isTimed)
    }

    @Test
    fun `an off-plan set of a lift the plan declares keeps the plan's prep`() {
        val plan =
            listOf(
                PlanSessionDef(
                    name = "B",
                    exercises =
                    listOf(PlanExerciseDef(exercise = "leg_curl", prepS = 12, sets = listOf(PlanSetDef(reps = 10)))),
                ),
            )
        val s = assertNotNull(offPlanAddedState(resting(queueIndex = 0).copy(planSessions = plan), "leg_curl"))
        assertEquals(12, s.queue[1].prepS)
    }

    @Test
    fun `a wrong off-plan pick can be removed, giving back the plan's queue and boxes`() {
        val before = resting(queueIndex = 0)
        val added = assertNotNull(offPlanAddedState(before, "leg_curl"))
        assertEquals(1, added.removeSetTarget?.removeAt)
        val removed = assertNotNull(removedState(added))
        assertEquals(queue, removed.queue)
        assertEquals("100", removed.loadInput)
        assertEquals("5", removed.repsInput)
    }

    @Test
    fun `an off-plan pick on READY can be removed`() {
        val ready = RecordState(stage = Stage.READY, queue = queue, queueIndex = 0, adHoc = false)
        val added = assertNotNull(offPlanAddedState(ready, "dead_hang"))
        assertEquals(queue, assertNotNull(removedState(added)).queue)
    }

    /**
     * Whole-release review: he corrected the squat to 110 against the plan's
     * 100 and that stands for set 2; an off-plan curl put in front of set 2
     * and then removed used to re-seed set 2 from the plan's 100.
     */
    @Test
    fun `removing an off-plan set gives back the block's standing statements`() {
        val corrected =
            resting(queueIndex = 0).copy(
                loadInput = "110",
                statedLoadKg = 110.0,
                repsInput = "4",
                statedReps = 4,
                statedTempo = "4010",
                statedSide = "left",
            )
        val added = assertNotNull(offPlanAddedState(corrected, "leg_curl"))
        assertEquals("40", added.loadInput)
        assertNull(added.statedLoadKg)
        val removed = assertNotNull(removedState(added))
        assertEquals(queue, removed.queue)
        assertEquals(110.0, removed.statedLoadKg)
        assertEquals("110", removed.loadInput)
        assertEquals(4, removed.statedReps)
        assertEquals("4", removed.repsInput)
        assertEquals("4010", removed.statedTempo)
        assertEquals("left", removed.statedSide)
        assertNull(removed.heldStatements)
    }

    /**
     * Review of fullrev-fix-data: off-plan curl, run, and the squat's 110 given
     * back (hold spent); then "Add another" curl lands in front of squat set 2.
     * appendedState re-seeded to NONE and wiped the 110, and the second curl
     * (added, set index 1) was not read as off-plan, so set 2 recorded 100.
     *
     * The rest after the second curl runs through restingState, which a test
     * here cannot drive (it takes a PendingSetWrite); removing the second curl
     * goes through the same OffPlanHold.departs / resumed pair.
     */
    @Test
    fun `adding another set of the off-plan lift keeps the block's standing load`() {
        val inserted = assertNotNull(offPlanAddedState(resting(queueIndex = 0), "leg_curl"))
        // The curl has run and the 110 stands for squat set 2 again.
        val afterCurl =
            inserted.copy(queueIndex = 1, loadInput = "110", statedLoadKg = 110.0, heldStatements = null)
        val appended = assertNotNull(appendedState(afterCurl))
        assertEquals(
            listOf("back_squat", "leg_curl", "leg_curl", "back_squat", "back_squat"),
            appended.queue.map {
                it.exercise.id
            },
        )
        val held = assertNotNull(appended.heldStatements)
        assertEquals("back_squat", held.slot.exerciseId)
        assertEquals(1, held.slot.setIndexInExercise)
        assertEquals(110.0, held.stated.loadKg)
        val removed = assertNotNull(removedState(appended))
        assertEquals(110.0, removed.statedLoadKg)
        assertEquals("110", removed.loadInput)
        assertNull(removed.heldStatements)
    }

    @Test
    fun `an off-plan set holds the statements standing for the slot it displaces`() {
        val corrected = resting(queueIndex = 0).copy(loadInput = "110", statedLoadKg = 110.0)
        val held = assertNotNull(assertNotNull(offPlanAddedState(corrected, "leg_curl")).heldStatements)
        assertEquals("back_squat", held.slot.exerciseId)
        assertEquals(1, held.slot.setIndexInExercise)
        assertEquals(110.0, held.stated.loadKg)
    }

    @Test
    fun `an ad-hoc session has no plan to leave`() {
        assertNull(offPlanAddedState(RecordState(adHoc = true), "leg_curl"))
    }

    @Test
    fun `picking an exercise fills the boxes from the last time and clears the old statements`() {
        val s =
            prefilledState(
                RecordState(
                    adHoc = true,
                    recentLifts = listOf(lastCurl),
                    statedLoadKg = 99.0,
                    weightUnit = WeightUnit.KG,
                ),
                "leg_curl",
            )
        assertEquals("leg_curl", s.selectedExerciseId)
        assertEquals("40", s.loadInput)
        assertEquals("12", s.repsInput)
        assertEquals("3010", s.tempoInput)
        assertNull(s.statedLoadKg)
    }

    @Test
    fun `picking a body-weight lift never done opens the load on zero, not 60`() {
        val s = prefilledState(RecordState(adHoc = true, loadInput = "60"), "dead_hang")
        assertEquals("0", s.loadInput)
    }

    @Test
    fun `tapping the chip already picked keeps what was typed`() {
        val typed = RecordState(adHoc = true, selectedExerciseId = "back_squat", loadInput = "72.5")
        assertEquals(typed, exerciseTappedState(typed, "back_squat"))
    }
}
