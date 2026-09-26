package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The words of the prompt raised when Back is pressed after a finish came back
 * failed (#331).
 *
 * Nothing here renders the dialog. What is pinned is the text `RecordScreen`
 * hands to it and the label it draws on the retry control, both read from
 * [SessionNotClosedCopy]; that the screen reads them and not a literal of its
 * own was checked by reading `RecordScreen`, and by nothing else.
 */
class SessionNotClosedCopyTest {
    private val body = SessionNotClosedCopy.EXIT_BODY

    /**
     * The prompt points at the retry instead of offering it, so the control it
     * names must be the one the rest screen draws. RecordExitPolicyTest states
     * the rule for the sibling prompts: naming a control that is not on screen
     * is worse than naming none.
     */
    @Test
    fun `the body names the retry control by the label the rest screen draws`() {
        assertTrue(
            body.contains(SessionNotClosedCopy.RETRY_LABEL),
            "the body names a retry control the rest screen does not draw: $body",
        )
    }

    /**
     * The prompt is raised only while resting, and the record screen does not
     * leave IN_SET until a set's write has landed ([SetWriteState]).
     */
    @Test
    fun `the body says every set is already saved`() {
        assertTrue(
            body.contains("Every set is already saved either way"),
            "the body stopped saying the sets are safe: $body",
        )
    }

    /**
     * The frozen close lives in `SessionCloser`'s heap and nowhere else, so
     * leaving does lose whatever it has not written. The prompt must keep
     * saying so.
     */
    @Test
    fun `the body says leaving loses whatever has not been written`() {
        assertTrue(
            body.contains("Whatever has not been written is held only here, and leaving now loses it."),
            "the body stopped saying what leaving costs: $body",
        )
    }
}
