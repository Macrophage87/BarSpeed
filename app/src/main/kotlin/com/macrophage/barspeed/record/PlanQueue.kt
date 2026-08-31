package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.model.PlanNoteDisplay
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy

/**
 * Fraction of a timed target that still counts as having made the hold.
 *
 * Declared FROM [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] rather than beside
 * it, so there is one number in the app and not two that agree today.
 *
 * It is a `const val`, and until #188 that was load-bearing rather than
 * stylistic: `:app` ran its unit tests on a JDK 17 launcher while
 * `:core:model` compiles to class-file version 65, so a `:app` test that
 * caused `TimedSetEndPolicy` to be LOADED died with
 * `UnsupportedClassVersionError` before asserting anything, and a const
 * inlined into this file's constant pool at compile time loaded nothing.
 * `app/build.gradle.kts` now pins the test JVM to 21, so that trap is closed
 * and a `:app` test may load a `:core:model` type. The const stays because one
 * number is better than two; it is no longer what keeps [timedVerdicts]
 * testable.
 */
const val TIMED_CLOSE_ENOUGH_FRACTION = TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION

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
                    // The same two declarations twice, and one of each pair is
                    // frozen. `reps` and `durationS` carry the lifter's
                    // between-sets edit once `advancedState` bakes it in; these
                    // never do, so a plan that prescribes a CHANGE mid-exercise
                    // -- 10 / 8 / 6, or a hold ramping 30 / 45 / 60 -- stays
                    // distinguishable from a lifter who changed it. Mirrors
                    // loadKg / plannedLoadKg and tempo / plannedTempo below.
                    plannedReps = set.reps,
                    plannedDurationS = set.durationS,
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
                    exerciseNotesBehindTap = note.behindTap,
                    targetMeanConVelMps = set.targetMeanConcentricVelocityMps,
                    velocityLossStopPct = set.velocityLossStopPct,
                    restS = set.restS,
                    prepS = exerciseDef.prepS,
                    // Carried from the plan and UNREAD since #198: the
                    // export publishes one figure now, not two, and nothing
                    // resolves this one against anything. The set's own
                    // declaration still beats the exercise block's, which is
                    // the precedence every other per-set key has, so what the
                    // slot states is what the plan said about this set.
                    sensors = set.sensors ?: exerciseDef.sensors,
                    // Set level only, and there is no exercise-level fallback
                    // to read: a block ramps, so its warm-up singles and its
                    // working set are sets of the same exercise (#187).
                    warmup = set.warmup,
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
