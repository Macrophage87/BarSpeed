package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SetGeometryPolicy.plannedExercise], the one call `flattenPlan` makes per
 * plan exercise (#65).
 *
 * The three calls it bundles are pinned at length in `SetGeometryPolicyTest`
 * and `StackInversionRuleTest`; what is pinned here is the WIRING: which
 * definition each is handed, and that the plan's kind override reaches the
 * definition the set runs against -- the line #65 measured surviving deletion
 * in `PlanQueue.kt`'s old `base.copy` block.
 */
class PlannedExerciseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun declared(id: String, declarations: String = ""): PlanExerciseDef = json.decodeFromString(
        PlanFile.serializer(),
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).sessions[0].exercises[0]

    private val kindDynamic = ",\"kind\":\"dynamic\""

    @Test
    fun `the plan's kind override reaches the definition the set runs against`() {
        val planned = SetGeometryPolicy.plannedExercise(ExerciseDef.seedById("plank")!!, declared("plank", kindDynamic))
        assertEquals(ExerciseKind.DYNAMIC, planned.exercise.kind)
        assertEquals(ExerciseKind.DYNAMIC, planned.geometry.kind, "and the export publishes the kind it ran as")
    }

    @Test
    fun `each part is the call it bundles, handed the definition that call takes`() {
        val cases = listOf(
            ExerciseDef.inferred("lat_pulldown") to
                declared("lat_pulldown", ",\"concentric\":\"down\",\"sensorOnStack\":true"),
            ExerciseDef.inferred("seated_row") to declared("seated_row", ",\"plane\":\"horizontal\""),
            ExerciseDef.seedById("deadlift")!! to declared("deadlift", ",\"start\":\"top\""),
            ExerciseDef.inferred("pull_up") to declared("pull_up"),
        )
        for ((base, plan) in cases) {
            val planned = SetGeometryPolicy.plannedExercise(base, plan)
            val resolved = SetGeometryPolicy.resolve(base, plan)
            assertEquals(resolved, planned.exercise, base.id)
            assertEquals(SetGeometryPolicy.describe(resolved, plan), planned.geometry, base.id)
            assertEquals(SetGeometryPolicy.stackRuleApplied(base, plan), planned.stackRuleApplied, base.id)
        }
    }

    @Test
    fun `the stack rule is asked of the stored definition and reaches the slot`() {
        val pulldown = SetGeometryPolicy.plannedExercise(
            ExerciseDef.inferred("lat_pulldown"),
            declared("lat_pulldown", ",\"concentric\":\"down\""),
        )
        assertTrue(pulldown.stackRuleApplied, "a drive-down stack id with no written sensorInverted")
        assertTrue(pulldown.exercise.sensorInverted)
        val bench = SetGeometryPolicy.plannedExercise(ExerciseDef.seedById("bench_press")!!, declared("bench_press"))
        assertFalse(bench.stackRuleApplied)
    }
}
