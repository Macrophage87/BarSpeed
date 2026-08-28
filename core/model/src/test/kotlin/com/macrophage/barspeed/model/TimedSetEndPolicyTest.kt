package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planned end of a hold or a carry, and what falling short of it means.
 *
 * The pins in this file cover the part of [TimedSetEndPolicy] that restates a
 * rule the app already applies -- the distance to the target, the instant the
 * clock reaches it, and the shortfall threshold. They are green at the commit
 * that adds the seam. The differentials for the parts that CHANGE behaviour
 * are in the commit after it and are red there.
 */
class TimedSetEndPolicyTest {
    /**
     * A hold with no prescription has no planned end. Reds if the null case
     * ever returns a number, which would end an ad-hoc hold on its first tick.
     */
    @Test
    fun `a hold with no prescribed duration has no planned end`() {
        assertNull(TimedSetEndPolicy.remainingS(elapsedS = 0, targetS = null))
        assertNull(TimedSetEndPolicy.remainingS(elapsedS = 90, targetS = null))
        assertFalse(TimedSetEndPolicy.endsNow(TimedSetEndPolicy.remainingS(0, null)))
        assertFalse(TimedSetEndPolicy.endsNow(TimedSetEndPolicy.remainingS(90, null)))
    }

    /**
     * A prescription of zero or less names no instant a hold could reach, so
     * it is absence rather than an instant already past. Reds if the `> 0`
     * guard goes: a plan carrying `duration_s: 0` would end the set on tick
     * one and record nothing.
     */
    @Test
    fun `a prescription of zero or less is no prescription`() {
        assertNull(TimedSetEndPolicy.remainingS(elapsedS = 0, targetS = 0))
        assertNull(TimedSetEndPolicy.remainingS(elapsedS = 0, targetS = -30))
    }

    /** The countdown the voice reads, one second at a time. */
    @Test
    fun `the remainder counts down from the prescription to zero`() {
        assertEquals(60, TimedSetEndPolicy.remainingS(elapsedS = 0, targetS = 60))
        assertEquals(1, TimedSetEndPolicy.remainingS(elapsedS = 59, targetS = 60))
        assertEquals(0, TimedSetEndPolicy.remainingS(elapsedS = 60, targetS = 60))
    }

    /**
     * The set ends on the second the target is reached and not the one before
     * it. Reds in either direction: `< 0` leaves the set running past its
     * target, `<= 1` ends it a second early and records a set one second short
     * of the word the lifter just heard.
     */
    @Test
    fun `the set ends on the second the target is reached and not before`() {
        assertFalse(TimedSetEndPolicy.endsNow(2))
        assertFalse(TimedSetEndPolicy.endsNow(1))
        assertTrue(TimedSetEndPolicy.endsNow(0))
    }

    /**
     * A missed tick must not sail past the target. The tick loop runs on
     * `delay(1_000)` in a process Android may pause; if a second is skipped
     * the set ends one tick late rather than never.
     */
    @Test
    fun `a remainder already past the target still ends the set`() {
        listOf(-1, -5, -60).forEach { assertTrue(TimedSetEndPolicy.endsNow(it), "$it") }
    }

    /**
     * The shortfall threshold, restated from the rule the set write already
     * applies. At a 10 s prescription the threshold is 9: nine seconds is
     * delivered, eight is short.
     *
     * Reds if [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] moves off 0.9, and
     * reds if the truncation to a whole second is dropped -- 0.9 * 10 is
     * 9.0 either way, so the boundary is asserted at both sides rather than
     * on the fraction.
     */
    @Test
    fun `a hold within a tenth of its prescription is not short`() {
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = 10, plannedS = 10))
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = 9, plannedS = 10))
        assertTrue(TimedSetEndPolicy.fellShort(recordedS = 8, plannedS = 10))
        assertTrue(TimedSetEndPolicy.fellShort(recordedS = 0, plannedS = 10))
    }

    /**
     * A hold longer than asked for is not short. Stated because the
     * post-set correction only ever moves the figure up, and a correction
     * that flipped a delivered set to failed would be worse than no
     * correction at all.
     */
    @Test
    fun `a hold longer than its prescription is not short`() {
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = 75, plannedS = 60))
    }

    /**
     * Nothing to judge is not a failure. An ad-hoc hold has no prescription
     * and a set with no measured seconds has no figure; neither is a set that
     * fell short, and reporting either as one would mark work failed that the
     * app simply cannot grade.
     */
    @Test
    fun `an absent prescription or an absent measurement is not a shortfall`() {
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = 12, plannedS = null))
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = null, plannedS = 60))
        assertFalse(TimedSetEndPolicy.fellShort(recordedS = null, plannedS = null))
    }
}
