package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rest after a hold the sensor ended runs from the release, not from the
 * tap. Issue #259 on top of #172, and RED at the commit that adds it.
 *
 * ## Why this is not a separate decision from the duration
 *
 * #178 is the incident: the countdown and the rest-HR window were seeded from
 * two different instants and the archive could not be joined, because neither
 * document said which it had used. The same failure is available here. Once a
 * hold's recorded seconds run to the release, a rest still counted from the tap
 * means the app holds two answers to when the set ended -- and the gap between
 * them is the 5-10 s reach this change exists to remove.
 *
 * ## What a hold actually has to offer
 *
 * When this pin was written, nothing: `Time` was not a terminal cue, so every
 * hold fell back to the write instant. Since #295 a hold that ran to its
 * target rests from `Time` -- on field-42 set 16, 1788776841087 against a
 * write at 1788776841088 -- and a hold broken before it has no `Time` to
 * offer. On a hold ended by hand the fallback costs the whole reach, which is
 * what the release instant below removes.
 *
 * The instants below are field-38 set 17's own: clock 1788517883914, release
 * 1788517913103, write 1788517920142.
 */
class RestFromSensorEndDifferentialTest {
    private companion object {
        const val CLOCK_STARTED_AT_MS = 1788517883914L
        const val RELEASE_AT_MS = 1788517913103L
        const val WRITE_AT_MS = 1788517920142L
    }

    @Test
    fun `a hold with a sensor end rests from the release`() {
        assertEquals(
            RELEASE_AT_MS,
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = null,
                sensorEndAtMs = RELEASE_AT_MS,
                endedAtMs = WRITE_AT_MS,
            ),
            "the rest still starts when the phone was reached, not when the hold ended",
        )
        // Seven seconds of a 120 s prescription, which is the size of what the
        // countdown was over-crediting on every hands-full hold.
        assertEquals(
            7L,
            (WRITE_AT_MS - RELEASE_AT_MS) / 1_000L,
            "the interval the rest clock gains",
        )
        assertEquals(
            113,
            RestClockPolicy.remainingS(
                restS = 120,
                startedAtMs = RestClockPolicy.startedAtMs(
                    setOverCueAtMs = null,
                    sensorEndAtMs = RELEASE_AT_MS,
                    endedAtMs = WRITE_AT_MS,
                ),
                nowMs = WRITE_AT_MS,
            ),
            "the rest screen opens on 113 of 120, not on the full period",
        )
    }

    @Test
    fun `a sensor end is preferred over the write instant and yields to nothing else on a hold`() {
        // The release is the instant the DURATION was measured to, so it is the
        // instant the rest must run from: one answer to when the set ended, for
        // both readers, which is #178's rule. A hold its clock ended carries
        // `Time`, terminal since #295, so a cue and a release can both be on
        // offer; the ordering below is deliberate rather than incidental.
        assertEquals(
            RELEASE_AT_MS,
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = WRITE_AT_MS,
                sensorEndAtMs = RELEASE_AT_MS,
                endedAtMs = WRITE_AT_MS,
            ),
            "a cue cannot outrank the instant the duration was measured to",
        )
        // And absence stays absence: with no release the rule is exactly #172's,
        // which this must not disturb.
        assertEquals(
            CLOCK_STARTED_AT_MS,
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = CLOCK_STARTED_AT_MS,
                sensorEndAtMs = null,
                endedAtMs = WRITE_AT_MS,
            ),
            "a set with a terminal cue and no release still rests from its cue",
        )
    }
}
