package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HistoryTarget.heldChip], called directly (#320).
 *
 * The history card and the record screen's post-set chip both draw the "Held"
 * chip from this one function. The record screen is in `:app`, where no test
 * reaches it, so this pins the function it calls, not the call. What the
 * screen passes in is compile- and lint-gated only.
 *
 * Green on arrival: the screen's old copy and this function agreed, so this
 * refactor changes no chip. Each pin's strength is shown by a mutation run.
 * The bands at a 45 s target: 90 % of 45 is 40.5, whose whole seconds are 40,
 * so 40 is close and 39 is well short. At a 10 s target the line is 9.
 */
class HeldChipTest {
    private fun chip(actualS: Int, targetS: Int?) = HistoryTarget.heldChip(actualS, targetS)

    @Test
    fun `the chip names the target where there is one and the seconds alone where not`() {
        assertEquals("Held 30/45s", chip(30, 45).text)
        assertEquals("Held 30s", chip(30, null).text)
    }

    @Test
    fun `the tone bands read through the close-enough fraction`() {
        assertEquals(HistoryTarget.Tone.OK, chip(46, 45).tone, "past the target")
        assertEquals(HistoryTarget.Tone.OK, chip(45, 45).tone, "at the target")
        assertEquals(HistoryTarget.Tone.WARN, chip(44, 45).tone, "just under")
        assertEquals(HistoryTarget.Tone.WARN, chip(40, 45).tone, "40 is the whole seconds of 90 % of 45")
        assertEquals(HistoryTarget.Tone.BAD, chip(39, 45).tone, "one under the close-enough line at 45")
        assertEquals(HistoryTarget.Tone.WARN, chip(9, 10).tone, "9 is 90 % of 10")
        assertEquals(HistoryTarget.Tone.BAD, chip(8, 10).tone, "one under the close-enough line at 10")
    }

    @Test
    fun `a hold with no target is not graded`() {
        assertEquals(HistoryTarget.Tone.OK, chip(0, null).tone)
        assertEquals(HistoryTarget.Tone.OK, chip(30, null).tone)
    }
}
