package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision itself: ask now, or start the session (#181).
 *
 * RED WHEN WRITTEN. [BodyWeightPromptPolicy.shouldPrompt] is a constant
 * `false` at the parent of this commit, so every test below that expects an
 * ask fails here and every test that expects silence passes for the wrong
 * reason. The silent cases are kept in this file anyway rather than held back:
 * a gate is only interesting alongside the cases it must not fire on, and a
 * later change that makes the prompt fire on a barbell-only day has to red
 * something.
 *
 * The three states of a stored value are each exercised against a session that
 * needs one, because the reason UNKNOWN_AGE exists at all is that a two-state
 * model would have collapsed it into one of its neighbours and been wrong for
 * every lifter upgrading from 0.1.44.
 */
class BodyWeightPromptDecisionTest {
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun session(vararg bodyweight: Boolean) = PlanSessionDef(
        name = "S",
        exercises = bodyweight.mapIndexed { i, bw ->
            PlanExerciseDef(exercise = "e$i", bodyweight = bw, sets = listOf(PlanSetDef(reps = 5)))
        },
    )

    private fun prompt(session: PlanSessionDef, kg: Double? = null, setAtMs: Long? = null, skipped: Boolean = false) =
        BodyWeightPromptPolicy.shouldPrompt(
            session = session,
            kg = kg,
            setAtMs = setAtMs,
            nowMs = now,
            skippedThisSession = skipped,
        )

    // --- it asks ---

    @Test
    fun `a bodyweight session with nothing stored asks`() {
        assertTrue(prompt(session(true)), "a pull-up session with no stored body weight did not ask")
    }

    @Test
    fun `a bodyweight session with a stale value asks`() {
        assertTrue(
            prompt(session(true), kg = 80.0, setAtMs = now - 30 * day),
            "a body weight set a month ago did not prompt",
        )
    }

    /**
     * The upgrade case. Every value stored by a build before this one has no
     * date, and the app cannot distinguish it from a year-old figure.
     */
    @Test
    fun `a bodyweight session with an undated value asks`() {
        assertTrue(
            prompt(session(true), kg = 80.0, setAtMs = null),
            "a value stored before the app recorded set-at times did not prompt",
        )
    }

    @Test
    fun `one bodyweight exercise among loaded work is enough to ask`() {
        assertTrue(
            prompt(session(false, false, true), kg = 80.0, setAtMs = now - 30 * day),
            "a session ending in dips did not ask for the base load of those dips",
        )
    }

    @Test
    fun `a stored value of zero asks, because zero is an absence`() {
        assertTrue(
            prompt(session(true), kg = 0.0, setAtMs = now),
            "a stored 0 kg silenced the prompt, and would record every pull-up at its added load",
        )
    }

    // --- it stays silent ---

    /** The defect #181 exists to remove: a barbell-only day must never be asked. */
    @Test
    fun `a session with no bodyweight work never asks, however stale the value`() {
        assertFalse(prompt(session(false, false, false), kg = 80.0, setAtMs = now - 400 * day))
        assertFalse(prompt(session(false), kg = null, setAtMs = null))
        assertFalse(prompt(session(), kg = null, setAtMs = null))
    }

    @Test
    fun `a fresh value is not questioned`() {
        assertFalse(prompt(session(true), kg = 80.0, setAtMs = now))
        assertFalse(prompt(session(true), kg = 80.0, setAtMs = now - 13 * day))
    }

    /**
     * #161's interaction, stated as a test rather than as a note. A plan import
     * that carried a body weight IS a write, so it dates the value and the
     * session started straight afterwards must not ask for the number the
     * import gate just announced.
     */
    @Test
    fun `a value just written by a plan import satisfies the rule`() {
        assertFalse(
            prompt(session(true), kg = 84.0, setAtMs = now - 1_000L),
            "the session prompted for a body weight the plan had set seconds earlier",
        )
    }

    /** A skip is the lifter's own answer and lasts the session out. */
    @Test
    fun `a skip within this session silences every case that would otherwise ask`() {
        assertFalse(prompt(session(true), kg = null, setAtMs = null, skipped = true))
        assertFalse(prompt(session(true), kg = 80.0, setAtMs = now - 30 * day, skipped = true))
        assertFalse(prompt(session(true), kg = 80.0, setAtMs = null, skipped = true))
    }

    @Test
    fun `the fourteen-day boundary decides the prompt, inclusively`() {
        assertFalse(prompt(session(true), kg = 80.0, setAtMs = now - (14 * day - 1)))
        assertTrue(
            prompt(session(true), kg = 80.0, setAtMs = now - 14 * day),
            "exactly fourteen days old did not prompt",
        )
    }
}
