package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the import gate does TODAY with the three keys #253 adds -- `implement`,
 * `bar_lb` and `bar_kg`.
 *
 * All three are unknown keys: the plan decodes, the declaration is dropped on
 * the floor, and the only trace is one aggregated unknown-key warning per key.
 * That is the pre-state every differential in this change is measured against,
 * and it is worth having in the tree rather than in a commit body because the
 * warning is generated from the serializer descriptor -- declaring the field is
 * what makes the warning stop, and nothing else says so.
 *
 * Also pinned: a plan declaring a dumbbell today implies NOTHING about
 * `implementCount`. That is what #253 changes at the resolved reading, and the
 * raw field must keep reading exactly what the author wrote either way.
 */
class PlanImplementKeyCharacterizationTest {
    private fun planWith(exerciseKeys: String): String = """
    {
      "schemaVersion": "${PlanFile.SCHEMA_VERSION}",
      "planName": "P",
      "sessions": [
        {
          "name": "S",
          "exercises": [
            { "exercise": "back_squat", $exerciseKeys "sets": [ { "reps": 5, "load_lb": 195 } ] }
          ]
        }
      ]
    }
    """.trimIndent()

    private fun unknownKeyWarning(result: PlanImport.Result, key: String) =
        result.warnings.any { "unknown key \"$key\"" in it }

    @Test
    fun `implement is an unknown key the gate reports and drops`() {
        val result = PlanImport.parse(planWith("\"implement\": \"barbell\","))
        assertEquals(emptyList(), result.errors, "declaring implement is not an error today")
        assertTrue(unknownKeyWarning(result, "implement"), "warnings were ${result.warnings}")
    }

    @Test
    fun `bar_lb and bar_kg are unknown keys the gate reports and drops`() {
        val result = PlanImport.parse(planWith("\"bar_lb\": 35, \"bar_kg\": 15,"))
        assertEquals(emptyList(), result.errors, "declaring a bar weight is not an error today")
        assertTrue(unknownKeyWarning(result, "bar_lb"), "warnings were ${result.warnings}")
        assertTrue(unknownKeyWarning(result, "bar_kg"), "warnings were ${result.warnings}")
    }

    @Test
    fun `a bar weight on a non-barbell implement is accepted today`() {
        val result = PlanImport.parse(planWith("\"implement\": \"dumbbell\", \"bar_lb\": 35,"))
        assertEquals(emptyList(), result.errors, "nothing checks the pair today")
    }

    @Test
    fun `a declared dumbbell implies no implement count today`() {
        val plan = PlanImport.parse(planWith("\"implement\": \"dumbbell\",")).plan
        assertNull(plan!!.sessions[0].exercises[0].implementCount)
    }
}
