package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard on the whole design: a declared implement count is DISPLAY ONLY,
 * and cannot move a load a plan resolves.
 *
 * Nothing in this repository guarded that before this file. It is asserted by
 * decoding two documents identical but for the key and comparing every
 * resolved load by EXACT double equality, so the first person to route a count
 * into [PlanSetDef.resolvedLoadKg] -- the one change that would let a factor
 * of two reach a recorded set -- reds this instead of shipping it. A wrong
 * recorded load is metadata the IMU stream cannot reconstruct, so unlike
 * anything the DSP derives it is unrecoverable.
 */
class PlanImplementCountTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun plan(exerciseKeys: String) = json.decodeFromString(
        PlanFile.serializer(),
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"dumbbell_bench_press"$exerciseKeys,
           "sets":[{"reps":10,"load_lb":80},{"reps":10,"load_kg":36.5},{"reps":10}]}
        ]}]}
        """.trimIndent(),
    )

    @Test
    fun `a declared implement count does not change the load a plan resolves`() {
        val without = plan("").sessions[0].exercises[0].sets.map { it.resolvedLoadKg }
        assertEquals(listOf(80.0 / WeightUnit.LB_PER_KG, 36.5, null), without)
        listOf(1, 2, 3, 8).forEach { n ->
            assertEquals(
                without,
                plan(",\"implementCount\":$n").sessions[0].exercises[0].sets.map { it.resolvedLoadKg },
                "implementCount $n moved a load the plan resolves",
            )
        }
    }

    @Test
    fun `an omitted implement count decodes as absent, not as one`() {
        // Absent and 1 must stay separable: absent is what a plan writes for
        // two UNEVEN dumbbells, and it means "show the total alone".
        assertNull(plan("").sessions[0].exercises[0].implementCount)
        assertEquals(1, plan(",\"implementCount\":1").sessions[0].exercises[0].implementCount)
        assertEquals(2, plan(",\"implementCount\":2").sessions[0].exercises[0].implementCount)
    }

    @Test
    fun `a declared implement count is a known exercise key, not an unknown one`() {
        val result = PlanImport.parse(
            """
            {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"dumbbell_bench_press","implementCount":2,"sets":[{"reps":10}]}]}]}
            """.trimIndent(),
        )
        assertEquals(emptyList(), result.errors)
        assertTrue(
            result.warnings.none { it.contains("unknown key") },
            "implementCount was flagged as an unknown key: ${result.warnings}",
        )
    }

    @Test
    fun `an implement count written on a set is named as belonging on an exercise`() {
        val result = PlanImport.parse(
            """
            {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"dumbbell_bench_press","sets":[{"reps":10,"implementCount":2}]}]}]}
            """.trimIndent(),
        )
        // dumbbell_bench_press is not built in and this set prescribes reps,
        // so the undeclared-start warning fires alongside the wrong-level one.
        // keys.lost is prepended ahead of plan.warnings() in PlanImport.parse.
        assertEquals(
            listOf(
                "sessions[0].exercises[0].sets[0]: \"implementCount\" is a plan key, but not one " +
                    "a set has - it belongs on an exercise. It was ignored.",
                "sessions[0].exercises[0]: dumbbell_bench_press does not declare \"start\", and is not " +
                    "one of the app's built-in exercises, so the app is guessing which end of the range " +
                    "it begins at from the id alone - the guess decides the voice guide's first call and " +
                    "which direction opens a rep. Declare \"start\": \"top\" or \"bottom\" to replace it.",
            ),
            result.warnings,
        )
    }
}
