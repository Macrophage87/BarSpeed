package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When CANCEL SET is offered (owner's ruling 2026-11-06, UX review rest F2).
 *
 * What is pinned is the rule `RecordState.mayCancelSet` asks; that the screen
 * draws the control from it and that the ViewModel's cancel writes nothing was
 * checked by reading them, and by nothing else.
 */
class SetCancelPolicyTest {
    @Test
    fun `the lead-in may always be cancelled`() {
        assertTrue(SetCancelPolicy.mayCancel(timed = false, leadInRunning = true, repsCounted = 0))
        assertTrue(SetCancelPolicy.mayCancel(timed = true, leadInRunning = true, repsCounted = 0))
    }

    @Test
    fun `a rep set may be cancelled until its first rep is counted`() {
        assertTrue(SetCancelPolicy.mayCancel(timed = false, leadInRunning = false, repsCounted = 0))
        assertFalse(SetCancelPolicy.mayCancel(timed = false, leadInRunning = false, repsCounted = 1))
        assertFalse(SetCancelPolicy.mayCancel(timed = false, leadInRunning = false, repsCounted = 5))
    }

    /** A hold has no reps; once its clock runs it is under way. */
    @Test
    fun `a hold whose clock is running may not be cancelled`() {
        assertFalse(SetCancelPolicy.mayCancel(timed = true, leadInRunning = false, repsCounted = 0))
    }
}
