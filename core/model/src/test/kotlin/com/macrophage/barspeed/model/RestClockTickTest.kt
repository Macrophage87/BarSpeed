package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rest countdown's tick and cue, read off the rest's start instant rather
 * than counted (UX review 2026-11-06, rest F10).
 *
 * The countdown subtracted one per `delay(1_000)`, so each tick added the
 * loop's overhead and the count stopped while the CPU slept. It now asks
 * [RestClockPolicy.remainingS] each tick; these pin when it wakes and what it
 * says when it does. That the `:app` loop calls them is compile-gated only.
 */
class RestClockTickTest {
    @Test
    fun `the next tick lands on the next whole second of the period`() {
        assertEquals(1_000L, RestClockPolicy.msToNextTick(startedAtMs = 5_000L, nowMs = 5_000L))
        assertEquals(750L, RestClockPolicy.msToNextTick(startedAtMs = 5_000L, nowMs = 5_250L))
        assertEquals(1L, RestClockPolicy.msToNextTick(startedAtMs = 5_000L, nowMs = 7_999L))
    }

    @Test
    fun `a clock moved backwards past the start still waits a positive time`() {
        assertEquals(750L, RestClockPolicy.msToNextTick(startedAtMs = 5_000L, nowMs = 4_250L))
    }

    @Test
    fun `a tick late by a whole second shows the true remainder, not one less than the last`() {
        // 150 s rest; the loop slept from 9.0 s to 11.3 s elapsed.
        assertEquals(139, RestClockPolicy.remainingS(restS = 150, startedAtMs = 0L, nowMs = 11_300L))
    }

    @Test
    fun `the last three seconds are each spoken once as they are crossed`() {
        assertEquals("3", RestClockPolicy.cueOnTick(previousS = 4, remainingS = 3, countdownFromS = 3))
        assertEquals("2", RestClockPolicy.cueOnTick(previousS = 3, remainingS = 2, countdownFromS = 3))
        assertEquals("1", RestClockPolicy.cueOnTick(previousS = 2, remainingS = 1, countdownFromS = 3))
        assertEquals("Rest over", RestClockPolicy.cueOnTick(previousS = 1, remainingS = 0, countdownFromS = 3))
    }

    @Test
    fun `seconds above the countdown say nothing`() {
        assertNull(RestClockPolicy.cueOnTick(previousS = 10, remainingS = 9, countdownFromS = 3))
        assertNull(RestClockPolicy.cueOnTick(previousS = 5, remainingS = 4, countdownFromS = 3))
    }

    @Test
    fun `a jump says only where the clock now is`() {
        assertEquals("2", RestClockPolicy.cueOnTick(previousS = 40, remainingS = 2, countdownFromS = 3))
        assertEquals("Rest over", RestClockPolicy.cueOnTick(previousS = 3, remainingS = 0, countdownFromS = 3))
    }

    @Test
    fun `a remainder that did not fall says nothing`() {
        assertNull(RestClockPolicy.cueOnTick(previousS = 2, remainingS = 2, countdownFromS = 3))
        assertNull(RestClockPolicy.cueOnTick(previousS = 2, remainingS = 3, countdownFromS = 3))
    }
}
