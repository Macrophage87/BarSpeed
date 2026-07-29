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
    /** True for straight-bar lifts — enables the plate-loading readout. */
    val usesBarbell: Boolean = true,
) {
    val isTimed: Boolean get() = kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY

    companion object {
        val SEED: List<ExerciseDef> =
            listOf(
                ExerciseDef("back_squat", "Back Squat"),
                ExerciseDef("front_squat", "Front Squat"),
                ExerciseDef("bench_press", "Bench Press"),
                ExerciseDef("overhead_press", "Overhead Press", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("deadlift", "Deadlift", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("romanian_deadlift", "Romanian Deadlift"),
                ExerciseDef("barbell_row", "Barbell Row", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("hip_thrust", "Hip Thrust", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("plank", "Plank", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("side_plank", "Side Plank", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("dead_hang", "Dead Hang", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("farmers_walk", "Farmer's Walk", kind = ExerciseKind.CARRY, usesBarbell = false),
                ExerciseDef("suitcase_carry", "Suitcase Carry", kind = ExerciseKind.CARRY, usesBarbell = false),
                ExerciseDef("snatch", "Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_snatch", "Power Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("clean", "Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_clean", "Power Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("push_press", "Push Press", StartPhase.ECCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef(
                    "kettlebell_swing",
                    "Kettlebell Swing",
                    StartPhase.ECCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
                ExerciseDef(
                    "kettlebell_snatch",
                    "KB Snatch",
                    StartPhase.CONCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
                ExerciseDef(
                    "kettlebell_clean",
                    "KB Clean",
                    StartPhase.CONCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
            )

        fun seedById(id: String): ExerciseDef? = SEED.firstOrNull { it.id == id }

        private val EXPLOSIVE_HINTS = listOf("swing", "snatch", "clean", "jerk", "push_press", "throw", "slam")
        private val HOLD_HINTS = listOf("plank", "hold", "hang", "wall_sit", "l_sit")
        private val CARRY_HINTS = listOf("carry", "walk", "farmer", "yoke", "sled")

        /**
         * Best-effort kind for exercise ids not in the seed list (LLM plans invent
         * ids freely), so e.g. "kettlebell_swing_heavy" still gets the explosive
         * UI and "pallof_hold" the timed one.
         */
        fun inferKind(id: String): ExerciseKind {
            val lower = id.lowercase()
            return when {
                EXPLOSIVE_HINTS.any { lower.contains(it) } -> ExerciseKind.EXPLOSIVE
                HOLD_HINTS.any { lower.contains(it) } -> ExerciseKind.HOLD
                CARRY_HINTS.any { lower.contains(it) } -> ExerciseKind.CARRY
                else -> ExerciseKind.DYNAMIC
            }
        }

        private val CONCENTRIC_START_HINTS =
            listOf(
                "deadlift", "row", "curl", "pull", "chin", "shrug", "thrust",
                "overhead", "shoulder_press", "military", "raise", "snatch", "clean",
            )

        /**
         * Lifts that begin with the drive (deadlifts, rows, presses from the
         * rack position) must count concentric-first — pairing ecc→con would
         * miss almost every rep. Bench-style lifts start at lockout and lower
         * first, so bare "press" stays eccentric-first.
         */
        fun inferStartPhase(id: String): StartPhase {
            val lower = id.lowercase()
            return if (CONCENTRIC_START_HINTS.any { lower.contains(it) }) {
                StartPhase.CONCENTRIC
            } else {
                StartPhase.ECCENTRIC
            }
        }

        private val NON_BARBELL_HINTS =
            listOf("dumbbell", "db_", "kettlebell", "kb_", "cable", "machine", "band", "bodyweight", "smith")

        /** Plate math only applies to straight-bar lifts. */
        fun inferBarbell(id: String): Boolean {
            val lower = id.lowercase()
            if (NON_BARBELL_HINTS.any { lower.contains(it) }) return false
            return inferKind(id) == ExerciseKind.DYNAMIC || inferKind(id) == ExerciseKind.EXPLOSIVE
        }
    }
}
