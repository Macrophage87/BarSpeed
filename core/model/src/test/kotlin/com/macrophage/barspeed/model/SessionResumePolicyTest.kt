package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether walking back into Record asks "Continue this session?", and what it
 * says (#48).
 *
 * Nothing here reaches a ViewModel or a dialog. What is pinned is the decision
 * `RecordViewModel`'s `offerResume` asks and the words `RecordScreen`'s
 * `ResumeSessionDialog` draws; that they do was checked by reading them, and
 * by nothing else.
 */
class SessionResumePolicyTest {
    private val open =
        LatestSession(
            sessionId = 7,
            startedAtMs = 0L,
            endedAtMs = null,
            setCount = 2,
            lastSetEndedAtMs = 600_000L,
            nextOrderIdx = 2,
            planSessionName = "Lower A",
        )

    /**
     * The prompt says what continuing does and what declining leaves, and it
     * says declining leaves the row with no end time -- which is true: nothing
     * on that path writes to it.
     */
    @Test
    fun `the body names the session, its sets, and what each answer does`() {
        val body = SessionResumePolicy.body(open, "18:05")
        assertTrue("(Lower A)" in body, body)
        assertTrue("started at 18:05" in body, body)
        assertTrue("2 sets saved" in body, body)
        assertTrue("added to it" in body, body)
        assertTrue("no end time" in body, body)
    }

    @Test
    fun `one set is one set`() {
        assertTrue("1 set saved" in SessionResumePolicy.body(open.copy(setCount = 1), "18:05"))
    }

    // ---- whether to ask -----------------------------------------------------

    /** Ten minutes after the open session's last set. */
    private val soon = 600_000L + 10 * 60_000L

    /**
     * THE DEFECT (#48 (a)). An open session with sets, left minutes ago, with
     * nothing from the earlier visit still writing: walking back in started a
     * second row instead of asking.
     */
    @Test
    fun `an open session left minutes ago is offered`() {
        assertEquals(open, SessionResumePolicy.offer(open, soon, writeOutstanding = false))
    }

    /** The window's edge is inside it. */
    @Test
    fun `an open session at the edge of the window is offered`() {
        val edge = 600_000L + SessionResumePolicy.RESUME_WINDOW_MS
        assertEquals(open, SessionResumePolicy.offer(open, edge, writeOutstanding = false))
    }

    /** The other side of the differential: what must still never be asked about. */
    @Test
    fun `a closed, empty, stale or still-writing session is not offered`() {
        val beyond = 600_000L + SessionResumePolicy.RESUME_WINDOW_MS + 1
        assertNull(SessionResumePolicy.offer(null, soon, writeOutstanding = false))
        assertNull(SessionResumePolicy.offer(open.copy(endedAtMs = 900_000L), soon, writeOutstanding = false))
        assertNull(
            SessionResumePolicy.offer(open.copy(setCount = 0, lastSetEndedAtMs = null), soon, writeOutstanding = false),
        )
        assertNull(SessionResumePolicy.offer(open, beyond, writeOutstanding = false))
        assertNull(SessionResumePolicy.offer(open, soon, writeOutstanding = true))
    }

    /**
     * A continued session's close may not compute an HRV from this visit's
     * beats alone (#48). Its earlier sets' beats were in the earlier visit's
     * memory and are gone, so an RMSSD over what is left describes part of
     * the session under the whole session's name, by an amount nothing here
     * can bound. It is withheld: an empty list, which `Hrv.rmssdMs`, the
     * function `SessionCloser` computes it with, answers with null.
     */
    @Test
    fun `a continued session's close computes no HRV from part of its beats`() {
        val rr = listOf(800.0, 810.0, 790.0)
        assertEquals(emptyList(), SessionResumePolicy.closeRrMs(resumed = true, rrMs = rr))
        assertEquals(rr, SessionResumePolicy.closeRrMs(resumed = false, rrMs = rr))
    }
}
