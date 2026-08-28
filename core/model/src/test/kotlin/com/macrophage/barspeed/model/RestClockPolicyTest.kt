package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rest clock: which instant it runs from, and what it says at a given
 * moment. Issue #172.
 *
 * At this commit [RestClockPolicy.remainingS] states TODAY'S rule -- the whole
 * period, whatever has already elapsed -- so the characterization below is
 * green and is meant to be. The differential that reds it lands next.
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
    // What the clock says. Today's rule.
    // ------------------------------------------------------------------

    /**
     * TODAY'S RULE, pinned so #172's change is a differential against a
     * statement rather than against a memory. The countdown is seeded with the
     * whole period however long the set has been over.
     *
     * This test is DELETED by the differential commit; it is here to make the
     * red mean something.
     */
    @Test
    fun `today the whole period is offered however long the set has been over`() {
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 1_000L, nowMs = 16_000L))
        assertEquals(150, RestClockPolicy.remainingS(restS = 150, startedAtMs = 1_000L, nowMs = 900_000L))
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
}
