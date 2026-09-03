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
    // Which samples the rest window holds. The differentials for #178.
    //
    // The two pins that stood here -- that no sample of the set ever reaches
    // the rest window -- stated the rule this replaces and are deleted, not
    // reworded.
    // ------------------------------------------------------------------

    /**
     * The rest window opens at the instant the rest runs from, so the tail of
     * the set's own capture is in it.
     *
     * Field-37 set 7 is the case this is shaped on: the set spoke `Done` and
     * went on recording for 53.06 s, and the rest-HR window began after all of
     * it while the countdown had already spent that time. The samples in that
     * tail are rest, and both documents must say so.
     */
    @Test
    fun `the samples after the set-over instant are the rest window's`() {
        val cue = 10_000L
        val samples = listOf(hr(9_000L), hr(cue), hr(40_000L), hr(63_060L))
        assertEquals(
            listOf(hr(cue), hr(40_000L), hr(63_060L)),
            RestClockPolicy.restWindowSeed(samples, startedAtMs = cue),
        )
    }

    /**
     * A sample stamped BEFORE that instant is the set's and stays out.
     *
     * The window cannot reach backwards into the work: heart rate under load
     * is a different population from heart rate at rest, and one sample of the
     * former at the head of a rest window is what a recovery slope is fitted
     * from.
     */
    @Test
    fun `a sample from before the set was over is not in the rest window`() {
        assertEquals(
            emptyList(),
            RestClockPolicy.restWindowSeed(listOf(hr(9_999L)), startedAtMs = 10_000L),
        )
    }

    /**
     * The boundary is inclusive: a sample stamped exactly at the instant is
     * rest.
     *
     * Pinned so the choice is on the record. Either answer is defensible for
     * one sample; an unstated one is how the two readers drifted apart in the
     * first place.
     */
    @Test
    fun `a sample stamped exactly at the set-over instant is in the rest window`() {
        assertEquals(
            listOf(hr(10_000L)),
            RestClockPolicy.restWindowSeed(listOf(hr(10_000L)), startedAtMs = 10_000L),
        )
    }

    /**
     * A set nothing called over runs its rest from the write instant, so only
     * what arrived at or after that instant is in the window.
     *
     * These are field-37's ~0 rows -- the sets that ended on `Set ended` at
     * `endedAt_ms`, and the holds that ended on `Time` -- and the rule must
     * leave them where they already were rather than moving a set's capture
     * into its rest for a reason nothing measured.
     */
    @Test
    fun `a set nothing called over keeps its own samples`() {
        val samples = listOf(hr(26_500L), hr(27_003L))
        assertEquals(
            emptyList(),
            RestClockPolicy.restWindowSeed(samples, startedAtMs = 27_004L),
        )
    }

    /**
     * The seed keeps the capture's order.
     *
     * It is prepended to a buffer that goes on filling from the strap, and the
     * archive's CSV is written from that buffer in list order. A reordered
     * seed publishes a heart-rate stream whose timestamps do not ascend, which
     * every reader of it assumes.
     */
    @Test
    fun `the seed keeps the order the samples were captured in`() {
        val samples = listOf(hr(10_000L), hr(11_000L), hr(12_000L))
        assertEquals(
            listOf(10_000L, 11_000L, 12_000L),
            RestClockPolicy.restWindowSeed(samples, startedAtMs = 10_000L).map { it.timestampMs },
        )
    }

    /**
     * A set that captured nothing seeds nothing.
     *
     * An empty window is an absence and stays one: no sample is invented for a
     * strap that was not worn, because an empty rest-HR stream claims a window
     * was captured and was silent, which is a different fact.
     */
    @Test
    fun `a set with no capture seeds no rest window`() {
        assertEquals(emptyList(), RestClockPolicy.restWindowSeed(emptyList(), startedAtMs = 10_000L))
    }

    private fun hr(tMs: Long) = HrSample(timestampMs = tMs, bpm = 120)
}
