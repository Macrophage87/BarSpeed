package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The predicate the effort grid is gated on.
 *
 * Most of this file is characterization: the answers `RecordState.setComplete`
 * already gave inside the view model, asserted for the first time because
 * `SetEndControlPolicy` was swept over all 24 of its inputs while nothing
 * pinned WHICH of those inputs the app hands it.
 *
 * TWO OF THEM ARE DIFFERENTIALS, not characterization, and they are named
 * here so a reader does not take the whole file for a description of what the
 * code did. `a hold is not complete until its clock reaches the target` and
 * `a hold prescribed no positive duration can never be judged` both FAIL at
 * the commit that introduces them and pass at the one after it. What they
 * change is which question a hold's clock is asked: not
 * [TimedSetEndPolicy.fellShort], whose 90% tolerance is the right answer to
 * "was this recorded hold short" and the wrong answer to "is the hold over",
 * but [TimedSetEndPolicy.remainingS] and [TimedSetEndPolicy.endsNow] -- the
 * same pair, on the same instant, that #168 ends the set on.
 *
 * Nothing here says what `:app` passes. These are six arguments; that they
 * are wired from the slot, the clock and the guide is compile-gated only,
 * because `:app` has one test file and none of it reaches a view model.
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
    fun `a hold is not complete until its clock reaches the target`() {
        // The owner: "it should only be shown when all the reps are finished
        // or the hold is finished." Finished is the target, not nine tenths
        // of it. `TimedSetEndPolicy.fellShort` answers "did this RECORDED
        // hold fall short", and 90% is the right threshold for THAT question
        // -- it is what keeps a scheduler losing a tick from writing a set as
        // failed. It is the wrong threshold for "is the hold over", and this
        // predicate asked it anyway: on a 20 s plank the grid opened at
        // elapsed 18 while #168 ends the set at 20, so the whole window in
        // which a hold could be rated in-set was its last two seconds, under
        // load.
        listOf(18, 19).forEach { elapsed ->
            assertEquals(
                false,
                hold(targetS = 20, elapsedS = elapsed),
                "a 20 s hold was judged finished at $elapsed s",
            )
        }
        assertEquals(false, hold(targetS = 60, elapsedS = 54), "a 60 s hold was judged finished at 54 s")
        // And the other side of the same instant, so the differential cannot
        // be satisfied by never answering true.
        assertEquals(true, hold(targetS = 60, elapsedS = 60))
        assertEquals(true, hold(targetS = 60, elapsedS = 61), "a hold past its target is not finished")
    }

    @Test
    fun `a hold prescribed no positive duration can never be judged`() {
        // TimedSetEndPolicy.remainingS already treats a non-positive target as
        // naming no instant a hold could reach, so such a set never auto-ends;
        // fellShort does not, and answered "finished" on its first tick. The
        // two now agree, because this reads remainingS.
        assertNull(hold(targetS = 0, elapsedS = 0))
        assertNull(hold(targetS = -5, elapsedS = 10))
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
