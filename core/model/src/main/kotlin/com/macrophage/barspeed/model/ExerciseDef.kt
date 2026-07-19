package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/** How an exercise is performed and therefore how a set of it is measured. */
@Serializable
enum class ExerciseKind {
    /** Rep-based barbell/dumbbell movement — velocity and tempo tracking apply. */
    DYNAMIC,

    /** Isometric hold (plank, dead hang) — measured by duration. */
    HOLD,

    /** Loaded carry (farmer's walk) — measured by duration; load still matters. */
    CARRY,

    /** Single explosive concentric (snatch, clean) — peak velocity is the metric; no tempo. */
    EXPLOSIVE,
}

/** Exercise definition with per-exercise segmentation configuration. */
@Serializable
data class ExerciseDef(
    val id: String,
    val displayName: String,
    val startsWith: StartPhase = StartPhase.ECCENTRIC,
    val kind: ExerciseKind = ExerciseKind.DYNAMIC,
    val isCustom: Boolean = false,
) {
    val isTimed: Boolean get() = kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY

    companion object {
        val SEED: List<ExerciseDef> =
            listOf(
                ExerciseDef("back_squat", "Back Squat"),
                ExerciseDef("front_squat", "Front Squat"),
                ExerciseDef("bench_press", "Bench Press"),
                ExerciseDef("overhead_press", "Overhead Press"),
                ExerciseDef("deadlift", "Deadlift", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("romanian_deadlift", "Romanian Deadlift"),
                ExerciseDef("barbell_row", "Barbell Row", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("hip_thrust", "Hip Thrust", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("plank", "Plank", kind = ExerciseKind.HOLD),
                ExerciseDef("side_plank", "Side Plank", kind = ExerciseKind.HOLD),
                ExerciseDef("dead_hang", "Dead Hang", kind = ExerciseKind.HOLD),
                ExerciseDef("farmers_walk", "Farmer's Walk", kind = ExerciseKind.CARRY),
                ExerciseDef("suitcase_carry", "Suitcase Carry", kind = ExerciseKind.CARRY),
                ExerciseDef("snatch", "Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_snatch", "Power Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("clean", "Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_clean", "Power Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("push_press", "Push Press", StartPhase.ECCENTRIC, ExerciseKind.EXPLOSIVE),
            )

        fun seedById(id: String): ExerciseDef? = SEED.firstOrNull { it.id == id }
    }
}
