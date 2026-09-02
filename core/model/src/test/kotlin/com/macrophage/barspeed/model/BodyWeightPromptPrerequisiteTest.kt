package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The facts a "prompt for body weight only when the session needs it" rule
 * rests on, pinned BEFORE that rule exists (#181).
 *
 * Two of them, and neither is new behaviour:
 *
 * 1. A plan session says, per exercise, whether the lifter's own body is the
 *    load. That is the only thing in the whole document that can decide
 *    whether a session needs a body weight at all, so if the flag did not
 *    survive decoding — or defaulted the wrong way on a document that omits it
 *    — a prompt gated on it would fire on barbell-only days, which is the exact
 *    defect #181 exists to remove.
 * 2. An absent body weight does not stop a bodyweight set being recorded; it
 *    makes the set record its ADDED load alone. That is the cost the prompt has
 *    to state, and it is pinned here as a number rather than described, so a
 *    later change to [SetLoadPolicy.totalKg] cannot quietly make the prompt's
 *    sentence untrue.
 *
 * Parsed through [PlanImport] rather than constructed, for
 * [PlanBodyWeightImportTest]'s reason: the fact under test is about the
 * document, and constructing [PlanExerciseDef] directly would assume the wire
 * name it is being pinned to.
 */
class BodyWeightPromptPrerequisiteTest {
    private fun sessionOf(exercises: String): PlanSessionDef {
        val result = PlanImport.parse(
            """
            {
              "schemaVersion": "1.8",
              "planName": "P",
              "sessions": [ { "name": "S", "exercises": [ $exercises ] } ]
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), result.errors, "the fixture document did not import")
        return requireNotNull(result.plan).sessions.single()
    }

    /**
     * `bodyweight` decodes an omitted key as `null`, not `false`, as of #227 --
     * the same shape #223 gave `sensorOnStack`, and for the same reason: a
     * document that says nothing has made no declaration for
     * [SetGeometryPolicy.resolve] to read as a plan-level "no", so the app can
     * still supply its own default for an id built in as body-weight work
     * without a plan author's `false` losing to it later. This assertion was
     * `assertFalse` before #227 and is corrected here rather than reworded
     * around, because the decoded value genuinely changed.
     */
    @Test
    fun `an exercise that omits the bodyweight key declares nothing, not false`() {
        val session = sessionOf("""{ "exercise": "back_squat", "sets": [{ "reps": 5 }] }""")
        assertNull(
            session.exercises.single().bodyweight,
            "a document that says nothing about bodyweight decoded as a declaration",
        )
    }

    @Test
    fun `a declared bodyweight exercise survives decoding as one`() {
        val session = sessionOf("""{ "exercise": "pull_up", "bodyweight": true, "sets": [{ "reps": 5 }] }""")
        assertEquals(
            true,
            session.exercises.single().bodyweight,
            "a declared bodyweight exercise decoded as loaded work",
        )
    }

    @Test
    fun `a session can mix loaded and bodyweight work`() {
        val session = sessionOf(
            """
            { "exercise": "back_squat", "sets": [{ "reps": 5 }] },
            { "exercise": "pull_up", "bodyweight": true, "sets": [{ "reps": 8 }] }
            """.trimIndent(),
        )
        assertEquals(listOf(null, true), session.exercises.map { it.bodyweight })
    }

    /**
     * The cost sentence, as arithmetic. A missing body weight is not a refusal
     * and not a crash: the bodyweight term is 0, so a bodied-up pull-up with
     * 5 kg hung off it records 5 kg travelled instead of 85 kg, and every
     * power figure derived from that load is on the same wrong scale.
     */
    @Test
    fun `an absent body weight records a bodyweight set at its added load alone`() {
        assertEquals(5.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = null, addedKg = 5.0))
        assertEquals(85.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = 80.0, addedKg = 5.0))
    }

    /** A stale figure is not detectable downstream: it is just a number, used as if current. */
    @Test
    fun `a stale body weight is used exactly as a current one would be`() {
        assertEquals(70.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = 70.0, addedKg = 0.0))
        assertEquals(90.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = 90.0, addedKg = 0.0))
    }
}
