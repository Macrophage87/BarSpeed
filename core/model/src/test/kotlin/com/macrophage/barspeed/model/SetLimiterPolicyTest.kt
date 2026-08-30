package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the reason page opens, and what the rest screen reads afterwards (#189). */
class SetLimiterPolicyTest {
    @Test
    fun `a failed set with no reason yet is asked`() {
        assertTrue(SetLimiterPolicy.prompts(failed = true, limiter = null, dismissed = false))
    }

    /**
     * A set that did not fail is not asked.
     *
     * #191 widens the question to completed sets; until it does, asking on
     * every set is a tap on every set, paid mid-session by the only field
     * tester there is.
     */
    @Test
    fun `a set that did not fail is not asked`() {
        assertFalse(SetLimiterPolicy.prompts(failed = false, limiter = null, dismissed = false))
    }

    /**
     * A skip stays skipped.
     *
     * Absence is a state the lifter is allowed to choose, and a page that
     * reopens after being dismissed is a page that must be answered.
     */
    @Test
    fun `a dismissed page does not reopen`() {
        assertFalse(SetLimiterPolicy.prompts(failed = true, limiter = null, dismissed = true))
    }

    @Test
    fun `a set already carrying a reason is not asked again`() {
        assertFalse(SetLimiterPolicy.prompts(failed = true, limiter = SetLimiter.GRIP, dismissed = false))
    }

    /**
     * The row stays reachable after a skip, so a skip is not a door that
     * locks.
     */
    @Test
    fun `the correction row is offered on any failed set and on any set carrying an answer`() {
        assertTrue(SetLimiterPolicy.offersCorrection(failed = true, limiter = null))
        assertTrue(SetLimiterPolicy.offersCorrection(failed = true, limiter = SetLimiter.PAIN))
        assertTrue(SetLimiterPolicy.offersCorrection(failed = false, limiter = SetLimiter.PAIN))
        assertFalse(SetLimiterPolicy.offersCorrection(failed = false, limiter = null))
    }

    @Test
    fun `a set with no reason reads as a named absence`() {
        assertEquals(
            SetLimiterPolicy.NOT_GIVEN,
            SetLimiterPolicy.lineText(limiter = null, note = "ignored", timed = false),
        )
    }

    @Test
    fun `a listed answer reads with its own wording, and a hold reads the hold's`() {
        assertEquals("Grip gave out", SetLimiterPolicy.lineText(SetLimiter.GRIP, null, timed = false))
        assertEquals("Muscle failure", SetLimiterPolicy.lineText(SetLimiter.MUSCLE, null, timed = false))
        assertEquals("Could not hold it any longer", SetLimiterPolicy.lineText(SetLimiter.MUSCLE, null, timed = true))
    }

    /**
     * A note attached to a listed answer is not what the line reads.
     *
     * Only `other` has words worth quoting; a note beside "grip gave out"
     * would replace the answer a coach groups by with prose that cannot be
     * grouped.
     */
    @Test
    fun `a note beside a listed answer does not replace the answer`() {
        assertEquals("Slipped", SetLimiterPolicy.lineText(SetLimiter.SLIP, "bar rolled", timed = false))
    }

    @Test
    fun `other reads as the lifter's own words, normalized`() {
        assertEquals("dog needed out", SetLimiterPolicy.lineText(SetLimiter.OTHER, "  dog\nneeded out ", timed = false))
    }

    /**
     * Other with no words falls back to the tile's own wording.
     *
     * An empty quotation would read as the app having lost the note.
     */
    @Test
    fun `other with no words reads as the tile's wording`() {
        assertEquals("Other", SetLimiterPolicy.lineText(SetLimiter.OTHER, "   ", timed = false))
        assertEquals("Other", SetLimiterPolicy.lineText(SetLimiter.OTHER, null, timed = false))
    }
}
