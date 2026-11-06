package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseKindInferenceTest {
    @Test
    fun `unknown ids infer kind from name hints`() {
        assertEquals(ExerciseKind.EXPLOSIVE, ExerciseDef.inferKind("kettlebell_swing_heavy"))
        assertEquals(ExerciseKind.EXPLOSIVE, ExerciseDef.inferKind("dumbbell_snatch"))
        assertEquals(ExerciseKind.EXPLOSIVE, ExerciseDef.inferKind("med_ball_slam"))
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("pallof_hold"))
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("wall_sit"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("overhead_carry"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("sled_push"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("goblet_squat"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("incline_bench_press"))
    }

    /**
     * core-model review 2026-11-06, F4. A leg-press or hack-squat sled is a
     * machine the lifter presses or squats on, not a sled they push across
     * the floor. Read as a CARRY, a rep set of it published no velocity,
     * range or power at all.
     */
    @Test
    fun `a sled machine you press or squat on is not a carry`() {
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("leg_press_sled"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("hack_squat_sled"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("sled_leg_press"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("sled_push"), "a pushed sled still is")
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("sled_pull"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("sled_drag"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferKind("sled"))
    }

    /**
     * F4's other half. A plank row and a side-plank hip dip are rep work done
     * from a plank, not a plank held for time; read as a HOLD, the set ran on
     * the clock and its reps were never segmented.
     */
    @Test
    fun `rep work done from a plank is not a hold`() {
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("plank_row"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("plank_dumbbell_row"))
        assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind("side_plank_hip_dip"))
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("plank"), "a plank still is")
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("weighted_plank"))
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("plank_reach"), "unchanged; pinned in the sweep")
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("inverted_row_hold"), "the veto is the plank's only")
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("dip_hold"))
    }

    @Test
    fun `seed kettlebell lifts are explosive`() {
        assertEquals(ExerciseKind.EXPLOSIVE, ExerciseDef.seedById("kettlebell_swing")?.kind)
        assertEquals(ExerciseKind.EXPLOSIVE, ExerciseDef.seedById("kettlebell_snatch")?.kind)
    }
}
