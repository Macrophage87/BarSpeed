package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bodyweight a plan carries, at the one boundary where a document becomes a
 * plan (#161).
 *
 * Everything here goes through [PlanImport.parse] rather than constructing a
 * [PlanFile] directly, because the fact under test is about the DOCUMENT: a key
 * the decoder does not know is not a compile error, it is a silently ignored
 * line at the import gate. Constructing the data class would assume the wire
 * name it is being pinned to.
 *
 * None of these passes when it is written. `bodyweight_kg` and `bodyweight_lb`
 * are not plan keys at this commit, so every document below imports with the
 * bodyweight reported as an unknown key and nothing refused.
 */
class PlanBodyWeightImportTest {
    /** A one-exercise plan, with [planKeys] spliced in beside `planName`. */
    private fun parse(planKeys: String = "", exerciseKeys: String = "") = PlanImport.parse(
        """
        {
          "schemaVersion": "1.8",
          "planName": "P"$planKeys,
          "sessions": [
            {
              "name": "S",
              "exercises": [
                { "exercise": "pull_up", "bodyweight": true$exerciseKeys, "sets": [{ "reps": 5 }] }
              ]
            }
          ]
        }
        """.trimIndent(),
    )

    private fun assertNoBodyweightWarning(result: PlanImport.Result) = assertTrue(
        result.warnings.none { "bodyweight_kg" in it || "bodyweight_lb" in it },
        "the bodyweight was reported at the gate as a key the app does not know: ${result.warnings}",
    )

    @Test
    fun `a plan may declare the lifter's bodyweight in kilograms`() {
        val result = parse(planKeys = ""","bodyweight_kg": 84.0""")
        assertEquals(emptyList(), result.errors, "a declared bodyweight_kg was refused")
        assertNoBodyweightWarning(result)
    }

    @Test
    fun `a plan may declare the lifter's bodyweight in pounds`() {
        val result = parse(planKeys = ""","bodyweight_lb": 185.0""")
        assertEquals(emptyList(), result.errors, "a declared bodyweight_lb was refused")
        assertNoBodyweightWarning(result)
    }

    /**
     * The owner's ruling, and the reason the key is nullable at all: an LLM
     * that feels compelled to emit every key needs a spelling of "I was not
     * told". A null is not an error, not a warning, and writes nothing.
     */
    @Test
    fun `an unknown bodyweight written as null is accepted in silence`() {
        val result = parse(planKeys = ""","bodyweight_kg": null""")
        assertEquals(emptyList(), result.errors, "an explicit null bodyweight was refused")
        assertNoBodyweightWarning(result)
    }

    /**
     * Two units for one weight is the same contradiction `load_kg` with
     * `load_lb` already is, and gets the same answer: refused, rather than one
     * of them silently winning. Which one won would decide the base load of
     * every bodyweight set for as long as the value stands.
     */
    @Test
    fun `a plan declaring both units is refused`() {
        val result = parse(planKeys = ""","bodyweight_kg": 84.0, "bodyweight_lb": 185.0""")
        assertTrue(
            result.errors.any { "must not both be declared" in it },
            "a plan declaring a bodyweight in two units was accepted: ${result.errors}",
        )
    }

    @Test
    fun `a zero bodyweight is refused rather than read as unknown`() {
        val result = parse(planKeys = ""","bodyweight_kg": 0""")
        assertTrue(
            result.errors.any { "bodyweight_kg must be positive" in it },
            "a zero bodyweight was accepted: ${result.errors}",
        )
    }

    @Test
    fun `a negative bodyweight is refused in either unit`() {
        assertTrue(
            parse(planKeys = ""","bodyweight_lb": -185.0""").errors.any { "bodyweight_lb must be positive" in it },
            "a negative bodyweight_lb was accepted",
        )
        assertTrue(
            parse(planKeys = ""","bodyweight_kg": -84.0""").errors.any { "bodyweight_kg must be positive" in it },
            "a negative bodyweight_kg was accepted",
        )
    }

    /**
     * The near neighbour of this change. `bodyweight` is already an EXERCISE
     * key, so `bodyweight_kg` written beside it is the mistake this contract
     * invites, and the level check is what turns it from "unknown key, ignored"
     * into a line naming where it belongs.
     */
    @Test
    fun `a bodyweight written on an exercise is reported as belonging on the plan`() {
        val result = parse(exerciseKeys = ""","bodyweight_kg": 84.0""")
        assertTrue(
            result.warnings.any { "bodyweight_kg" in it && "belongs on the plan" in it },
            "an exercise-level bodyweight_kg was not pointed at the plan level: ${result.warnings}",
        )
    }
}
