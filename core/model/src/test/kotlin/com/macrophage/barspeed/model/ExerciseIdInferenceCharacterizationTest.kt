package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization sweep over the three id-based inferences in [ExerciseDef].
 *
 * Every row records what the code does TODAY, correct or not. Several rows are
 * wrong on purpose and are labelled: they are the defect this file exists to
 * make visible before it is fixed, and they are the rows a later commit flips.
 *
 * All three inferences are swept together because they share one mechanism —
 * `lower.contains(hint)` against a hint list — and because they feed each other:
 * [ExerciseDef.inferBarbell] is defined in terms of [ExerciseDef.inferKind]
 * (`ExerciseDef.kt:173`), so a kind row moving drags a barbell row with it.
 *
 * The three differ in one way that matters more than the arithmetic. `kind` and
 * `usesBarbell` are re-derived from the id on every read
 * (`SessionRepository.kt:160`, `:162`, `:169`, `:171`), so correcting them
 * corrects history too. `startsWith` is not: `SessionRepository.kt:182` writes
 * `inferStartPhase(id).name` into a `CustomExerciseEntity` the first time an id
 * is seen, `:159` reads it back forever after, and `ExerciseDao`
 * (`Daos.kt:88-101`) has no update path. That column is written once.
 */
class ExerciseIdInferenceCharacterizationTest {
    private data class Row(
        val id: String,
        val kind: ExerciseKind,
        val start: StartPhase,
        val barbell: Boolean,
    )

    private val sweep =
        listOf(
            // --- issue #24's reported cases: all four are wrong today ---
            Row("hanging_leg_raise", ExerciseKind.HOLD, StartPhase.CONCENTRIC, false),
            Row("hanging_knee_raise", ExerciseKind.HOLD, StartPhase.CONCENTRIC, false),
            Row("walking_lunge", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("dumbbell_walking_lunge", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("snatch_grip_deadlift", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("clean_grip_deadlift", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("snatch_grip_rdl", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),

            // --- Olympic variants: right today, and a naive slow-lift veto breaks them ---
            Row("squat_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("hang_squat_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("squat_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("hang_power_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("hang_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("hang_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("clean_pull", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("snatch_pull", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("clean_and_jerk", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("power_clean_from_blocks", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("romanian_deadlift_snatch_grip", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("deadlift_snatch_grip", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),

            // --- every seed id, which resolves by lookup before inference ever runs ---
            Row("back_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("front_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("overhead_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("romanian_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("barbell_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("hip_thrust", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("plank", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("side_plank", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("dead_hang", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("farmers_walk", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("suitcase_carry", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("power_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("power_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("push_press", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, true),
            Row("kettlebell_swing", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, false),
            Row("kettlebell_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, false),
            Row("kettlebell_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, false),

            // --- ids already asserted elsewhere, swept here so the whole table moves together ---
            Row("kettlebell_swing_heavy", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, false),
            Row("dumbbell_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, false),
            Row("med_ball_slam", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, true),
            Row("pallof_hold", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("wall_sit", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("overhead_carry", ExerciseKind.CARRY, StartPhase.CONCENTRIC, false),
            Row("sled_push", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("goblet_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("incline_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("paused_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("dumbbell_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("cable_fly", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, false),
            Row("plank_reach", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),

            // --- plurals: `contains` tolerates them by accident ---
            Row("farmers_walks", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("wall_sits", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("planks", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("push_presses", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, true),
            Row("snatches", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),
            Row("barbell_curls", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),

            // --- unsegmented ids: `contains` reaches inside them, whole tokens cannot ---
            Row("deadhang", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("kbswing", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, true),
            Row("farmerswalk", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("pullup", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("chinup", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("dbsnatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),

            // --- prefix fragments in NON_BARBELL_HINTS ("db_", "kb_") ---
            Row("db_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, false),
            Row("kb_swing", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, false),
            Row("db_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("kb_carry", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("dumbbell_push_press", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC, false),

            // --- hint lists colliding with themselves and with each other ---
            // "narrow" contains "row"; "pullover" contains "pull"; "machine"
            // contains "chin"; "prowler" contains "row"; and EXPLOSIVE_HINTS'
            // own "throw" contains CONCENTRIC_START_HINTS' "row".
            Row("narrow_grip_bench_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("pullover", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("machine_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("machine_chest_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("machine_fly", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("prowler_push", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("ball_throw", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC, true),

            // --- "pull" reaches inside "pulldown", which is the movement's own name ---
            Row("lat_pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("cable_pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("tricep_pushdown", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),

            // --- ordinary ids that must not move ---
            Row("seated_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("pull_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("chin_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("dip", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("sled_drag", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("yoke_walk", ExerciseKind.CARRY, StartPhase.ECCENTRIC, false),
            Row("l_sit", ExerciseKind.HOLD, StartPhase.ECCENTRIC, false),
            Row("cable_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("landmine_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("landmine_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("t_bar_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("leg_curl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("seated_leg_curl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("calf_raise", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("leg_extension", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("cossack_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("rear_foot_elevated_split_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, true),
            Row("shoulder_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("military_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, true),
            Row("smith_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, false),
            Row("band_pull_apart", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC, false),
            Row("bodyweight_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, false),
            Row("dumbbell_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC, false),
        )

    @Test
    fun `inferKind over the id sweep`() {
        sweep.forEach { assertEquals(it.kind, ExerciseDef.inferKind(it.id), it.id) }
    }

    @Test
    fun `inferStartPhase over the id sweep`() {
        sweep.forEach { assertEquals(it.start, ExerciseDef.inferStartPhase(it.id), it.id) }
    }

    @Test
    fun `inferBarbell over the id sweep`() {
        sweep.forEach { assertEquals(it.barbell, ExerciseDef.inferBarbell(it.id), it.id) }
    }

    @Test
    fun `every seed id resolves by lookup, so inference never decides one`() {
        // exerciseById checks seedById first (SessionRepository.kt:153). These
        // rows are in the sweep to prove a matcher change cannot reach them.
        ExerciseDef.SEED.forEach { seed ->
            assertEquals(seed, ExerciseDef.seedById(seed.id), seed.id)
        }
    }
}
