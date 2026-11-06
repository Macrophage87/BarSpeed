package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.StoredSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `:app` half of two of the owner's 2026-11-06 rulings (UX review rest F1
 * and F2): where a continued plan session's queue starts ([resumedQueue]) and
 * what CANCEL SET puts back ([cancelledSetState]). Which slots are done and
 * when a set may be cancelled are `SessionResumePolicy.remainingSlots` and
 * `SetCancelPolicy.mayCancel`, pinned in `:core:model`; what is pinned here is
 * the queue and state those decisions are applied to.
 *
 * Written without a local run: this container has no Android SDK, so `:app`
 * does not build here and CI is the first thing to execute this file.
 */
class ResumeAndCancelTest {
    // ---- the continued queue -------------------------------------------------

    @Test
    fun `a continued queue drops the recorded slots and keeps the plan's numbering`() {
        val stored =
            listOf(
                StoredSet("back_squat", added = false, voided = false),
                StoredSet("back_squat", added = false, voided = false),
                StoredSet("deadlift", added = false, voided = false),
            )
        val out = resumedQueue(squatsThenDeadlifts(), stored)

        assertEquals(listOf("back_squat", "deadlift"), out.map { it.exercise.id })
        assertEquals(listOf(2, 1), out.map { it.setIndexInExercise })
        assertEquals(listOf(3, 2), out.map { it.setsInExercise })
    }

    /** The "move the sensor" flag follows the new neighbours, and is off on the first slot. */
    @Test
    fun `a continued queue recomputes the exercise-change flag`() {
        val out = resumedQueue(squatsThenDeadlifts(), listOf(StoredSet("back_squat", added = false, voided = false)))

        assertEquals(listOf(false, false, true, false), out.map { it.isExerciseChange })
    }

    // ---- CANCEL SET ------------------------------------------------------------

    /**
     * Cancelled in the lead-in of a set started from the rest screen: the rest
     * screen comes back with the same slot next, the lifter's standing load
     * as it was, and the rest clock less the seconds the set's clock ran.
     */
    @Test
    fun `cancel in the lead-in puts the rest screen back with the slot not consumed`() {
        val rest = resting().copy(statedLoadKg = 115.0, restRemainingS = 90)
        val started = assertNotNull(startedNextSetState(rest))
        assertEquals(1, started.queueIndex)
        val inSet = started.copy(stage = Stage.IN_SET, leadInRunning = true, setElapsedS = 4)

        val out = assertNotNull(cancelledSetState(inSet))

        assertEquals(Stage.RESTING, out.stage)
        assertEquals(0, out.queueIndex)
        assertEquals(rest.queue, out.queue)
        assertEquals(115.0, out.statedLoadKg)
        assertEquals(86, out.restRemainingS)
        assertFalse(out.leadInRunning)
        assertNull(out.cancelTo)
    }

    @Test
    fun `a rep set may be cancelled before its first rep and not after`() {
        val started = assertNotNull(startedNextSetState(resting()))
        val inSet = started.copy(stage = Stage.IN_SET, leadInRunning = false)

        assertTrue(inSet.mayCancelSet)
        assertNotNull(cancelledSetState(inSet))
        assertNull(cancelledSetState(inSet.copy(manualReps = 1)))
        assertNull(cancelledSetState(inSet.copy(sensorReps = 1)))
    }

    /** With no screen to go back to, or with no set running, there is nothing to cancel. */
    @Test
    fun `nothing is cancelled without a snapshot or outside a set`() {
        val inSet = resting().copy(stage = Stage.IN_SET)
        assertNull(cancelledSetState(inSet))
        val started = assertNotNull(startedNextSetState(resting()))
        assertNull(cancelledSetState(started))
    }
}

private val squat = ExerciseDef("back_squat", "Back squat")
private val deadlift = ExerciseDef("deadlift", "Deadlift")

private fun resting() =
    RecordState(stage = Stage.RESTING, queue = squatsThenDeadlifts(), queueIndex = 0, adHoc = false)

private fun squatsThenDeadlifts() = listOf(
    slot(squat, 0, 3),
    slot(squat, 1, 3),
    slot(squat, 2, 3),
    slot(deadlift, 0, 2),
    slot(deadlift, 1, 2),
)

private fun slot(exercise: ExerciseDef, setIndexInExercise: Int, setsInExercise: Int) = PlannedSlot(
    exercise = exercise,
    geometry = SetGeometryPolicy.describe(exercise, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = setsInExercise,
    reps = 5,
    durationS = null,
    plannedReps = 5,
    plannedDurationS = null,
    loadKg = 100.0,
    plannedLoadKg = 100.0,
    tempo = null,
    plannedTempo = null,
    side = null,
    plannedSide = null,
    implementCount = 1,
    implement = Implement.BARBELL,
    barKg = 20.0,
    exerciseNotes = null,
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 180,
    prepS = 10,
    sensors = 1,
    progression = ProgressionKind.WEIGHT,
    isExerciseChange = setIndexInExercise == 0 && exercise != squat,
    warmup = false,
    isAddedSet = false,
)
