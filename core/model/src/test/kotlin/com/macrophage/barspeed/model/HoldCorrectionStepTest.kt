package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * A hold the clock ended at 45 of 45 has no time left, so it sits inside
     * the last ten seconds and one step down is one second -- the voice said
     * every one of those digits -- until ten are left. Beyond ten a step is
     * five and lands on a remaining time the voice named.
     */
    @Test
    fun `a clock-ended 45 of 45 steps down a second at a time to 10 to go, then five`() {
        val walk = generateSequence(45) { HoldEndPolicy.steppedSeconds(it, 45, up = false) }.drop(1).take(17)
        assertEquals(
            listOf(44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 30, 25, 20, 15, 10, 5, 0),
            walk.toList(),
        )
    }

    /**
     * The same marks from the other end: stepping up from nothing held, the
     * time left runs 40, 35 ... 15, 10, then 9, 8 ... 1 and the target -- the
     * sequence the voice speaks on a 45 s hold.
     */
    @Test
    fun `stepping up from 0 of 45 lands on every mark the voice names`() {
        val walk = generateSequence(0) { HoldEndPolicy.steppedSeconds(it, 45, up = true) }.drop(1).take(17)
        assertEquals(
            listOf(5, 10, 15, 20, 25, 30, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45),
            walk.toList(),
        )
    }

    /**
     * A sensor-ended 33 of 45 is off the marks, 12 s to go. Its first step
     * snaps to the nearest mark in the direction stepped -- 15 to go down, 10
     * to go up -- and steps from there follow the marks.
     */
    @Test
    fun `a sensor-ended 33 of 45 snaps to the nearest mark in the direction stepped`() {
        val row = HoldEndPolicy.correction(33, 45, HoldEndSource.SENSOR)
        assertEquals(30, row.downS)
        assertEquals(35, row.upS)
        assertEquals(25, HoldEndPolicy.steppedSeconds(30, 45, up = false))
        assertEquals(36, HoldEndPolicy.steppedSeconds(35, 45, up = true))
    }

    @Test
    fun `every word that can end a hold takes the same fine steps`() {
        (listOf(null) + HoldEndSource.entries).forEach {
            val row = HoldEndPolicy.correction(33, 45, it)
            assertEquals(30, row.downS, "$it")
            assertEquals(35, row.upS, "$it")
        }
    }

    /**
     * The marks are REMAINING times, as the voice's are, so on a target that
     * is not a multiple of five they fall on held figures that are not either.
     */
    @Test
    fun `the marks are remaining times, so a 32 s target steps on 12, 17 and 22`() {
        assertEquals(17, HoldEndPolicy.steppedSeconds(20, 32, up = false))
        assertEquals(22, HoldEndPolicy.steppedSeconds(20, 32, up = true))
        assertEquals(12, HoldEndPolicy.steppedSeconds(17, 32, up = false))
        assertEquals(23, HoldEndPolicy.steppedSeconds(22, 32, up = true))
    }

    /**
     * Past the target the voice named nothing, so a step there is the flat
     * five an overage was always stated in; coming back down it stops on the
     * target, the one mark on that side.
     */
    @Test
    fun `past the target a step is five and coming back lands on the target`() {
        assertEquals(50, HoldEndPolicy.steppedSeconds(45, 45, up = true))
        assertEquals(45, HoldEndPolicy.steppedSeconds(50, 45, up = false))
        assertEquals(45, HoldEndPolicy.steppedSeconds(47, 45, up = false))
        assertEquals(52, HoldEndPolicy.steppedSeconds(47, 45, up = true))
    }

    /**
     * The larger step survives #312 for a new reason: a clock-ended hold sits
     * where a fine step is one second, so "let go at 10 to go" would be ten
     * taps without it. It moves at least ten seconds down unless the floor at
     * zero stops it sooner, and its label says how far it actually moves.
     */
    @Test
    fun `the larger step moves at least ten down or to zero, and says how far`() {
        val clock = HoldEndPolicy.correction(45, 45, HoldEndSource.CLOCK)
        assertEquals(35, clock.bigDownS)
        assertEquals("−10s", clock.bigDownLabel)
        val offMark = HoldEndPolicy.correction(42, 45, HoldEndSource.LIFTER)
        assertEquals(30, offMark.bigDownS)
        assertEquals("−12s", offMark.bigDownLabel)
        assertEquals(25, HoldEndPolicy.correction(35, 45, HoldEndSource.CLOCK).bigDownS)
        assertEquals("−7s", HoldEndPolicy.correction(7, null, HoldEndSource.CLOCK).bigDownLabel)
    }

    @Test
    fun `a larger step that would move nothing is not offered`() {
        assertNull(HoldEndPolicy.correction(0, 45, HoldEndSource.CLOCK).bigDownS)
        assertNull(HoldEndPolicy.correction(0, 45, HoldEndSource.CLOCK).bigDownLabel)
        assertNull(HoldEndPolicy.correction(0, null, HoldEndSource.LIFTER).bigDownS)
    }

    @Test
    fun `the figure shows the held total and the time left to the target`() {
        assertEquals("Held 35s · 10s to go", HoldEndPolicy.heldFigure(35, 45))
        assertEquals("Held 0s · 45s to go", HoldEndPolicy.heldFigure(0, 45))
        assertEquals("Held 44s · 1s to go", HoldEndPolicy.heldFigure(44, 45))
        assertEquals("Held 35s · 10s to go", HoldEndPolicy.correction(35, 45, HoldEndSource.CLOCK).figure)
    }

    @Test
    fun `at or past the target the figure says so and never shows a negative`() {
        assertEquals("Held 45s · target reached", HoldEndPolicy.heldFigure(45, 45))
        assertEquals("Held 50s · target reached", HoldEndPolicy.heldFigure(50, 45))
        (0..90).forEach { assertFalse('-' in HoldEndPolicy.heldFigure(it, 45), "$it of 45") }
    }
}
