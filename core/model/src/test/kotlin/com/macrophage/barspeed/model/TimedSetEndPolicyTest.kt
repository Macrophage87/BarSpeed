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

    // ---- #168 differentials -------------------------------------------------

    /**
     * A hold that ran to its planned end records the planned duration exactly,
     * whatever the wall clock made of it.
     *
     * This is the one the whole change turns on, and it is not a rounding
     * nicety. The tick loop counts ticks; the recorded figure comes off
     * `System.currentTimeMillis()` deltas. `delay(1_000)` drifts positive, so
     * a sixty-tick hold measures 60 or 61 seconds depending on how the
     * scheduler felt, and 61 against a 60 s prescription is a hold that reads
     * as having been carried a second past target -- on every set, for a
     * reason that is a property of the coroutine dispatcher and not of the
     * lifter. Auto-end records what was announced.
     *
     * Also the drift the other way: a paused process can leave the wall clock
     * measurement SHORT of the ticks counted, and 17 recorded against a 20 s
     * hold that ran to its word is a set the app then grades as failed.
     */
    @Test
    fun `a hold that reached its target records the target and not the clock`() {
        assertEquals(60, TimedSetEndPolicy.recordedSeconds(measuredS = 61, targetS = 60, autoEnded = true))
        assertEquals(60, TimedSetEndPolicy.recordedSeconds(measuredS = 60, targetS = 60, autoEnded = true))
        assertEquals(20, TimedSetEndPolicy.recordedSeconds(measuredS = 17, targetS = 20, autoEnded = true))
    }

    /**
     * A hold the lifter stopped by hand records what it actually lasted, and
     * auto-end never rounds a short set up to its prescription.
     *
     * The owner's third requirement, stated as an assertion rather than
     * assumed from the fact that the auto-end branch is guarded. Green against
     * the seam, which returns the measurement in every case -- said out loud
     * because a pin that passes before the fix is not evidence for the fix,
     * and the mutation table in the next commit is what makes it one.
     */
    @Test
    fun `a hold stopped by hand records what it lasted and is never rounded up`() {
        assertEquals(30, TimedSetEndPolicy.recordedSeconds(measuredS = 30, targetS = 60, autoEnded = false))
        assertEquals(1, TimedSetEndPolicy.recordedSeconds(measuredS = 1, targetS = 60, autoEnded = false))
        assertEquals(0, TimedSetEndPolicy.recordedSeconds(measuredS = 0, targetS = 60, autoEnded = false))
    }

    /**
     * An ad-hoc hold has no prescription to record instead of the
     * measurement, so it records the measurement even if something claims it
     * auto-ended. Reds if the fix reads `autoEnded` without checking there is
     * a target behind it -- the crash-adjacent case, since substituting a
     * null target would have to invent a number.
     */
    @Test
    fun `a hold with no prescription records its measurement`() {
        assertEquals(83, TimedSetEndPolicy.recordedSeconds(measuredS = 83, targetS = null, autoEnded = true))
        assertEquals(83, TimedSetEndPolicy.recordedSeconds(measuredS = 83, targetS = null, autoEnded = false))
    }

    /**
     * The shortfall verdict a hold gets is computed from what it RECORDS, and
     * the two ends of that chain have to agree.
     *
     * #137's family: a hold stopped short flows into the same shortfall
     * handling a dynamic set gets, and a hold that ran to its word does not.
     * Chained through both functions rather than asserted on each, because
     * the defect this guards against is the join -- a correct cap and a
     * correct threshold, wired to different figures, marks every auto-ended
     * hold on a paused process as failed.
     */
    @Test
    fun `a hold that ran to its word is not judged short and one stopped early is`() {
        val ranToWord = TimedSetEndPolicy.recordedSeconds(measuredS = 17, targetS = 20, autoEnded = true)
        assertFalse(TimedSetEndPolicy.fellShort(ranToWord, plannedS = 20))
        val stoppedEarly = TimedSetEndPolicy.recordedSeconds(measuredS = 11, targetS = 20, autoEnded = false)
        assertTrue(TimedSetEndPolicy.fellShort(stoppedEarly, plannedS = 20))
    }

    /**
     * One tap of the post-set correction adds [TimedSetEndPolicy.CORRECTION_STEP_S]
     * seconds, and one the other way takes them off.
     *
     * The rest-screen control is where the genuine overage is entered, because
     * the owner does not look at the phone mid-set: *"There are rare instances
     * I even look at the phone mid set."* A mid-set affordance would be
     * exercised never.
     */
    @Test
    fun `one tap of the correction moves the recorded hold by the step`() {
        assertEquals(25, TimedSetEndPolicy.adjustedSeconds(currentS = 20, deltaS = 5))
        assertEquals(30, TimedSetEndPolicy.adjustedSeconds(currentS = 25, deltaS = 5))
        assertEquals(20, TimedSetEndPolicy.adjustedSeconds(currentS = 25, deltaS = -5))
    }

    /**
     * A correction cannot drive the recorded hold below zero.
     *
     * Negative seconds are not a hold that ran backwards; they are a figure
     * the export schema declares `"minimum": 0` for, and one that would make
     * every downstream comparison meaningless. The floor is zero, and zero
     * here is a measured zero -- the set happened and lasted no time worth
     * recording -- not an absence.
     */
    @Test
    fun `a correction cannot take the recorded hold below zero`() {
        assertEquals(0, TimedSetEndPolicy.adjustedSeconds(currentS = 3, deltaS = -5))
        assertEquals(0, TimedSetEndPolicy.adjustedSeconds(currentS = 0, deltaS = -5))
    }

    /**
     * The correction step is five seconds.
     *
     * Added because mutation M4 in the fix commit SURVIVED: every test above
     * passes 5 as a literal delta and none of them reads the constant, so
     * moving the step to 1 changed what the rest screen does and reddened
     * nothing. A literal, for the reason `TimedSetVoiceTest`'s KDoc gives --
     * asserting the constant against itself would pass for any value.
     *
     * Five and not one because the thing being corrected is the walk back to
     * the phone, which is seconds to tens of seconds; at one second a lifter
     * says with ten taps what one tap says here, on a rest screen with a
     * countdown running.
     */
    @Test
    fun `the correction step is five seconds`() {
        assertEquals(5, TimedSetEndPolicy.CORRECTION_STEP_S)
    }
}
