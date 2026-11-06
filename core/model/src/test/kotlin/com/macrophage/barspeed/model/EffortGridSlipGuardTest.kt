package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffortGridSlipGuardTest {
    @Test
    fun `the window is 700 ms`() {
        assertEquals(700L, EffortGridSlipGuard.WINDOW_MS)
    }

    @Test
    fun `a grid tap with no +1 tap on the set is taken`() {
        assertTrue(EffortGridSlipGuard.accepts(nowMs = 5_000L, lastRepTapAtMs = null))
    }

    @Test
    fun `a grid tap inside 700 ms of a +1 tap is ignored`() {
        assertFalse(EffortGridSlipGuard.accepts(nowMs = 10_000L, lastRepTapAtMs = 10_000L))
        assertFalse(EffortGridSlipGuard.accepts(nowMs = 10_150L, lastRepTapAtMs = 10_000L))
        assertFalse(EffortGridSlipGuard.accepts(nowMs = 10_699L, lastRepTapAtMs = 10_000L))
    }

    @Test
    fun `a grid tap from 700 ms after the last +1 tap is taken`() {
        assertTrue(EffortGridSlipGuard.accepts(nowMs = 10_700L, lastRepTapAtMs = 10_000L))
        assertTrue(EffortGridSlipGuard.accepts(nowMs = 14_000L, lastRepTapAtMs = 10_000L))
    }
}
