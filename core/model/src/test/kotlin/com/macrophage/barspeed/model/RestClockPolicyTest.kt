package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rest clock: which instant it runs from, and what it says at a given
 * moment. Issue #172.
 *
 * The differentials here were written red against the rule this issue
 * replaced -- the whole period whatever had already elapsed -- and CI run
 * 33221571539 holds that red at the differential commit. They are green from
 * the fix commit on.
 */
class RestClockPolicyTest {
    // ------------------------------------------------------------------
    // Which instant the period runs from.
    // ------------------------------------------------------------------

    /** The cue that called the set over is when the lifter stopped lifting. */
    @Test
    fun `the set-over cue is the instant the rest runs from`() {
        assertEquals(13_517L, RestClockPolicy.startedAtMs(setOverCueAtMs = 13_517L, endedAtMs = 27_004L))
    }

    /**
     * A set nothing called over -- a hold, an ad-hoc set with the voice off --
     * falls back to the instant the write froze.
     *
     * Absence is a state here and not a low number: a null must not be read as
     * an instant of zero, which would make every rest period appear to have
     * elapsed decades ago.
     */
    @Test
    fun `a set nothing called over runs from the instant the write froze`() {
        assertEquals(27_004L, RestClockPolicy.startedAtMs(setOverCueAtMs = null, endedAtMs = 27_004L))
    }

    /**
     * A cue stamped after the write is taken anyway.
     *
     * It cannot arise from the app -- the cue is written before the set ends,
     * on the same clock -- so it means the wall clock moved, and neither
     * figure is then more trustworthy than the other. Pinned so that the
     * choice is a decision on the record rather than an accident of operator
     * order, and because [RestClockPolicy.remainingS] floors the result either
     * way.
     */
    @Test
    fun `a cue stamped after the write is still the instant taken`() {
        assertEquals(99_000L, RestClockPolicy.startedAtMs(setOverCueAtMs = 99_000L, endedAtMs = 27_004L))
    }

    // ------------------------------------------------------------------
    // What the clock says. The differentials for #172.
    // ------------------------------------------------------------------

    /**
     * Nothing has elapsed, so the whole period is left.
     *
     * The one case today's rule and the fixed rule agree on, kept because it
     * is the case every set that goes straight to the rest screen lands in and
     * a subtraction that got its sign wrong would show it here.
     */
    @Test
    fun `an instant that has only just passed leaves the whole period`() {
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 1_000L, nowMs = 1_000L))
    }

    /**
     * The defect. Time between the set ending and the rest screen drawing is
     * rest that was taken, and it comes off the countdown.
     *
     * Fifteen seconds is the owner's own figure from the gym: "rate a set for
     * fifteen seconds today and the app gives fifteen seconds of rest it does
     * not know about, then counts a full period on top". 150 s is
     * DEFAULT_REST_S.
     */
    @Test
    fun `time already spent since the set ended comes off the countdown`() {
        assertEquals(135, RestClockPolicy.remainingS(restS = 150, startedAtMs = 1_000L, nowMs = 16_000L))
    }

    /**
     * The interval this actually costs on a guided set, from a capture rather
     * than from a guess.
     *
     * The eleven sets of session 32 carrying both a `Done` cue and an IMU
     * stream keep recording for 4.3 to 13.7 s past the cue, measured as last
     * sample minus cue and recorded in `SetEnd`'s own documentation. Both ends
     * of that range are asserted against a 90 s prescription, because a rule
     * that only holds at one arbitrary elapsed value is a rule fitted to its
     * test.
     */
    @Test
    fun `both ends of session 32's measured tail come off a 90 second rest`() {
        assertEquals(86, RestClockPolicy.remainingS(restS = 90, startedAtMs = 0L, nowMs = 4_300L), "the 4.3 s tail")
        assertEquals(77, RestClockPolicy.remainingS(restS = 90, startedAtMs = 0L, nowMs = 13_700L), "the 13.7 s tail")
    }

    /**
     * A rest that fully elapsed while the lifter was still on the set-end
     * screen shows zero, not a fresh full period.
     *
     * Floored rather than allowed to go negative: the countdown formats
     * mm:ss and a negative would render as a nonsense duration, and the
     * countdown loop reads `> 0` to decide whether to keep ticking at all.
     * The zero is a measured zero -- the rest happened and none of it is left
     * -- not an absence.
     */
    @Test
    fun `a rest that fully elapsed during the interaction is zero, not a full period`() {
        assertEquals(0, RestClockPolicy.remainingS(restS = 60, startedAtMs = 0L, nowMs = 60_000L), "exactly elapsed")
        assertEquals(0, RestClockPolicy.remainingS(restS = 60, startedAtMs = 0L, nowMs = 600_000L), "long elapsed")
    }

    /**
     * Whole elapsed seconds only, floored, the way SetClockPolicy measures a
     * hold.
     *
     * Not a nicety. Rounding up would take a second of rest away from every
     * set for no reason the lifter did anything about, and the countdown then
     * reaches zero before the period it names has actually run.
     */
    @Test
    fun `a part second of elapsed time is not a second of rest taken`() {
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 0L, nowMs = 999L), "just under one")
        assertEquals(149, RestClockPolicy.remainingS(restS = 150, startedAtMs = 0L, nowMs = 1_000L), "exactly one")
        assertEquals(149, RestClockPolicy.remainingS(restS = 150, startedAtMs = 0L, nowMs = 1_999L), "just under two")
    }

    /**
     * A clock that moved backwards cannot hand out more rest than was
     * prescribed.
     *
     * `System.currentTimeMillis()` is a wall clock: NTP, a timezone database
     * update or the lifter setting the time can move it either way between the
     * cue being stamped and the rest screen being built. A negative elapsed
     * would otherwise ADD to the countdown, and the ceiling is the same figure
     * the progress ring divides by -- a remainder above the total draws a ring
     * more than full.
     */
    @Test
    fun `a clock that went backwards cannot inflate the period`() {
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 60_000L, nowMs = 1_000L))
    }

    /**
     * A partly-elapsed period leaves exactly the seconds that remain, so the
     * countdown speaks only those.
     *
     * The cue half of #172, pinned at the only place a JVM test can reach it.
     * `:app` speaks a digit each second from REST_COUNTDOWN_FROM_S = 3 as the
     * counter ticks DOWN through it, so a period seeded at 2 can never utter
     * "3": the digits already gone are gone because the number they would have
     * been spoken at was never held. Nothing in :app is asserted here -- that
     * the loop obeys this seed is compile-gated only and was checked on the
     * bench.
     */
    @Test
    fun `a period with two seconds left is seeded at two, so the passed digits cannot be spoken`() {
        assertEquals(2, RestClockPolicy.remainingS(restS = 150, startedAtMs = 0L, nowMs = 148_000L))
    }

    /**
     * A prescription of zero or less is no rest, not a negative countdown.
     *
     * Survives #172: a floor is a floor whichever end of the period it is
     * applied at.
     */
    @Test
    fun `a non-positive prescription is no rest at all`() {
        assertEquals(0, RestClockPolicy.remainingS(restS = 0, startedAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(0, RestClockPolicy.remainingS(restS = -30, startedAtMs = 1_000L, nowMs = 1_000L))
    }

    // ------------------------------------------------------------------
    // Which samples the rest window holds. TODAY'S RULE, pinned for one
    // commit so #178's change can be a differential against it, and deleted
    // at that differential rather than reworded.
    // ------------------------------------------------------------------

    /**
     * The set's own capture is entirely the set's: nothing of it reaches the
     * rest window, however long the set went on recording after the cue that
     * called it over.
     *
     * Field-37 set 7 is the case: `Done` at the cue, 53.06 s of further
     * recording, and a rest-HR window that begins after all of it.
     */
    @Test
    fun `no sample of the set reaches the rest window`() {
        val samples = listOf(hr(10_000L), hr(63_060L), hr(63_400L))
        assertEquals(
            emptyList(),
            RestClockPolicy.restWindowSeed(samples, startedAtMs = 10_000L),
        )
    }

    /** Nor on a set whose terminal cue sits at the write instant. */
    @Test
    fun `no sample of a set that ended at its cue reaches the rest window either`() {
        assertEquals(
            emptyList(),
            RestClockPolicy.restWindowSeed(listOf(hr(27_004L)), startedAtMs = 27_004L),
        )
    }

    private fun hr(tMs: Long) = HrSample(timestampMs = tMs, bpm = 120)
}
