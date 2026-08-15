package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.model.PlanSessionDef

/** Fraction of a timed target that still counts as having made the hold. */
const val TIMED_CLOSE_ENOUGH_FRACTION = 0.9

/** Flatten a plan session into the ordered queue of sets the record flow walks. */
suspend fun SessionRepository.flattenPlan(planSession: PlanSessionDef): List<PlannedSlot> {
    val slots = mutableListOf<PlannedSlot>()
    for ((exerciseIdx, exerciseDef) in planSession.exercises.withIndex()) {
        val base = exerciseById(exerciseDef.exercise)
        // Plan-declared direction beats seed defaults and name inference: the
        // same movement pattern starts at the top on one machine and the
        // bottom on another, and no signal processing can tell which.
        // An omitted key must fall back to what `base` already says, not to the
        // Kotlin default: overwriting with the default discards the built-in
        // definition. travelRatio and plane are nullable on the wire, so they
        // can express "omitted" and are written that way here. sensorInverted,
        // sensorOnStack and bodyweight cannot -- they are non-nullable Boolean
        // (Plan.kt:124, :130, :149), so an omitted key and a declared false are
        // the same value, and there is nothing for `?:` to test. That is
        // latent today because no SEED entry sets any of the three; the first
        // one that does (pull_up, dip, seated_row) makes it live.
        val exercise =
            base.copy(
                startsWith = exerciseDef.startPhaseOverride ?: base.startsWith,
                concentricUp = exerciseDef.concentric?.let { it == "up" } ?: base.concentricUp,
                kind = exerciseDef.effectiveKind,
                sensorInverted = exerciseDef.sensorInverted,
                travelRatio = exerciseDef.travelRatio ?: base.travelRatio,
                horizontal = exerciseDef.plane?.let { it == "horizontal" } ?: base.horizontal,
                sensorOnStack = exerciseDef.sensorOnStack,
                bodyweight = exerciseDef.bodyweight,
            )
        exerciseDef.sets.forEachIndexed { setIdx, set ->
            slots +=
                PlannedSlot(
                    exercise = exercise,
                    setIndexInExercise = setIdx,
                    setsInExercise = exerciseDef.sets.size,
                    reps = set.reps,
                    durationS = set.durationS,
                    loadKg = set.resolvedLoadKg,
                    plannedLoadKg = set.resolvedLoadKg,
                    tempo = set.tempo,
                    side = set.side,
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
