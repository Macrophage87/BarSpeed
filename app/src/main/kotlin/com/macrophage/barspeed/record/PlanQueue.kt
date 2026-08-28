package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.model.PlanNoteDisplay
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.SetGeometryPolicy

/** Fraction of a timed target that still counts as having made the hold. */
const val TIMED_CLOSE_ENOUGH_FRACTION = 0.9

/** Flatten a plan session into the ordered queue of sets the record flow walks. */
suspend fun SessionRepository.flattenPlan(planSession: PlanSessionDef): List<PlannedSlot> {
    val slots = mutableListOf<PlannedSlot>()
    for ((exerciseIdx, exerciseDef) in planSession.exercises.withIndex()) {
        val base = exerciseById(exerciseDef.exercise)
        // Plan-declared direction beats seed defaults and name inference: the
        // same movement pattern starts at the top on one machine and the
        // bottom on another, and no signal processing can tell which. The
        // precedence itself now lives in SetGeometryPolicy, in a module that
        // has tests; this file had none and could not be run against.
        val exercise = SetGeometryPolicy.resolve(base, exerciseDef)
        // Described from the definition that was resolved, not from the plan,
        // so what the export publishes is what the DSP was handed.
        val geometry = SetGeometryPolicy.describe(exercise, exerciseDef)
        exerciseDef.sets.forEachIndexed { setIdx, set ->
            // Which of the exercise's coaching keys the lifter reads without
            // touching the phone is decided in :core:model, where a test can
            // run on it. This file has one test class and it covers a different
            // function; the join it replaces lived here and was unreachable.
            val note =
                PlanNoteDisplay.forSet(
                    description = exerciseDef.description,
                    additionalNotes = exerciseDef.additionalNotes,
                    notes = exerciseDef.notes,
                    setNote = set.note,
                )
            slots +=
                PlannedSlot(
                    exercise = exercise,
                    geometry = geometry,
                    setIndexInExercise = setIdx,
                    setsInExercise = exerciseDef.sets.size,
                    reps = set.reps,
                    durationS = set.durationS,
                    loadKg = set.resolvedLoadKg,
                    plannedLoadKg = set.resolvedLoadKg,
                    tempo = set.tempo,
                    // The same declaration twice, and one of the two is frozen.
                    // `tempo` carries the lifter's between-sets adjustment once
                    // `advancedState` bakes it in; `plannedTempo` never does, so
                    // a plan that prescribes a tempo CHANGE mid-exercise stays
                    // distinguishable from a lifter who changed it. Mirrors
                    // loadKg / plannedLoadKg above.
                    plannedTempo = set.tempo,
                    side = set.side,
                    // Read from the exercise block, so two blocks of the same
                    // exercise in one session carry independent counts and
                    // nothing can leak between them. Display only: loadKg
                    // above is untouched by it.
                    implementCount = exerciseDef.implementCount,
                    exerciseNotes = note.visible,
                    targetMeanConVelMps = set.targetMeanConcentricVelocityMps,
                    velocityLossStopPct = set.velocityLossStopPct,
                    restS = set.restS,
                    prepS = exerciseDef.prepS,
                    isExerciseChange = setIdx == 0 && exerciseIdx > 0,
                )
        }
    }
    return slots
}

/** Plain-language verdicts for a hold or carry, which is judged on the clock alone. */
fun timedVerdicts(actualS: Int?, plannedS: Int?): List<String> {
    if (actualS == null) return emptyList()
    return when {
        plannedS == null -> listOf("Held ${actualS}s.")
        actualS >= plannedS -> listOf("Held ${actualS}s — full ${plannedS}s target. Nice.")
        actualS >= (plannedS * TIMED_CLOSE_ENOUGH_FRACTION).toInt() ->
            listOf("Held ${actualS}s of ${plannedS}s — just short.")
        else -> listOf("Held ${actualS}s of ${plannedS}s. Consider a shorter target or lighter load.")
    }
}
