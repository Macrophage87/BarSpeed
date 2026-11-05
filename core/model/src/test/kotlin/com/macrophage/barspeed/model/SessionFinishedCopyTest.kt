package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The FINISHED stage's headline (#48).
 *
 * Nothing here renders `FinishedStage`. What is pinned is the text
 * `RecordScreen` hands to it, read from [SessionFinishedCopy]; that the screen
 * reads it and not a literal of its own was checked by reading `RecordScreen`,
 * and by nothing else.
 */
class SessionFinishedCopyTest {
    /** A session with a row was written, and the headline says so. */
    @Test
    fun `a session with a row is called saved`() {
        assertEquals("Session saved.", SessionFinishedCopy.headline(sessionWritten = true))
    }

    /**
     * THE DEFECT (#48 (c)). A session that recorded no set never opened a row,
     * and the close wrote nothing, so there is nothing saved to speak of. The
     * headline said "Session saved." anyway, over a screen whose View session
     * button is hidden because there is no session to view.
     */
    @Test
    fun `a session with no row is not called saved`() {
        val headline = SessionFinishedCopy.headline(sessionWritten = false)
        assertNotEquals(SessionFinishedCopy.SAVED, headline, "no row was written, and the headline says it was saved")
        assertEquals("Nothing was recorded, so no session was saved.", headline)
    }
}
