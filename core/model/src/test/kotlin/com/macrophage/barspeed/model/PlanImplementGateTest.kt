package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the import gate refuses, and what it merely says out loud, about
 * `implement`, `bar_lb` and `bar_kg` (#253).
 *
 * REFUSED rather than resolved, in every case here, for the reason the
 * bodyweight pair is refused: whichever way a contradiction were settled
 * quietly, the settlement would become an INSTRUCTION the lifter follows with
 * a bar in their hands. "2 x 97.5 lb dumbbells" for a barbell set, or a bar
 * weight silently ignored on a machine, is worse than a plan that does not
 * import.
 *
 * The one WARNING is the other half. A plan written before 1.12 that declares
 * `implementCount` and no `implement` used to draw the pair line off the count
 * alone and now draws nothing, and the gate is the only place that says so --
 * the change is invisible in the document and invisible on the card, which
 * simply has one line fewer.
 */
class PlanImplementGateTest {
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

    private fun errors(exerciseKeys: String) = PlanImport.parse(planWith(exerciseKeys)).errors

    private fun warnings(exerciseKeys: String) = PlanImport.parse(planWith(exerciseKeys)).warnings

    @Test
    fun `an unrecognised implement is refused with the path named`() {
        assertEquals(
            listOf("sessions[0].exercises[0].implement must be one of barbell, dumbbell, other"),
            errors("\"implement\": \"barbel\","),
        )
    }

    @Test
    fun `the three words are accepted`() {
        listOf("barbell", "dumbbell", "other").forEach {
            assertEquals(emptyList(), errors("\"implement\": \"$it\","), "\"$it\" was refused")
        }
    }

    @Test
    fun `a bar weight on a non-barbell implement is refused with the path named`() {
        assertEquals(
            listOf(
                "sessions[0].exercises[0].bar_lb is declared on an exercise whose \"implement\" is " +
                    "not \"barbell\" - only a barbell has a bar to load",
            ),
            errors("\"implement\": \"dumbbell\", \"bar_lb\": 35,"),
        )
    }

    @Test
    fun `a bar weight with no implement at all is refused too`() {
        // The commonest way to get this wrong is to write the bar and forget
        // the key, and an omitted implement is "other" -- which has no bar.
        assertEquals(
            listOf(
                "sessions[0].exercises[0].bar_kg is declared on an exercise whose \"implement\" is " +
                    "not \"barbell\" - only a barbell has a bar to load",
            ),
            errors("\"bar_kg\": 15,"),
        )
    }

    @Test
    fun `two bar weights at once are refused`() {
        assertTrue(
            "sessions[0].exercises[0] must not have both bar_kg and bar_lb" in
                errors("\"implement\": \"barbell\", \"bar_kg\": 15, \"bar_lb\": 35,"),
        )
    }

    @Test
    fun `a bar of no weight is refused`() {
        assertEquals(
            listOf("sessions[0].exercises[0].bar_lb must be positive"),
            errors("\"implement\": \"barbell\", \"bar_lb\": 0,"),
        )
        assertEquals(
            listOf("sessions[0].exercises[0].bar_kg must be positive"),
            errors("\"implement\": \"barbell\", \"bar_kg\": -20,"),
        )
    }

    @Test
    fun `a bar on a barbell is accepted`() {
        assertEquals(emptyList(), errors("\"implement\": \"barbell\", \"bar_lb\": 35,"))
    }

    @Test
    fun `a dumbbell declared as one object is refused`() {
        assertEquals(
            listOf(
                "sessions[0].exercises[0]: \"implement\": \"dumbbell\" and \"implementCount\": 1 " +
                    "disagree - a dumbbell is a PAIR; omit the count, or write \"implement\": " +
                    "\"other\" for a single dumbbell",
            ),
            errors("\"implement\": \"dumbbell\", \"implementCount\": 1,"),
        )
    }

    @Test
    fun `a dumbbell declared as two or more is accepted`() {
        assertEquals(emptyList(), errors("\"implement\": \"dumbbell\", \"implementCount\": 2,"))
        assertEquals(emptyList(), errors("\"implement\": \"dumbbell\", \"implementCount\": 3,"))
        assertEquals(emptyList(), errors("\"implement\": \"dumbbell\","))
    }

    @Test
    fun `a barbell declared as more than one object is refused`() {
        assertEquals(
            listOf(
                "sessions[0].exercises[0]: \"implement\": \"barbell\" and \"implementCount\": 2 " +
                    "disagree - a barbell is ONE object; load_kg/load_lb is the total on the bar",
            ),
            errors("\"implement\": \"barbell\", \"implementCount\": 2,"),
        )
    }

    @Test
    fun `a count with no implement is warned about, not refused`() {
        assertEquals(emptyList(), errors("\"implementCount\": 2,"))
        assertTrue(
            warnings("\"implementCount\": 2,").any {
                it == "sessions[0].exercises[0]: back_squat declares \"implementCount\": 2 but no " +
                    "\"implement\", so nothing is drawn under the set on the Up next card - declare " +
                    "\"implement\": \"dumbbell\" for the pair line, or \"other\" to accept none."
            },
            "warnings were ${warnings("\"implementCount\": 2,")}",
        )
    }

    @Test
    fun `a declared implement silences that warning, other included`() {
        listOf("dumbbell", "other").forEach { word ->
            assertTrue(
                warnings("\"implement\": \"$word\", \"implementCount\": 2,").none { "but no \"implement\"" in it },
                "\"$word\" still warns about the count",
            )
        }
    }
}
