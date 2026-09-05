package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line under the guided set's ring, characterized exactly as `RecordScreen`
 * drew it before the expression was lifted out of the composable.
 *
 * Nothing here is a differential. Every string below is what a lifter sees
 * today, including the ones #252 exists to change: they are recorded so the
 * commit that changes them has to say which, and so the lift itself is pinned
 * as behaviour-preserving.
 */
class GuidedRepCaptionTest {
    private fun caption(
        finishedReps: Int,
        plannedReps: Int? = 12,
        leadIn: Boolean = false,
        finished: Boolean = false,
    ) = GuidedRepCaption.forRing(finishedReps, plannedReps, leadIn, finished)

    @Test
    fun `the ring counts finished reps, one behind the voice since 243`() {
        assertEquals("rep 6 of 12", caption(finishedReps = 6))
    }

    @Test
    fun `the prep reads as a rep count of zero`() {
        assertEquals("rep 0 of 12", caption(finishedReps = 0, leadIn = true))
    }

    @Test
    fun `a finished set reads as the count it ended on`() {
        assertEquals("rep 12 of 12", caption(finishedReps = 12, finished = true))
    }

    @Test
    fun `a set with no prescribed count leaves the total off`() {
        assertEquals("rep 4", caption(finishedReps = 4, plannedReps = null))
    }

    @Test
    fun `a zero typed into the ad-hoc rep box is printed as a total`() {
        assertEquals("rep 0 of 0", caption(finishedReps = 0, plannedReps = 0))
    }

    @Test
    fun `the phase the set is in changes nothing yet`() {
        val plain = caption(finishedReps = 3)
        assertEquals(plain, caption(finishedReps = 3, leadIn = true))
        assertEquals(plain, caption(finishedReps = 3, finished = true))
        assertEquals(plain, caption(finishedReps = 3, leadIn = true, finished = true))
    }
}
