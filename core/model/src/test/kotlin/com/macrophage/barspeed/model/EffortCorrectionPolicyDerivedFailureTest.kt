package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two things the correction grid says that are not true, issue #140.
 *
 * Both are the same mistake: the grid pre-lights "Failed the set" from the
 * OR-ed verdict, so a failure the app derived from the rep count is drawn as a
 * tile the lifter tapped. #139 removed exactly that false attribution from the
 * effort line thirty lines above; the grid one tap away still makes it.
 *
 * Nothing stored or exported is wrong in either case -- both `failed` and `rpe`
 * are correct in the record. What is wrong is whose verdict the screen claims.
 */
class EffortCorrectionPolicyDerivedFailureTest {
    @Test
    fun `a set the lifter stopped short and never rated pre-lights nothing`() {
        // END SET EARLY passes no rating at all, so tappedFailed is false and
        // autoFailed is true. There is no tap to draw, and drawing one claims
        // the lifter said a word they never said.
        val s = EffortCorrectionPolicy.selection(rpe = null, warmup = false, tappedFailed = false, derivedFailed = true)
        assertFalse(s.failed, "the failed tile must not be pre-lit for a shortfall nobody declared")
        assertFalse(s.warmup)
        assertNull(s.rpe)
        // And the grid must say why nothing is lit, or "nothing lit" reads as
        // the app having lost the rating.
        assertTrue(s.derivedShortfall)
    }

    @Test
    fun `a rated set that also fell short pre-lights the rating the lifter chose`() {
        // Since #137 landed this is the common case, not the rare one: every
        // short set the lifter rates arrives here. The effort line reads
        // "Hard - 2 reps left - short of target" while the grid beside it lit
        // "Failed the set" instead, contradicting it and overwriting the
        // lifter's own input with a verdict they did not give.
        val s = EffortCorrectionPolicy.selection(rpe = 8, warmup = false, tappedFailed = false, derivedFailed = true)
        assertEquals(8, s.rpe)
        assertFalse(s.failed)
        assertTrue(s.derivedShortfall)
    }

    @Test
    fun `every rated set keeps its rating whatever the shortfall says`() {
        (6..10).forEach { rpe ->
            listOf(false, true).forEach { derived ->
                val s =
                    EffortCorrectionPolicy.selection(
                        rpe = rpe,
                        warmup = false,
                        tappedFailed = false,
                        derivedFailed = derived,
                    )
                assertEquals(rpe, s.rpe, "rpe=$rpe derivedFailed=$derived")
            }
        }
    }

    @Test
    fun `a rating standing beside an old failure tap is the later statement`() {
        // Tapping the failed tile stores rpe null, so a set carrying both has
        // been re-rated since the tap. Unreachable through today's screen --
        // SetRatingTracker.rate overwrites its tapped flag, so the two never
        // coexist in RecordState -- but the rule is total, and which of the two
        // wins is a decision, not an accident. It is pinned here because
        // nothing else in the suite distinguishes the ordering.
        val s = EffortCorrectionPolicy.selection(rpe = 9, warmup = false, tappedFailed = true, derivedFailed = false)
        assertEquals(9, s.rpe)
        assertFalse(s.failed)
    }

    @Test
    fun `no two tiles are ever pre-lit at once`() {
        // The invariant the inline precedence chain existed to hold, now over
        // the whole input space rather than the three cases it was written for:
        // 6 ratings x warm-up x tapped x derived = 48.
        val inputs = allInputs()
        assertEquals(48, inputs.size)
        inputs.forEach { i ->
            val s = i.select()
            val lit = listOf(s.warmup, s.failed, s.rpe != null).count { it }
            assertTrue(lit <= 1, "$i lit $lit tiles")
        }
    }
}
