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
 * it, so there is one number in the app and not two that agree today. It stays
 * a `const val` deliberately: `:app`'s unit tests run on a JDK 17 launcher and
 * `:core:model` compiles to class-file version 65, so a `:app` test that caused
 * `TimedSetEndPolicy` to be LOADED would die with `UnsupportedClassVersionError`
 * before asserting anything -- the trap `PlanQueueTest`'s own KDoc documents.
 * A const initialised from another const is inlined into this file's constant
 * pool at compile time and loads nothing at runtime, so [timedVerdicts] and its
 * five tests are unaffected.
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
                    // The set's own declaration beats the exercise block's,
                    // the precedence every other per-set key already has --
                    // a warm-up and its working set are not always mounted
                    // the same way. Null when neither declared anything,
                    // which is a different fact from a declared 1 and is
                    // what lets the export publish both figures (#156).
                    sensors = set.sensors ?: exerciseDef.sensors,
                    isExerciseChange = setIdx == 0 && exerciseIdx > 0,
                )
        }
    }
    return slots
}

/**
 * The two facts [addedSetIndex] needs about one queued slot, and nothing else.
 *
 * A local type over two primitives rather than the slot itself, and the reason
 * is the toolchain trap [PlanQueueTest]'s own KDoc documents: `:app` compiles
 * and runs its unit tests on a JDK 17 launcher while `:core:model` emits class
 * file version 65, so a test that caused `ExerciseDef` or `ResolvedGeometry` to
 * be LOADED would die with `UnsupportedClassVersionError` before asserting
 * anything -- and `PlannedSlot` is built out of both. Projecting the two fields
 * the ordering rule reads keeps the rule inside the one `:app` file a test on
 * the CI path can reach, which is the cheapest real coverage available on this
 * path (#177).
 *
 * [setIndexInExercise] is carried because an exercise id alone cannot say where
 * a block ENDS. A session may run the same movement in two consecutive blocks,
 * and the second is a fresh prescription rather than a continuation of the
 * first; index 0 marks exactly the first set of a block. That is
 * `SetLoadPolicy.sameExerciseBlock`'s rule, read from here rather than restated
 * -- a second statement of "what is a block" is a second rule.
 */
data class QueueBlockKey(val exerciseId: String, val setIndexInExercise: Int)

/**
 * Where a set the lifter appends to the CURRENT exercise goes in the queue.
 *
 * The lifter is adding to the block, not jumping the queue: when the first set
 * shows the load was wrong, the plan's remaining set count is the wrong count,
 * and the sets that remain of the exercise are still wanted. So the appended
 * set goes AFTER the exercise's remaining sets, and only where the exercise has
 * none remaining is that the same thing as "immediately next" (#177).
 *
 * [blocks] is the whole queue projected through [QueueBlockKey], including any
 * sets already done -- indices are into the queue itself, so nothing here may
 * quietly renumber. [upcomingIndex] is the slot the next START will run:
 * `queueIndex + 1` during rest and `queueIndex` on READY, which is
 * `RecordState.upcomingIndex` and is passed rather than re-derived.
 *
 * The answer is an INSERTION index, so a return of `blocks.size` means "at the
 * end" and is a valid answer rather than an error.
 *
 * NAIVE FOR NOW, DELIBERATELY. This commit returns `upcomingIndex + 1` --
 * immediately next, which is what incidental ordering gives you and what #177
 * item 3 says is wrong. The block rule and the differentials that red without
 * it land together in the fix commit; what this commit adds is the seam and the
 * two answers both rules agree on.
 */
fun addedSetIndex(blocks: List<QueueBlockKey>, upcomingIndex: Int): Int {
    if (upcomingIndex !in blocks.indices) return blocks.size
    return upcomingIndex + 1
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
