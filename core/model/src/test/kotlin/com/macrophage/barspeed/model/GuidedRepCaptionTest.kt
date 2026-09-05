package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The line under the guided set's ring (#252).
 *
 * It counted FINISHED reps, which agreed with the voice until #243 moved every
 * numbered call onto the rep in hand. Since then the ring read one less than
 * the voice said for the whole of every guided set, and the lifter can see and
 * hear both at once. The characterization of the old wording that stood here
 * was replaced by these differentials rather than kept beside them: the old
 * strings are what this change exists to stop drawing, and a pin holding them
 * would have to be deleted by the fix.
 *
 * The number is pinned equal to `CadencePlan.announcementFor`'s in
 * `:core:dsp`'s `RingVoiceAgreementTest`; what is pinned HERE is the wording,
 * the three phases and the counts a text box can produce.
 */
class GuidedRepCaptionTest {
    private fun caption(
        finishedReps: Int,
        plannedReps: Int? = 12,
        leadIn: Boolean = false,
        finished: Boolean = false,
    ) = GuidedRepCaption.forRing(finishedReps, plannedReps, leadIn, finished)

    @Test
    fun `the ring names the rep in hand, not the one just finished`() {
        assertEquals("rep 7 of 12", caption(finishedReps = 6))
    }

    @Test
    fun `the first rep is named before it is finished`() {
        assertEquals("rep 1 of 12", caption(finishedReps = 0))
    }

    @Test
    fun `the last rep is named the way the voice names it`() {
        assertEquals("last rep of 12", caption(finishedReps = 11))
    }

    @Test
    fun `a one-rep set is all last rep`() {
        assertEquals("last rep of 1", caption(finishedReps = 0, plannedReps = 1))
    }

    @Test
    fun `the prep says how many are coming rather than claiming one is in hand`() {
        assertEquals("12 reps to come", caption(finishedReps = 0, leadIn = true))
    }

    @Test
    fun `the prep says nothing about reps when no count was asked for`() {
        assertNull(caption(finishedReps = 0, plannedReps = null, leadIn = true))
    }

    @Test
    fun `a finished set names what was done and no rep in hand`() {
        assertEquals("12 of 12 done", caption(finishedReps = 12, finished = true))
    }

    @Test
    fun `a set finished short names what was actually done`() {
        assertEquals("6 of 12 done", caption(finishedReps = 6, finished = true))
    }

    @Test
    fun `finished beats the prep flag`() {
        assertEquals("3 of 12 done", caption(finishedReps = 3, leadIn = true, finished = true))
    }

    @Test
    fun `a set with no prescribed count still names the rep in hand`() {
        assertEquals("rep 5", caption(finishedReps = 4, plannedReps = null))
    }

    @Test
    fun `a finished set with no prescribed count names the total`() {
        assertEquals("5 done", caption(finishedReps = 5, plannedReps = null, finished = true))
    }

    @Test
    fun `a zero typed into the ad-hoc rep box is no count, not a count of zero`() {
        assertEquals("rep 1", caption(finishedReps = 0, plannedReps = 0))
        assertEquals("rep 1", caption(finishedReps = 0, plannedReps = -3))
    }

    @Test
    fun `no working caption ever names a rep already finished`() {
        for (finished in 0..19) {
            val line = caption(finishedReps = finished, plannedReps = 20)!!
            assertEquals(false, line == "rep $finished of 20", "named the finished rep at $finished")
        }
    }
}
