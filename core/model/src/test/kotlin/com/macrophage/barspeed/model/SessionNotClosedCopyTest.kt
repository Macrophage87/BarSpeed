package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words of the prompt raised when Back is pressed after a finish came back
 * failed (#331, #333).
 *
 * Nothing here renders the dialog. What is pinned is the text `RecordScreen`
 * hands to it and the label it draws on the retry control, both read from
 * [SessionNotClosedCopy]; that the screen reads them and not a literal of its
 * own was checked by reading `RecordScreen`, and by nothing else.
 *
 * Until #333 the body was one constant for both of a failed close's causes,
 * and so named "the end time and the rest recorded after your last set, or
 * only that rest" whatever had failed, and never the rating or the skipped
 * sets the close also carries. The prompt is now worded from
 * [UnwrittenClose], what the close says it did not write.
 */
class SessionNotClosedCopyTest {
    /** `endSession` did not land, and the close carried everything it can carry. */
    private val everything =
        UnwrittenClose.of(sessionRowLanded = false, rated = true, skipped = true, restCaptured = true)

    /** `endSession` did not land, and the close carried nothing beyond the row itself. */
    private val rowOnly =
        UnwrittenClose.of(sessionRowLanded = false, rated = false, skipped = false, restCaptured = false)

    /** `endSession` landed; only the rest window after it did not. */
    private val restOnly =
        UnwrittenClose.of(sessionRowLanded = true, rated = true, skipped = true, restCaptured = true)

    /** Every shape `SessionCloser` can report: the row write failing, or only the rest after it. */
    private val reachable =
        listOf(true, false).flatMap { rated ->
            listOf(true, false).flatMap { skipped ->
                listOf(true, false).map { rest -> UnwrittenClose.of(false, rated, skipped, rest) }
            }
        } + restOnly

    /**
     * The prompt points at the retry instead of offering it, so the control it
     * names must be the one the rest screen draws. RecordExitPolicyTest states
     * the rule for the sibling prompts: naming a control that is not on screen
     * is worse than naming none.
     */
    @Test
    fun `the body names the retry control by the label the rest screen draws`() {
        reachable.forEach { u ->
            val body = SessionNotClosedCopy.body(u)
            assertTrue(body.contains(SessionNotClosedCopy.RETRY_LABEL), "$u names no retry control: $body")
        }
    }

    /**
     * The prompt is raised only while resting, and the record screen does not
     * leave IN_SET until a set's write has landed ([SetWriteState]).
     */
    @Test
    fun `the body says every set is already saved`() {
        reachable.forEach { u ->
            val body = SessionNotClosedCopy.body(u)
            assertTrue(body.contains("every set", ignoreCase = true), "$u stopped saying the sets are safe: $body")
        }
    }

    /**
     * The frozen close lives in `SessionCloser`'s heap and nowhere else, so
     * leaving does lose whatever it has not written. The prompt must keep
     * saying so.
     */
    @Test
    fun `the body says leaving loses whatever has not been written`() {
        reachable.forEach { u ->
            val body = SessionNotClosedCopy.body(u)
            assertTrue(body.contains("leaving now loses"), "$u stopped saying what leaving costs: $body")
        }
    }

    /**
     * The defect #331 reports. Since #62 a reader publishes an unclosed
     * session's heart-rate average and maximum from its set rows, and its HRV
     * from its stored streams where they support one, so the body may not say
     * that leaving loses them.
     */
    @Test
    fun `the body does not say leaving loses the heart-rate and HRV summary`() {
        reachable.forEach { u ->
            val body = SessionNotClosedCopy.body(u)
            assertFalse(body.contains("HRV summary"), "$u still says the summary is lost: $body")
        }
    }

    /**
     * `SessionHrv.rmssdMs` returns null, never 0, where fewer than ten
     * successive differences survive, so the body must not promise a rebuilt
     * HRV. Since the UX review of 2026-11-06 cut it to two sentences it names
     * no HRV at all, which is the strongest form of not promising one.
     */
    @Test
    fun `the body does not promise a rebuilt HRV`() {
        reachable.forEach { u ->
            val body = SessionNotClosedCopy.body(u)
            assertFalse(body.contains("HRV"), "$u promises an HRV the stored streams may not support: $body")
        }
    }

    // ---- by cause, issue #333 -----------------------------------------------

    /**
     * THE DEFECT (#333). Where `endSession` landed, the end time, the summary,
     * the rating and the skipped sets are stored and the row is closed. Titling
     * that "This session did not finish" is false.
     */
    @Test
    fun `a close whose session write landed is not titled as unfinished`() {
        assertEquals("The last rest did not save", SessionNotClosedCopy.title(restOnly))
        assertEquals(SessionNotClosedCopy.NOT_FINISHED_TITLE, SessionNotClosedCopy.title(everything))
    }

    /**
     * Where `endSession` did not land, the body lists what it was carrying:
     * the end time always, and the rating, the skipped sets and the heart rate
     * after the last set where the close held them.
     */
    @Test
    fun `a close that failed at its session write lists everything it carried`() {
        val body = SessionNotClosedCopy.body(everything)
        assertTrue("the end time" in body, body)
        assertTrue("your rating of the session" in body, body)
        assertTrue("the sets you skipped" in body, body)
        assertTrue("the heart rate recorded after your last set" in body, body)
    }

    /** Nothing that was never there is named as lost. */
    @Test
    fun `a close that carried no rating, no skips and no rest names none of them`() {
        val body = SessionNotClosedCopy.body(rowOnly)
        assertTrue("the end time" in body, body)
        assertFalse("rating" in body, body)
        assertFalse("skipped" in body, body)
        assertFalse("after your last set" in body, body)
    }

    /**
     * Where only the rest failed, the body says the session is finished, and
     * names neither the end time nor anything else `endSession` stored as at
     * risk.
     */
    @Test
    fun `a close that failed only at the final rest says the session is finished`() {
        val body = SessionNotClosedCopy.body(restOnly)
        assertTrue("The session is finished" in body, body)
        assertTrue("the heart rate recorded after your last set" in body, body)
        assertFalse("end time" in body, body)
        assertFalse("rating" in body, body)
        assertFalse("skipped" in body, body)
    }
}
