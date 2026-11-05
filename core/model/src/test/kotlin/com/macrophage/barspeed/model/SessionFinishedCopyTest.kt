package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
