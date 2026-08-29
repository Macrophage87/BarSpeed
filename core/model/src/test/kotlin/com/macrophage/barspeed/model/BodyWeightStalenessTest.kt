package com.macrophage.barspeed.model

import com.macrophage.barspeed.model.BodyWeightPromptPolicy.StoredBodyWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two structural halves of #181's rule: what is stored, and whether the
 * session cares.
 *
 * Green when written. Neither function existed before this commit, so nothing
 * here is a differential — the decision that joins them,
 * [BodyWeightPromptPolicy.shouldPrompt], still answers a constant false at
 * this commit and is pinned separately.
 */
class BodyWeightStalenessTest {
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun session(vararg bodyweight: Boolean) = PlanSessionDef(
        name = "S",
        exercises = bodyweight.mapIndexed { i, bw ->
            PlanExerciseDef(exercise = "e$i", bodyweight = bw, sets = listOf(PlanSetDef(reps = 5)))
        },
    )

    // --- what is stored ---

    @Test
    fun `nothing stored is absent`() {
        assertEquals(StoredBodyWeight.ABSENT, BodyWeightPromptPolicy.stateOf(null, null, now))
        assertEquals(StoredBodyWeight.ABSENT, BodyWeightPromptPolicy.stateOf(null, now, now))
    }

    /**
     * A zero is an absence dressed as a number, and it is the number the app
     * reads as the base load of every bodyweight set. It must not read as a
     * lifter who weighs nothing.
     */
    @Test
    fun `a non-positive or non-finite stored value is absent, not a weight`() {
        assertEquals(StoredBodyWeight.ABSENT, BodyWeightPromptPolicy.stateOf(0.0, now, now))
        assertEquals(StoredBodyWeight.ABSENT, BodyWeightPromptPolicy.stateOf(-80.0, now, now))
        assertEquals(StoredBodyWeight.ABSENT, BodyWeightPromptPolicy.stateOf(Double.NaN, now, now))
    }

    @Test
    fun `a value with no set-at time is unknown age, not fresh and not absent`() {
        assertEquals(StoredBodyWeight.UNKNOWN_AGE, BodyWeightPromptPolicy.stateOf(80.0, null, now))
        assertEquals(StoredBodyWeight.UNKNOWN_AGE, BodyWeightPromptPolicy.stateOf(80.0, 0L, now))
        assertEquals(StoredBodyWeight.UNKNOWN_AGE, BodyWeightPromptPolicy.stateOf(80.0, -1L, now))
    }

    @Test
    fun `a value dated inside the window is fresh`() {
        assertEquals(StoredBodyWeight.DATED_FRESH, BodyWeightPromptPolicy.stateOf(80.0, now, now))
        assertEquals(StoredBodyWeight.DATED_FRESH, BodyWeightPromptPolicy.stateOf(80.0, now - 13 * day, now))
    }

    /** The boundary is inclusive: exactly fourteen days old is stale. */
    @Test
    fun `fourteen days is the threshold and it is inclusive`() {
        assertEquals(
            StoredBodyWeight.DATED_FRESH,
            BodyWeightPromptPolicy.stateOf(80.0, now - (14 * day - 1), now),
        )
        assertEquals(StoredBodyWeight.DATED_STALE, BodyWeightPromptPolicy.stateOf(80.0, now - 14 * day, now))
        assertEquals(StoredBodyWeight.DATED_STALE, BodyWeightPromptPolicy.stateOf(80.0, now - 400 * day, now))
        assertEquals(14L, BodyWeightPromptPolicy.STALE_AFTER_DAYS)
        assertEquals(14L * day, BodyWeightPromptPolicy.STALE_AFTER_MS)
    }

    /**
     * A phone's clock moves backwards for reasons that have nothing to do with
     * the lifter. Skew resolves towards silence, because being nagged for a
     * number that is already correct is the failure this policy exists to
     * remove.
     */
    @Test
    fun `a set-at time in the future reads as fresh`() {
        assertEquals(StoredBodyWeight.DATED_FRESH, BodyWeightPromptPolicy.stateOf(80.0, now + 30 * day, now))
        assertNull(BodyWeightPromptPolicy.ageDays(now + 30 * day, now))
    }

    @Test
    fun `age in days truncates and is null without a time`() {
        assertEquals(0L, BodyWeightPromptPolicy.ageDays(now - 13 * 60 * 60 * 1000L, now))
        assertEquals(1L, BodyWeightPromptPolicy.ageDays(now - day, now))
        assertEquals(21L, BodyWeightPromptPolicy.ageDays(now - 21 * day, now))
        assertNull(BodyWeightPromptPolicy.ageDays(null, now))
        assertNull(BodyWeightPromptPolicy.ageDays(0L, now))
    }

    // --- whether the session cares ---

    @Test
    fun `a session of loaded work alone does not need a body weight`() {
        assertFalse(BodyWeightPromptPolicy.sessionNeedsBodyWeight(session(false, false, false)))
    }

    @Test
    fun `one bodyweight exercise anywhere in the session is enough`() {
        assertTrue(BodyWeightPromptPolicy.sessionNeedsBodyWeight(session(true)))
        assertTrue(BodyWeightPromptPolicy.sessionNeedsBodyWeight(session(false, false, true)))
        assertTrue(BodyWeightPromptPolicy.sessionNeedsBodyWeight(session(true, false, false)))
    }

    @Test
    fun `a session with no exercises needs nothing`() {
        assertFalse(BodyWeightPromptPolicy.sessionNeedsBodyWeight(session()))
    }

    // --- what the prompt says ---

    @Test
    fun `the reason names the cost and says an estimate is fine`() {
        val why = BodyWeightPromptPolicy.WHY_IT_MATTERS
        assertTrue("base load" in why, why)
        assertTrue("estimate is fine" in why, why)
        assertTrue("not a weigh-in" in why, why)
    }

    @Test
    fun `the stored line distinguishes absent, undated and dated`() {
        assertEquals(
            "No body weight stored yet.",
            BodyWeightPromptPolicy.storedLine(StoredBodyWeight.ABSENT, null, null, WeightUnit.KG),
        )
        assertEquals(
            "Stored: 80 kg, set before the app recorded when.",
            BodyWeightPromptPolicy.storedLine(StoredBodyWeight.UNKNOWN_AGE, 80.0, null, WeightUnit.KG),
        )
        assertEquals(
            "Stored: 80 kg, set 21 days ago.",
            BodyWeightPromptPolicy.storedLine(StoredBodyWeight.DATED_STALE, 80.0, 21L, WeightUnit.KG),
        )
    }

    /** In the lifter's own display unit, for PlanBodyWeightPolicy.appliedLine's reason. */
    @Test
    fun `the stored line is written in the display unit`() {
        assertEquals(
            "Stored: 176.4 lb, set 1 day ago.",
            BodyWeightPromptPolicy.storedLine(StoredBodyWeight.DATED_STALE, 80.0, 1L, WeightUnit.LB),
        )
    }

    @Test
    fun `a value set within the last day says today rather than zero days`() {
        assertEquals(
            "Stored: 80 kg, set today.",
            BodyWeightPromptPolicy.storedLine(StoredBodyWeight.DATED_FRESH, 80.0, 0L, WeightUnit.KG),
        )
    }
}
