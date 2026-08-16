package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Back does on the record screen.
 *
 * The two `(pre-fix)` pins this file was created with have been replaced by
 * their inversions below, named in the commit body: `back leaves at once from a
 * set in progress (pre-fix)` and `back leaves at once while resting (pre-fix)`.
 * The naming follows `SetLoadPolicyTest`.
 */
class RecordExitPolicyTest {
    @Test
    fun `back leaves at once from setup`() {
        assertEquals(ExitPrompt.NONE, RecordExitPolicy.promptFor(Stage.SETUP))
    }

    @Test
    fun `back leaves at once from ready`() {
        // Nothing is at risk here: no session row exists until the first set is
        // recorded, no set is in flight, and the service has not been started.
        assertEquals(ExitPrompt.NONE, RecordExitPolicy.promptFor(Stage.READY))
    }

    @Test
    fun `back offers to discard the set being recorded`() {
        // Nothing of this set is in the database yet, and on the session's
        // first set the session row does not exist either.
        assertEquals(ExitPrompt.SET_IN_PROGRESS, RecordExitPolicy.promptFor(Stage.IN_SET))
    }

    @Test
    fun `back offers to close or abandon the open session while resting`() {
        // Every set is written; what is still open is the session row, and the
        // rest-window R-R intervals behind its HRV exist only in memory.
        assertEquals(ExitPrompt.SESSION_OPEN, RecordExitPolicy.promptFor(Stage.RESTING))
    }

    @Test
    fun `back leaves at once when the session is finished`() {
        // Everything is written and the service is stopped; leaving is what the
        // FINISHED stage's own Done button does.
        assertEquals(ExitPrompt.NONE, RecordExitPolicy.promptFor(Stage.FINISHED))
    }

    @Test
    fun `a set in progress offers discarding it and nothing else`() {
        // No save option: the durable write runs on a scope the pop cancels.
        assertEquals(
            listOf(ExitAction.DISCARD_SET_AND_LEAVE, ExitAction.STAY),
            ExitPrompt.SET_IN_PROGRESS.actions,
        )
    }

    @Test
    fun `an open session offers finishing it or leaving it open`() {
        // Three actions, not two: collapsing them would force every exit to
        // write a deliberate-finish timestamp over an abandoned session.
        assertEquals(
            listOf(ExitAction.FINISH_SESSION, ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY),
            ExitPrompt.SESSION_OPEN.actions,
        )
    }

    @Test
    fun `every prompt that stops the lifter offers a way to stay`() {
        ExitPrompt.entries.filter { it != ExitPrompt.NONE }.forEach { prompt ->
            assertTrue(ExitAction.STAY in prompt.actions, "$prompt traps the lifter on the screen")
        }
    }

    @Test
    fun `no prompt offers to discard a set and finish the session at once`() {
        // Finishing mid-set would leave five set-scoped jobs running and write
        // four session facts about a set that was never recorded.
        ExitPrompt.entries.forEach { prompt ->
            assertFalse(
                ExitAction.DISCARD_SET_AND_LEAVE in prompt.actions &&
                    ExitAction.FINISH_SESSION in prompt.actions,
                "$prompt offers a mid-set finish",
            )
        }
    }
}
