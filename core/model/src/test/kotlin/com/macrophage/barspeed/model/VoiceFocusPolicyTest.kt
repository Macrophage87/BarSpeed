package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * When the voice holds audio focus (#339).
 *
 * `VoiceCounter` turns [VoiceFocusPolicy.step] into a platform request or
 * abandon; nothing here can say what the platform or a music app did with it.
 */
class VoiceFocusPolicyTest {
    private val idle = VoiceFocusState()

    @Test
    fun `an idle voice holds nothing`() {
        assertFalse(VoiceFocusPolicy.holds(idle))
    }

    /**
     * A set recorded with the voice off, or a set no voice counts, must not
     * duck the lifter's music for its whole length.
     */
    @Test
    fun `a set nothing has been said in holds nothing`() {
        assertFalse(VoiceFocusPolicy.holds(VoiceFocusPolicy.setStarted(idle)))
    }

    /** What a finished set leaves behind is a voice that holds nothing. */
    @Test
    fun `a set that ended with nothing playing holds nothing`() {
        val spoken = VoiceFocusPolicy.spoke(VoiceFocusPolicy.setStarted(idle), "a")
        val ended = VoiceFocusPolicy.setEnded(VoiceFocusPolicy.finished(spoken, "a"))
        assertFalse(VoiceFocusPolicy.holds(ended))
    }

    @Test
    fun `focus already where it is wanted is left alone`() {
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = false, wanted = false))
        assertEquals(VoiceFocusStep.KEEP, VoiceFocusPolicy.step(held = true, wanted = true))
    }
}
