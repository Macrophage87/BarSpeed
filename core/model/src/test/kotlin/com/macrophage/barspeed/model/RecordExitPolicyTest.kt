package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What Back does on the record screen.
 *
 * The two `(pre-fix)` pins this file was created with have been replaced by
 * their inversions below, named in the commit body: `back leaves at once from a
 * set in progress (pre-fix)` and `back leaves at once while resting (pre-fix)`.
 * The naming follows `SetLoadPolicyTest`.
 *
 * The two `(pre-fix)` pins added with [SetWriteState] -- `the write state is not
 * read yet, in flight (pre-fix)` and `the write state is not read yet, failed
 * (pre-fix)` -- have been replaced by their inversions below, named here for
 * the same reason the first pair are.
 */
class RecordExitPolicyTest {
    @Test
    fun `back leaves at once from setup`() {
        assertEquals(
            ExitPrompt.NONE,
            RecordExitPolicy.promptFor(Stage.SETUP, SetWriteState.NONE, SessionCloseState.NONE),
        )
    }

    @Test
    fun `back leaves at once from ready`() {
        // Nothing is at risk here: no session row exists until the first set is
        // recorded, no set is in flight, and the service has not been started.
        assertEquals(
            ExitPrompt.NONE,
            RecordExitPolicy.promptFor(Stage.READY, SetWriteState.NONE, SessionCloseState.NONE),
        )
    }

    @Test
    fun `back offers to discard the set being recorded`() {
        // Nothing of this set is in the database yet, and on the session's
        // first set the session row does not exist either.
        assertEquals(
            ExitPrompt.SET_IN_PROGRESS,
            RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.NONE, SessionCloseState.NONE),
        )
    }

    @Test
    fun `back offers to close or abandon the open session while resting`() {
        // Every set is written; what is still open is the session row, and the
        // rest-window R-R intervals behind its HRV exist only in memory.
        assertEquals(
            ExitPrompt.SESSION_OPEN,
            RecordExitPolicy.promptFor(Stage.RESTING, SetWriteState.NONE, SessionCloseState.NONE),
        )
    }

    @Test
    fun `back leaves at once when the session is finished`() {
        // Everything is written and the service is stopped; leaving is what the
        // FINISHED stage's own Done button does.
        assertEquals(
            ExitPrompt.NONE,
            RecordExitPolicy.promptFor(Stage.FINISHED, SetWriteState.NONE, SessionCloseState.NONE),
        )
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

    // ---- the new prompts ---------------------------------------------------

    /**
     * The ordering guard against closing the session over an incomplete set
     * list, stated as a decision the policy holds rather than as a comment
     * beside the caller.
     */
    @Test
    fun `a set still saving offers no way to finish the session`() {
        assertFalse(ExitAction.FINISH_SESSION in ExitPrompt.SET_SAVING.actions)
        assertEquals(
            listOf(ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY),
            ExitPrompt.SET_SAVING.actions,
        )
    }

    @Test
    fun `a set still saving offers no way to cancel the write`() {
        // Nothing on this prompt claims to un-write a set whose insert is
        // already in flight; discarding is not among the options.
        assertFalse(ExitAction.DISCARD_SET_AND_LEAVE in ExitPrompt.SET_SAVING.actions)
    }

    @Test
    fun `a set that failed to save offers discarding it and nothing else`() {
        assertEquals(
            listOf(ExitAction.DISCARD_SET_AND_LEAVE, ExitAction.STAY),
            ExitPrompt.SET_UNSAVED.actions,
        )
    }

    @Test
    fun `finishing the session is offered from exactly one prompt`() {
        // Every other prompt is raised while a set is unfinished or unwritten,
        // and the session's heart-rate summary is built by reading the set rows
        // back, so a finish from any of them summarises an incomplete list.
        assertEquals(
            listOf(ExitPrompt.SESSION_OPEN),
            ExitPrompt.entries.filter { ExitAction.FINISH_SESSION in it.actions },
        )
    }

    // ---- the write state ---------------------------------------------------

    /**
     * A set whose write is in flight must not be offered the discard prompt.
     *
     * That prompt's body tells the lifter that nothing about the set has been
     * saved and that everything goes. Once the write outlives the screen that
     * is false in the direction that reads as a bug: the lifter is told the set
     * is destroyed, taps Discard, and finds it in their history.
     */
    @Test
    fun `back names the set as saving rather than as discardable`() {
        assertEquals(
            ExitPrompt.SET_SAVING,
            RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.IN_FLIGHT, SessionCloseState.NONE),
        )
    }

    @Test
    fun `a set still saving is never offered the discard prompt`() {
        assertNotEquals(
            ExitPrompt.SET_IN_PROGRESS,
            RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.IN_FLIGHT, SessionCloseState.NONE),
        )
    }

    /**
     * A failed write is its own answer, not the in-progress one.
     *
     * The set is over by then, so the in-progress body would point the lifter
     * at the effort grid and END SET EARLY -- controls that are no longer
     * drawn. Naming a control that is not on screen is worse than naming none.
     */
    @Test
    fun `back names the set as unsaved after the write failed`() {
        assertEquals(
            ExitPrompt.SET_UNSAVED,
            RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.FAILED, SessionCloseState.NONE),
        )
    }

    @Test
    fun `a failed write does not reuse the in-progress prompt`() {
        assertNotEquals(
            ExitPrompt.SET_IN_PROGRESS,
            RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.FAILED, SessionCloseState.NONE),
        )
    }

    /**
     * The three in-set answers must be three different prompts.
     *
     * Collapsing any two of them is how the wording goes wrong: each carries a
     * different statement about where the set actually is, and a shared prompt
     * can only be right about one of them.
     */
    @Test
    fun `each write state gets its own answer in-set`() {
        val answers = SetWriteState.entries.map { RecordExitPolicy.promptFor(Stage.IN_SET, it, SessionCloseState.NONE) }
        assertEquals(answers.size, answers.toSet().size, "in-set prompts collapsed: $answers")
    }

    /**
     * The ordering guard, asked of the policy rather than of the enum.
     *
     * The session's heart-rate summary is built by reading the set rows back,
     * so a finish offered while an insert is outstanding summarises a list that
     * set is missing from, once, with nothing to correct it afterwards.
     */
    @Test
    fun `no finish is offered while a set write is in flight`() {
        assertFalse(
            ExitAction.FINISH_SESSION in
                RecordExitPolicy.promptFor(Stage.IN_SET, SetWriteState.IN_FLIGHT, SessionCloseState.NONE).actions,
        )
    }

    /**
     * Ignored everywhere but IN_SET, and pinned rather than assumed.
     *
     * A set-end write cannot be outstanding in SETUP or READY, and the record
     * screen leaves IN_SET only once one has landed -- but "cannot happen" is
     * the caller's guarantee, not this function's. What this function promises
     * is that it does not look, which is the property that survives a caller
     * getting the state wrong.
     */
    @Test
    fun `every stage but in-set answers the same whatever the write state`() {
        Stage.entries.filter { it != Stage.IN_SET }.forEach { stage ->
            val expected = RecordExitPolicy.promptFor(stage, SetWriteState.NONE, SessionCloseState.NONE)
            SetWriteState.entries.forEach { write ->
                assertEquals(
                    expected,
                    RecordExitPolicy.promptFor(stage, write, SessionCloseState.NONE),
                    "$stage changed its answer for $write",
                )
            }
        }
    }

    @Test
    fun `no write state leaves the lifter without a prompt in-set`() {
        // Whatever the write is doing, a set is in the room: IN_SET never
        // becomes a silent exit.
        SetWriteState.entries.forEach { write ->
            assertFalse(
                RecordExitPolicy.promptFor(Stage.IN_SET, write, SessionCloseState.NONE) == ExitPrompt.NONE,
                "IN_SET leaves without asking while the write is $write",
            )
        }
    }

    // ---- the session close -------------------------------------------------

    @Test
    fun `the close state is not read yet, in flight (pre-fix)`() {
        assertEquals(
            ExitPrompt.SESSION_OPEN,
            RecordExitPolicy.promptFor(Stage.RESTING, SetWriteState.NONE, SessionCloseState.IN_FLIGHT),
        )
    }

    @Test
    fun `the close state is not read yet, failed (pre-fix)`() {
        assertEquals(
            ExitPrompt.SESSION_OPEN,
            RecordExitPolicy.promptFor(Stage.RESTING, SetWriteState.NONE, SessionCloseState.FAILED),
        )
    }

    @Test
    fun `a session being closed offers leaving and nothing else`() {
        assertEquals(
            listOf(ExitAction.LEAVE_SESSION_CLOSING, ExitAction.STAY),
            ExitPrompt.SESSION_CLOSING.actions,
        )
    }

    @Test
    fun `a session being closed is never offered as one to leave open`() {
        // "Leave without finishing" is false while a finish is in flight: the
        // close lands whatever the lifter does from here, so offering it would
        // be a choice with no effect dressed as a choice.
        assertFalse(ExitAction.LEAVE_SESSION_OPEN in ExitPrompt.SESSION_CLOSING.actions)
    }

    @Test
    fun `a close that failed offers leaving the session open`() {
        // Here it is true: the close did not land, so the row really is open.
        assertEquals(
            listOf(ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY),
            ExitPrompt.SESSION_NOT_CLOSED.actions,
        )
    }

    @Test
    fun `leaving a session that is closing is offered from exactly one prompt`() {
        assertEquals(
            listOf(ExitPrompt.SESSION_CLOSING),
            ExitPrompt.entries.filter { ExitAction.LEAVE_SESSION_CLOSING in it.actions },
        )
    }

    /**
     * The mirror of the write-state pin, and the reason the two states are
     * separate parameters rather than one merged enum.
     *
     * Merging them would make "a set write outstanding AND a close outstanding"
     * unrepresentable, and would delete this property and its twin above by
     * construction rather than by decision.
     */
    @Test
    fun `every stage but resting answers the same whatever the close state`() {
        Stage.entries.filter { it != Stage.RESTING }.forEach { stage ->
            val expected = RecordExitPolicy.promptFor(stage, SetWriteState.NONE, SessionCloseState.NONE)
            SessionCloseState.entries.forEach { close ->
                assertEquals(
                    expected,
                    RecordExitPolicy.promptFor(stage, SetWriteState.NONE, close),
                    "$stage changed its answer for $close",
                )
            }
        }
    }

    @Test
    fun `no close state leaves the lifter without a prompt while resting`() {
        SessionCloseState.entries.forEach { close ->
            assertFalse(
                RecordExitPolicy.promptFor(Stage.RESTING, SetWriteState.NONE, close) == ExitPrompt.NONE,
                "RESTING leaves without asking while the close is $close",
            )
        }
    }

    @Test
    fun `the close state never changes the in-set answer`() {
        // A close cannot be outstanding in IN_SET, and this function does not
        // look -- the same guarantee the write-state pin makes the other way.
        SetWriteState.entries.forEach { write ->
            val expected = RecordExitPolicy.promptFor(Stage.IN_SET, write, SessionCloseState.NONE)
            SessionCloseState.entries.forEach { close ->
                assertEquals(
                    expected,
                    RecordExitPolicy.promptFor(Stage.IN_SET, write, close),
                    "IN_SET/$write changed its answer for $close",
                )
            }
        }
    }
}
