package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
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
                    side = set.side,
                    // Read from the exercise block, so two blocks of the same
                    // exercise in one session carry independent counts and
                    // nothing can leak between them. Display only: loadKg
                    // above is untouched by it.
                    implementCount = exerciseDef.implementCount,
                    exerciseNotes = listOfNotNull(exerciseDef.notes, set.note)
                        .takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    targetMeanConVelMps = set.targetMeanConcentricVelocityMps,
                    velocityLossStopPct = set.velocityLossStopPct,
                    restS = set.restS,
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
