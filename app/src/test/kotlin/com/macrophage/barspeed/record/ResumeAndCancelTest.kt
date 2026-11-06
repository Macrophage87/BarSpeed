package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.StoredSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `:app` half of the owner's 2026-11-06 ruling on continuing a session
 * (UX review rest F1): where a continued plan session's queue starts
 * ([resumedQueue]). Which slots are done is
 * `SessionResumePolicy.remainingSlots`, pinned in `:core:model`; what is
 * pinned here is the queue that decision is applied to.
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
}

private val squat = ExerciseDef("back_squat", "Back squat")
private val deadlift = ExerciseDef("deadlift", "Deadlift")

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
