package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What the import gate does with the three keys #253 adds -- `implement`,
 * `bar_lb` and `bar_kg`.
 *
 * All three were UNKNOWN keys one commit ago: the plan decoded, the
 * declaration was dropped on the floor, and one aggregated unknown-key warning
 * per key was the only trace. Declaring the fields is what stops that warning
 * -- it is generated from the serializer descriptor -- so the three cases
 * below now assert the silence rather than the warning, and the change of
 * expectation IS the differential. They are corrected here, at the commit that
 * makes the old expectation false, rather than left to red a later one.
 *
 * What the gate REFUSES about the three is pinned in [PlanImplementGateTest],
 * which replaced the case this file carried for one commit -- that nothing
 * validated them at all.
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
    fun `implement is a known key the gate no longer reports`() {
        val result = PlanImport.parse(planWith("\"implement\": \"barbell\","))
        assertEquals(emptyList(), result.errors)
        assertFalse(unknownKeyWarning(result, "implement"), "warnings were ${result.warnings}")
        assertEquals(Implement.BARBELL, result.plan!!.sessions[0].exercises[0].resolvedImplement)
    }

    @Test
    fun `bar_lb and bar_kg are known keys the gate no longer reports`() {
        val result = PlanImport.parse(planWith("\"implement\": \"barbell\", \"bar_lb\": 35,"))
        assertEquals(emptyList(), result.errors)
        assertFalse(unknownKeyWarning(result, "bar_lb"), "warnings were ${result.warnings}")
        assertFalse(
            unknownKeyWarning(PlanImport.parse(planWith("\"bar_kg\": 15,")), "bar_kg"),
            "bar_kg is still reported as unknown",
        )
    }

    @Test
    fun `a declared dumbbell leaves the raw count alone`() {
        val plan = PlanImport.parse(planWith("\"implement\": \"dumbbell\",")).plan
        assertNull(plan!!.sessions[0].exercises[0].implementCount)
    }
}
