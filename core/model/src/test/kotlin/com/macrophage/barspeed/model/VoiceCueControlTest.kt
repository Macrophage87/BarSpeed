package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceCueControlTest {
    @Test
    fun `the switch names voice cues, not a count`() {
        assertEquals("Voice cues on", VoiceCueControl.label(on = true))
        assertEquals("Voice cues off", VoiceCueControl.label(on = false))
    }

    @Test
    fun `the caption says the cadence is not silenced`() {
        assertEquals("Tempo sets still call the cadence.", VoiceCueControl.TEMPO_CAPTION)
    }

    /**
     * SETUP and READY draw the switch inline; IN_SET and RESTING draw it in the
     * top bar. Together that is every stage a session can be in before it is
     * finished, so no stage with a voice running is left without a switch.
     */
    @Test
    fun `the top bar carries the switch exactly during a set and the rest`() {
        val inTopBar = Stage.entries.filter { VoiceCueControl.inTopBar(it) }.toSet()
        assertEquals(setOf(Stage.IN_SET, Stage.RESTING), inTopBar)
    }
}
