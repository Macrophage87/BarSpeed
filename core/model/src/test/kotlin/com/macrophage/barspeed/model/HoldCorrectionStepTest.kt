package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the Correct popup's hold row steps a draft and what its figure says
 * (#312): [HoldEndPolicy.correction] and the three rules under it.
 *
 * Every expectation is a literal in seconds, for the reason
 * `TimedSetVoiceTest`'s KDoc gives: an expectation computed from the constant
 * it guards passes for any value of that constant.
 *
 * The pins in this file that hold on a hold with NO target were written green
 * before the rule changed: with no target there is no countdown to land on,
 * so the step stays the flat [TimedSetEndPolicy.CORRECTION_STEP_S] and the
 * larger step [HoldEndPolicy.BIG_CORRECTION_STEP_S].
 */
class HoldCorrectionStepTest {
    @Test
    fun `with no target a hold steps five either way and ten on the larger step`() {
        val row = HoldEndPolicy.correction(33, null, HoldEndSource.CLOCK)
        assertEquals(28, row.downS)
        assertEquals(38, row.upS)
        assertEquals(23, row.bigDownS)
        assertEquals("−10s", row.bigDownLabel)
        assertEquals("Held 33s", row.figure)
    }

    @Test
    fun `a target of zero or less is no target`() {
        assertEquals(38, HoldEndPolicy.steppedSeconds(33, 0, up = true))
        assertEquals(28, HoldEndPolicy.steppedSeconds(33, -5, up = false))
        assertEquals("Held 33s", HoldEndPolicy.heldFigure(33, 0))
    }

    @Test
    fun `with no target the floor is zero, as the write's is`() {
        assertEquals(0, HoldEndPolicy.steppedSeconds(3, null, up = false))
        assertEquals(0, HoldEndPolicy.steppedSeconds(0, null, up = false))
        assertEquals(0, HoldEndPolicy.bigStepDownSeconds(4, null))
    }

    /**
     * Which holds offer the larger step is still [HoldEndPolicy.downStepsS]'s
     * answer, read through the row: every word but SENSOR, and a set
     * recorded before database v19.
     */
    @Test
    fun `the larger step is offered wherever downStepsS offers it and nowhere else`() {
        assertNull(HoldEndPolicy.correction(33, null, HoldEndSource.SENSOR).bigDownS)
        assertNull(HoldEndPolicy.correction(33, null, HoldEndSource.SENSOR).bigDownLabel)
        listOf(null, HoldEndSource.CLOCK, HoldEndSource.LIFTER, HoldEndSource.CORRECTED).forEach {
            assertEquals(23, HoldEndPolicy.correction(33, null, it).bigDownS, "$it")
        }
    }
}
