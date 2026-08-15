package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Differentials for accepting a plan that carries keys the app does not know,
 * and saying so instead of refusing the file.
 *
 * None of these passes when it is written. `PlanImport.parse` still rejects
 * unknown keys and still returns no key-level warnings, so every assertion here
 * is red against the commit before the one that implements it. That is the
 * point: the parts most likely to be wrong are the JSON path built through
 * nested arrays, which key sets belong at which of the four levels, how repeats
 * aggregate, and what order the bands come out in — and all of those are
 * testable before any of them exists.
 *
 * Ordering is by consequence, not by discovery:
 *
 *  1. a near miss on a known key, and a known key at the wrong level — the two
 *     that lose data. The plan looks like it said something and the app read
 *     nothing.
 *  2. a declared kind contradicting a built-in one. The app is being told to
 *     override something it ships with, and this is the only signal that it
 *     happened.
 *  3. a declared kind contradicting the set's own shape.
 *  4. a declared kind contradicting the guess. Informational.
 *  5. an unrecognised key. Extraneous by definition; nothing was lost.
 */
class PlanImportWarningTest {
    private fun parse(vararg exercises: String): PlanImport.Result = PlanImport.parse(
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[
          {"name":"Lower A","exercises":[${exercises.joinToString(",")}]}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `an unknown key is imported and flagged rather than refusing the file`() {
        val result = parse("""{"exercise":"back_squat","rpeTarget":8,"sets":[{"reps":5}]}""")

        assertEquals(emptyList(), result.errors, "an extra key is not a reason to refuse the plan")
        val plan = assertNotNull(result.plan)
        assertEquals("back_squat", plan.sessions[0].exercises[0].exercise)
        assertEquals(
            listOf("""sessions[0].exercises[0]: unknown key "rpeTarget" was ignored."""),
            result.warnings,
        )
    }

    @Test
    fun `one warning per distinct key, naming the first place and the count`() {
        val result = parse(
            """{"exercise":"back_squat","rpeTarget":8,"sets":[{"reps":5}]}""",
            """{"exercise":"bench_press","rpeTarget":7,"emphasis":"speed","sets":[{"reps":3}]}""",
        )

        assertEquals(
            listOf(
                """sessions[0].exercises[0]: unknown key "rpeTarget" was ignored (2 places in this plan).""",
                """sessions[0].exercises[1]: unknown key "emphasis" was ignored.""",
            ),
            result.warnings,
        )
    }

    @Test
    fun `the path is built through every level of nesting`() {
        val result = PlanImport.parse(
            """
            {"schemaVersion":"1.4","planName":"P","tempoStyle":"strict","sessions":[
              {"name":"A","exercises":[{"exercise":"back_squat","sets":[{"reps":5}]}]},
              {"name":"B","block":2,"exercises":[
                {"exercise":"bench_press","sets":[{"reps":5},{"reps":5,"cluster":2}]}
              ]}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                """: unknown key "tempoStyle" was ignored.""",
                """sessions[1]: unknown key "block" was ignored.""",
                """sessions[1].exercises[0].sets[1]: unknown key "cluster" was ignored.""",
            ),
            result.warnings,
        )
    }

    @Test
    fun `a near miss on a load key says the set imported with no load`() {
        // The one case where accepting a key the app does not know makes things
        // worse than rejecting the file: the plan named a load, the app read
        // none, and a loadless set is legitimate contract, so nothing else in
        // the pipeline can tell this from an intended bodyweight set.
        val result = parse("""{"exercise":"back_squat","sets":[{"reps":5,"loadKg":100}]}""")

        assertEquals(emptyList(), result.errors)
        assertEquals(
            listOf(
                "sessions[0].exercises[0].sets[0]: \"loadKg\" is not a plan key - did you mean " +
                    "\"load_kg\"? That set imported with no load at all, and records as bodyweight.",
            ),
            result.warnings,
        )
    }

    @Test
    fun `a near miss is spotted by casing and underscores alone, with no guessing`() {
        // lowercase, drop underscores, compare. It catches exactly the mistake
        // that motivates it -- a plan writing the Kotlin-side spelling of a key
        // that @SerialName renames -- and nothing else. A key that merely looks
        // similar is not a near miss and gets the ordinary unknown-key warning:
        // "rest" is not "rest_s" under this rule, and neither is "duration".
        val near = parse(
            """{"exercise":"back_squat","sets":[{"reps":5,"durationS":30,"restS":90,
               "velocityLossStopPct":20,"targetMeanConcentricVelocityMps":0.5}]}""",
        )
        assertTrue(
            near.warnings.all { it.contains("is not a plan key - did you mean") },
            "every one of these is a renamed key spelled the Kotlin way: ${near.warnings}",
        )
        assertEquals(4, near.warnings.size)

        val notNear = parse("""{"exercise":"back_squat","sets":[{"reps":5,"rest":90,"duration":30}]}""")
        assertTrue(
            notNear.warnings.all { it.contains("was ignored") },
            "a truncated key is a guess too far; it gets the plain warning: ${notNear.warnings}",
        )
        assertEquals(2, notNear.warnings.size)
    }

    @Test
    fun `a key that is real but at the wrong level names the level it belongs to`() {
        // "notes" on a set is the costly one: the set-level key is "note", and
        // a comment written for one set disappears.
        val result = parse("""{"exercise":"back_squat","sets":[{"reps":5,"notes":"pause at the bottom"}]}""")

        assertEquals(
            listOf(
                "sessions[0].exercises[0].sets[0]: \"notes\" is a plan key, but not one a set has - " +
                    "a comment on a single set is \"note\". It was ignored.",
            ),
            result.warnings,
        )
    }

    @Test
    fun `a declared kind that contradicts a built-in one is the loudest warning`() {
        val result = parse("""{"exercise":"plank","kind":"dynamic","sets":[{"reps":12}]}""")

        assertEquals(emptyList(), result.errors)
        assertEquals(ExerciseKind.DYNAMIC, assertNotNull(result.plan).sessions[0].exercises[0].effectiveKind)
        assertEquals(
            listOf(
                "sessions[0].exercises[0]: plank is built in as a hold, but this plan declares " +
                    "\"kind\": \"dynamic\" - the app will follow the plan.",
            ),
            result.warnings,
        )
    }

    @Test
    fun `a declared kind that contradicts the set's shape does not silence either`() {
        // The declaration still wins, and the set is still measured on what it
        // prescribes. Both are true at once, which is why this warns rather
        // than resolving one against the other.
        val result = parse("""{"exercise":"pallof_hold","kind":"hold","sets":[{"reps":10}]}""")

        assertEquals(
            listOf(
                "sessions[0].exercises[0]: this plan declares \"kind\": \"hold\" but prescribes reps - " +
                    "the set is measured on reps either way. Kind sets the movement type, not the timing.",
            ),
            result.warnings,
        )
    }

    @Test
    fun `a declared kind that only contradicts the guess is informational`() {
        // pallof_hold, not hanging_leg_raise. The first version of this test
        // used hanging_leg_raise, whose whole point is that the guess is WRONG
        // -- and the matcher fix makes the guess dynamic, which is what the
        // plan declares, so there is nothing left to disagree about. An id the
        // fix does not touch is needed to exercise this band at all.
        val result = parse("""{"exercise":"pallof_hold","kind":"dynamic","sets":[{"reps":10}]}""")

        assertEquals(
            listOf(
                "sessions[0].exercises[0]: this plan declares \"kind\": \"dynamic\" for " +
                    "pallof_hold; the app would have guessed hold from the id. The plan wins.",
            ),
            result.warnings,
        )
    }

    @Test
    fun `warnings come out in consequence order, not document order`() {
        val result = parse(
            """{"exercise":"back_squat","rpeTarget":8,"sets":[{"reps":5}]}""",
            """{"exercise":"pallof_hold","kind":"hold","sets":[{"reps":10}]}""",
            """{"exercise":"wall_sit","kind":"dynamic","sets":[{"reps":12}]}""",
            """{"exercise":"plank","kind":"dynamic","sets":[{"reps":12}]}""",
            """{"exercise":"bench_press","sets":[{"reps":5,"loadKg":100}]}""",
        )

        val shape = result.warnings.map {
            when {
                "is not a plan key" in it || "not one a set has" in it -> "lost"
                "built in as" in it -> "seed"
                "but prescribes" in it -> "shape"
                "would have guessed" in it -> "guess"
                else -> "extra"
            }
        }
        assertEquals(listOf("lost", "seed", "shape", "guess", "extra"), shape)
    }

    @Test
    fun `a declaration the app cannot read is an error, not a warning`() {
        // Unlike an extra key, this is not extraneous: the plan asked for
        // something specific and the app has no idea what. Guessing would be
        // worse than saying so.
        val result = parse("""{"exercise":"back_squat","kind":"ballistic","sets":[{"reps":5}]}""")

        assertEquals(
            listOf("sessions[0].exercises[0].kind must be one of dynamic, hold, carry, explosive"),
            result.errors,
        )
    }

    @Test
    fun `contradictory prescriptions stay errors while extraneous keys become warnings`() {
        // The line between the two: an extra key adds nothing and can be
        // dropped, a contradiction is two instructions for one set and cannot
        // be honoured either way round.
        val result = parse("""{"exercise":"plank","rpeTarget":8,"sets":[{"reps":12,"duration_s":60}]}""")

        assertEquals(
            listOf("sessions[0].exercises[0].sets[0] must not have both reps and duration_s"),
            result.errors,
        )
    }

    @Test
    fun `a malformed document is still a refusal`() {
        val result = PlanImport.parse("""{"schemaVersion":"1.4","planName":""" + '"')

        assertEquals(null, result.plan)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("Not valid plan JSON:"), result.errors.toString())
    }

    @Test
    fun `the existing declared-start warning keeps its wording and its place`() {
        val result = parse("""{"exercise":"back_squat","start":"bottom","rpeTarget":8,"sets":[{"reps":5}]}""")

        assertEquals(
            listOf(
                "sessions[0].exercises[0]: back_squat normally starts at the top, but this plan " +
                    "starts it at the bottom — the voice guide will follow the plan.",
                """sessions[0].exercises[0]: unknown key "rpeTarget" was ignored.""",
            ),
            result.warnings,
        )
    }
}
