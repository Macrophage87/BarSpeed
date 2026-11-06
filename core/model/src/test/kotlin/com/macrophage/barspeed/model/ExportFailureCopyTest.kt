package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words a failed export shows. UX review 2026-11-06, history-export F6. */
class ExportFailureCopyTest {
    @Test
    fun `a failure shows the first line of its message`() {
        assertEquals(
            "Export failed: No space left on device",
            ExportFailureCopy.failed("IOException", "No space left on device\n\tat java.io.FileOutputStream"),
        )
    }

    @Test
    fun `a failure with no message shows the error's name`() {
        assertEquals("Export failed: OutOfMemoryError", ExportFailureCopy.failed("OutOfMemoryError", null))
        assertEquals("Export failed: IllegalStateException", ExportFailureCopy.failed("IllegalStateException", "  "))
    }

    @Test
    fun `a failure with neither still says what happened`() {
        assertEquals("Export failed: unknown error", ExportFailureCopy.failed(null, null))
    }

    @Test
    fun `a long reason is cut so the toast stays short`() {
        val message = ExportFailureCopy.failed("X", "a".repeat(500))
        val reason = message.removePrefix("Export failed: ")
        assertEquals(ExportFailureCopy.MAX_REASON_CHARS, reason.length)
        assertTrue(reason.endsWith("…"), message)
    }

    @Test
    fun `a missing session says nothing was exported`() {
        assertEquals("Session not found; nothing exported.", ExportFailureCopy.SESSION_MISSING)
    }
}
