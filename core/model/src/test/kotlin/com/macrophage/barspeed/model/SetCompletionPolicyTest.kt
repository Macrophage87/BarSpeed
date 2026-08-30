package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The predicate the effort grid is gated on.
 *
 * Characterization at this commit: every case here is the answer
 * `RecordState.setComplete` already gave inside the view model, asserted for
 * the first time. The value of writing them down is that
 * `SetEndControlPolicy` was swept over all 24 of its inputs while nothing
 * pinned WHICH of those inputs the app hands it -- so deleting the
 * `targetReps == null` guard, or judging a hold by a plain `>=` instead of
 * [TimedSetEndPolicy.fellShort], reddened nothing at all.
 *
 * Two things are deliberately NOT asserted here.
 *
 * The 90%-of-target window is left to the differential that changes it, so
 * that a pin written one commit and rewritten the next does not read as a
 * contract. What IS pinned here are the two ends a hold is unambiguous at
 * under either rule: the start of the clock and the target itself.
 *
 * And nothing here says what `:app` passes. These are six arguments; that
 * they are wired from the slot, the clock and the guide is compile-gated
 * only, because `:app` has one test file and none of it reaches a view model.
 */
class SetCompletionPolicyTest {
    private fun hold(targetS: Int?, elapsedS: Int) = SetCompletionPolicy.complete(
        timed = true,
        timedTargetS = targetS,
        elapsedS = elapsedS,
        guided = false,
        targetReps = null,
        guidedFinished = false,
    )

    private fun guided(targetReps: Int?, finished: Boolean) = SetCompletionPolicy.complete(
        timed = false,
        timedTargetS = null,
        elapsedS = 0,
        guided = true,
        targetReps = targetReps,
        guidedFinished = finished,
    )

    @Test
    fun `a hold whose clock has not moved is not complete`() {
        assertEquals(false, hold(targetS = 20, elapsedS = 0))
    }

    @Test
    fun `a hold whose clock has reached its target is complete`() {
        assertEquals(true, hold(targetS = 20, elapsedS = 20))
        assertEquals(true, hold(targetS = 60, elapsedS = 60))
    }

    @Test
    fun `a hold with no prescribed duration can never be judged`() {
        // The ad-hoc hold. It never finishes on its own, so answering false
        // would leave a tapped failure as the only way out of a set that went
        // fine -- which is the whole reason this returns a nullable.
        assertNull(hold(targetS = null, elapsedS = 0))
        assertNull(hold(targetS = null, elapsedS = 300))
    }

    @Test
    fun `a guided set is complete exactly when the guide has finished`() {
        assertEquals(false, guided(targetReps = 5, finished = false))
        assertEquals(true, guided(targetReps = 5, finished = true))
    }

    @Test
    fun `a guided set the plan gave no rep count can never be judged`() {
        // GuidedCadenceRunner does not call onFinished for one of these, so
        // `guidedFinished` stays false forever and false would mean "gated
        // for the whole set".
        assertNull(guided(targetReps = null, finished = false))
        assertNull(guided(targetReps = null, finished = true))
    }

    @Test
    fun `a set with neither a clock nor a guide is never judged`() {
        // A hand-counted or explosive set. Nothing in the app knows it is over
        // until the lifter says so, and SetEndKind.gatesOnCompletion is what
        // keeps this answer from reaching a gate at all.
        listOf(true, false).forEach { finished ->
            assertNull(
                SetCompletionPolicy.complete(
                    timed = false,
                    timedTargetS = 30,
                    elapsedS = 30,
                    guided = false,
                    targetReps = 5,
                    guidedFinished = finished,
                ),
                "a set that is neither timed nor guided was judged (guidedFinished=$finished)",
            )
        }
    }

    @Test
    fun `a guided hold is judged by its clock and not by its guide`() {
        // The same order SetEndKind.of tests in, asserted rather than assumed:
        // a hold is measured on the clock whatever else is true of it. Two
        // orderings of the same three facts is how the control offered ends up
        // disagreeing with the kind the set was classified as.
        assertEquals(
            false,
            SetCompletionPolicy.complete(
                timed = true,
                timedTargetS = 30,
                elapsedS = 0,
                guided = true,
                targetReps = 5,
                guidedFinished = true,
            ),
            "a guided hold answered its guide rather than its clock",
        )
    }
}
