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
