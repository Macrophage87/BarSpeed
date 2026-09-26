package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * #331's proposed copy, checked clause by clause against the code, with
     * three clauses corrected where they were false against it:
     *
     *  - "the end time and the rest recorded after your last set" is false
     *    when `endSession` landed and only `recordFinalRestWindow` failed
     *    after it: both report FAILED, and in that case the end time IS
     *    written. Hence "or only that rest", the second cause the old text
     *    carried with its own "or".
     *  - "the session's heart-rate and HRV figures are rebuilt" is false in
     *    the same case, where the close wrote them and nothing is rebuilt,
     *    and false for the HRV wherever fewer than ten successive
     *    differences survive in the stored streams, where
     *    `SessionHrv.rmssdMs` returns null.
     *  - "The end time and the final rest are held only here" is false in
     *    the same case for the end time. The old text's last sentence says
     *    what is true in both cases, and it stays.
     */
    @Test
    fun `the body is the 331 copy, corrected where it was false against the code`() {
        assertEquals(
            "Part of this session was not written: the end time and the rest recorded after your last set, " +
                "or only that rest. Tapping FINISH SESSION AGAIN on this screen can still write them, after " +
                "freeing some space on the phone if that is what stopped it. Every set is already saved " +
                "either way, and any of the session's heart-rate and HRV figures the finish did not write " +
                "are rebuilt from those sets if you leave, the HRV only where they hold enough usable " +
                "heartbeats. Whatever has not been written is held only here, and leaving now loses it.",
            body,
        )
    }

    /**
     * The defect #331 reports. Since #62 a reader publishes an unclosed
     * session's heart-rate average and maximum from its set rows, and its HRV
     * from its stored streams where they support one, so the body may not say
     * that leaving loses them.
     */
    @Test
    fun `the body does not say leaving loses the heart-rate and HRV summary`() {
        assertFalse(body.contains("HRV summary"), "the body still says the summary is lost: $body")
    }

    /**
     * FAILED has two causes (`SessionCloser`): `endSession` not landing, or the
     * final rest window's write not landing after it did. A body that names
     * only the first tells a lifter in the second that the end time is lost.
     */
    @Test
    fun `the body names the case where only the final rest was not written`() {
        assertTrue(
            body.contains("or only that rest"),
            "the body names only one of the two ways a finish fails: $body",
        )
    }

    /**
     * `SessionHrv.rmssdMs` returns null, never 0, where fewer than ten
     * successive differences survive, so the rebuilt HRV is conditional and
     * the body must not promise it unconditionally.
     */
    @Test
    fun `the body does not promise a rebuilt HRV unconditionally`() {
        assertTrue(
            body.contains("the HRV only where they hold enough usable heartbeats"),
            "the body promises an HRV the stored streams may not support: $body",
        )
    }
}
