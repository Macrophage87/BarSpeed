package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which COMPLETED sets are asked what limited them (#191).
 *
 * ## The defect
 *
 * The limiter is asked only of a set that FAILED. So a set the lifter
 * finished at two reps left and a set finished with three increments in hand
 * store the same thing -- nothing -- and a reader cannot tell a set that was a
 * test of the load from one that was a test of the tempo. On this programme
 * that is not an edge case: the blocks run 4011 and 3010, so the tempo is
 * deliberately the limiter on the light sets, and a tempo-limited rating read
 * as a load-limited one says add weight.
 *
 * ## Which sets, and why not all of them
 *
 * The counted rungs, 7 through 10, which is where the lifter reported the set
 * was near failure. Something limited it and only the lifter knows what.
 *
 * NOT the headroom rungs. Their whole content is that there was room left --
 * one increment, two, or much more -- so the answer is that nothing limited
 * the set, and asking produces a tap that tells a coach what it could already
 * assume. Not the unanchored values either: 2, 3 and 5 sort the anchors and
 * are never offered, so the app has no reading to act on. Not an unrated set,
 * because there is no rung to decide on.
 *
 * This is the reverse of what #191's body proposes -- it argues for asking at
 * the LIGHT end, where the answer is least predictable. The owner directed the
 * counted end, and the directive is what is implemented; the light-end case is
 * left standing on the issue rather than quietly dropped.
 *
 * ## Once
 *
 * Same three conditions the failed set already has: not if an answer stands,
 * not if the lifter dismissed it. A question that reopens is a question that
 * must be answered, and the lifter is between sets.
 */
class SetLimiterCompletedAskTest {
    @Test
    fun `a completed set rated at the counted end is asked what limited it`() {
        for (rpe in EffortScale.PROXIMITY_FLOOR_RPE..10) {
            assertTrue(
                SetLimiterPolicy.prompts(failed = false, rpe = rpe, limiter = null, dismissed = false),
                "a completed set rated $rpe is not asked what limited it",
            )
        }
    }

    /**
     * And it is drawn where the lifter is looking, not below the fold.
     *
     * The rest screen scrolls to 0 on entering RESTING and starting the next
     * set clears the answer, so a question drawn low is not merely unasked --
     * it becomes unaskable.
     */
    @Test
    fun `the question on a completed set is drawn at the top of the rest screen`() {
        assertEquals(
            SetLimiterPagePlacement.PROMPT,
            SetLimiterPolicy.placement(
                failed = false,
                rpe = 8,
                limiter = null,
                dismissed = false,
                changing = false,
            ),
        )
    }

    /** And the row into it is there, so an answer can be given or changed later. */
    @Test
    fun `the reason row is reachable on a completed set rated at the counted end`() {
        assertTrue(SetLimiterPolicy.offersCorrection(failed = false, rpe = 9, limiter = null))
    }

    /**
     * A set with room left is not asked. This is the half that keeps the
     * widening from being a tap on every set.
     */
    @Test
    fun `a completed set rated in the headroom rungs is not asked`() {
        for (tier in HeadroomTier.entries) {
            assertFalse(
                SetLimiterPolicy.prompts(failed = false, rpe = tier.rpe, limiter = null, dismissed = false),
                "a completed set rated ${tier.rpe} for $tier is asked what limited it",
            )
            assertFalse(
                SetLimiterPolicy.offersCorrection(failed = false, rpe = tier.rpe, limiter = null),
                "a completed set rated ${tier.rpe} for $tier is offered the reason row",
            )
        }
    }

    /** Nor is a rating the app never offered, and never will act on. */
    @Test
    fun `a completed set on an unanchored rating is not asked`() {
        for (rpe in EffortScale.UNANCHORED_RPE) {
            assertFalse(
                SetLimiterPolicy.prompts(failed = false, rpe = rpe, limiter = null, dismissed = false),
                "a completed set rated $rpe is asked what limited it",
            )
        }
    }

    /** Nor an unrated one: there is no rung to decide on. */
    @Test
    fun `an unrated completed set is not asked`() {
        assertFalse(SetLimiterPolicy.prompts(failed = false, rpe = null, limiter = null, dismissed = false))
        assertFalse(SetLimiterPolicy.offersCorrection(failed = false, rpe = null, limiter = null))
    }

    /** Once. A skip on a completed set leaves absence standing, as on a failure. */
    @Test
    fun `a completed set already asked and skipped is not asked again`() {
        assertFalse(SetLimiterPolicy.prompts(failed = false, rpe = 10, limiter = null, dismissed = true))
        assertEquals(
            SetLimiterPagePlacement.NONE,
            SetLimiterPolicy.placement(
                failed = false,
                rpe = 10,
                limiter = null,
                dismissed = true,
                changing = false,
            ),
        )
    }

    /** Nor is one that already carries an answer. */
    @Test
    fun `a completed set already carrying an answer is not asked again`() {
        assertFalse(
            SetLimiterPolicy.prompts(failed = false, rpe = 8, limiter = SetLimiter.PACE, dismissed = false),
        )
    }

    /**
     * A FAILED set is asked whatever it is rated, which is what it has always
     * done.
     *
     * The failure tile stores no rpe at all, so a rule that only consulted the
     * rating would stop asking the one set that has been asked since #189.
     * That is the near neighbour here and it is pinned rather than assumed.
     */
    @Test
    fun `a failed set is asked whatever it is rated`() {
        for (rpe in listOf(null, 1, 4, 6, 7, 10)) {
            assertTrue(
                SetLimiterPolicy.prompts(failed = true, rpe = rpe, limiter = null, dismissed = false),
                "a failed set rated $rpe is no longer asked why it ended",
            )
            assertTrue(
                SetLimiterPolicy.offersCorrection(failed = true, rpe = rpe, limiter = null),
                "a failed set rated $rpe is no longer offered the reason row",
            )
        }
    }
}
