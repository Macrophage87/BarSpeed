package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/** Exercise definition with per-exercise segmentation configuration. */
@Serializable
data class ExerciseDef(
    val id: String,
    val displayName: String,
    val startsWith: StartPhase = StartPhase.ECCENTRIC,
    val isCustom: Boolean = false,
) {
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
            )

        fun seedById(id: String): ExerciseDef? = SEED.firstOrNull { it.id == id }
    }
}
