package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The button that opens the rest screen's correction popup (UX review
 * 2026-11-06, rest F5). It read CORRECT, which reads as a verdict rather than
 * an action; it now names the commonest fix.
 */
class LastSetFixLabelTest {
    @Test
    fun `the correction button names the fix`() {
        assertEquals("Fix count", LastSetRecordPolicy.fixLabel(timed = false))
        assertEquals("Fix time", LastSetRecordPolicy.fixLabel(timed = true))
    }
}
