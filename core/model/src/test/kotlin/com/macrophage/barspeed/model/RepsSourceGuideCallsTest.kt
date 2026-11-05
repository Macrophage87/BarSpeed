package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whose count a GUIDED set's `reps` is, once the guide's own count is known
 * beside the recorded one (#336).
 *
 * The guide's count is the length of the set's archived rep-mark stream: on a
 * guided set `GuidedCadenceRunner.onRepCounted` writes one mark per rep the
 * guide calls, and a `+1 REP` tap does nothing there (`CountingPolicy
 * .tapMeaning`). The exporter counts the stream; what is pinned here is the
 * word that count leads to.
 */
class RepsSourceGuideCallsTest {
    private fun guided(recordedReps: Int, guideCalls: Int?) = RepsSourcePolicy.published(
        liveReps = null,
        repsManual = true,
        timed = false,
        guideCounted = true,
        recordedReps = recordedReps,
        guideCalls = guideCalls,
    )

    /** The guide called the set and nobody disagreed: the metronome's count. */
    @Test
    fun `a guided set recorded at the guide's own count is the metronome's`() {
        assertEquals(RepsSource.METRONOME, guided(recordedReps = 12, guideCalls = 12))
    }

    /**
     * No rep-mark stream is stored -- a row recorded before marks were
     * archived, or a set whose journal never opened -- so nothing says what the
     * guide called, and the row reads as it always has. The collapse is stated
     * rather than hidden.
     */
    @Test
    fun `a guided row with no stored marks reads as the metronome's`() {
        assertEquals(RepsSource.METRONOME, guided(recordedReps = 8, guideCalls = null))
    }

    /**
     * Marks on a set the LIFTER counted are their own taps, not a guide's
     * calls, so a recorded count that differs from them is still the
     * lifter's statement.
     */
    @Test
    fun `marks on a hand-counted set are not a guide's count`() {
        assertEquals(
            RepsSource.MANUAL,
            RepsSourcePolicy.published(null, repsManual = true, timed = false, guideCounted = false, 7, 6),
        )
    }

    /** A sensor set is settled by its live count; marks do not enter it. */
    @Test
    fun `a sensor set is settled by its live count whatever marks it carries`() {
        assertEquals(RepsSource.SENSOR, RepsSourcePolicy.published(5, false, false, guideCounted = false, 5, 3))
        assertEquals(RepsSource.CORRECTED, RepsSourcePolicy.published(5, true, false, guideCounted = false, 6, 3))
    }

    /** A timed set publishes no word, guide or not. */
    @Test
    fun `a timed set publishes no word whatever the guide called`() {
        assertNull(RepsSourcePolicy.published(null, true, timed = true, guideCounted = true, 0, 4))
    }

    /** Where the guide's count is unknown, the answer is exactly the four-input form's. */
    @Test
    fun `with no guide count the answer is the four-input form's on every row`() {
        for (liveReps in listOf(null, 0, 5)) {
            for (repsManual in listOf(false, true)) {
                for (timed in listOf(false, true)) {
                    for (guideCounted in listOf(false, true)) {
                        assertEquals(
                            RepsSourcePolicy.published(liveReps, repsManual, timed, guideCounted),
                            RepsSourcePolicy.published(liveReps, repsManual, timed, guideCounted, 8, null),
                        )
                    }
                }
            }
        }
    }
}
