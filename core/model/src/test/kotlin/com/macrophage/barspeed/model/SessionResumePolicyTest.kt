package com.macrophage.barspeed.model

import kotlin.test.Test
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
}
