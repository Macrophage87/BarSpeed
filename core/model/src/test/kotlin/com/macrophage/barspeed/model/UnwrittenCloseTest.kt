package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a failed session close has not written, derived from where it stopped
 * and what it was carrying (#333).
 *
 * `SessionCloser` is two writes in order: `endSession`, which carries the end
 * time, the summary, the HRV, the rating and the skipped sets in one update,
 * then the rest window after the last set. Which one failed, and what the
 * frozen close held, is all the prompt has to go on.
 */
class UnwrittenCloseTest {
    @Test
    fun `a close that failed before its session write leaves everything it carried unwritten`() {
        assertEquals(
            UnwrittenClose(sessionRow = true, sessionRating = true, skippedSets = true, finalRest = true),
            UnwrittenClose.of(sessionRowLanded = false, rated = true, skipped = true, restCaptured = true),
        )
    }

    /**
     * The rating and the skips ride in the session write. Once it has landed
     * they are stored, whatever happens to the rest window after it.
     */
    @Test
    fun `a close whose session write landed leaves only the final rest unwritten`() {
        assertEquals(
            UnwrittenClose(sessionRow = false, sessionRating = false, skippedSets = false, finalRest = true),
            UnwrittenClose.of(sessionRowLanded = true, rated = true, skipped = true, restCaptured = true),
        )
    }

    /** Nothing that was never there can be lost: an unrated, unskipped close names neither. */
    @Test
    fun `a rating never given and skips never made are not named as unwritten`() {
        val u = UnwrittenClose.of(sessionRowLanded = false, rated = false, skipped = false, restCaptured = false)
        assertTrue(u.sessionRow)
        assertFalse(u.sessionRating)
        assertFalse(u.skippedSets)
        assertFalse(u.finalRest)
    }

    @Test
    fun `the state is FAILED while the row is open and REST_UNSAVED once it is closed`() {
        assertEquals(
            SessionCloseState.FAILED,
            UnwrittenClose.of(sessionRowLanded = false, rated = false, skipped = false, restCaptured = true).state,
        )
        assertEquals(
            SessionCloseState.REST_UNSAVED,
            UnwrittenClose.of(sessionRowLanded = true, rated = false, skipped = false, restCaptured = true).state,
        )
    }
}
