package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the reason page opens, and what the rest screen reads afterwards (#189). */
class SetLimiterPolicyTest {
    @Test
    fun `a failed set with no reason yet is asked`() {
        assertTrue(SetLimiterPolicy.prompts(failed = true, rpe = null, limiter = null, dismissed = false))
    }

    /**
     * An UNRATED set that did not fail is not asked.
     *
     * Renamed, because the name it carried -- `a set that did not fail is not
     * asked` -- became false at e1c2601c8084e9d9f38dc97375f4aa474541723c: a
     * completed set rated at the counted end is asked. The assertion is
     * unchanged and still holds, and SetLimiterCompletedAskTest is where the
     * widened rule is pinned.
     */
    @Test
    fun `an unrated set that did not fail is not asked`() {
        assertFalse(SetLimiterPolicy.prompts(failed = false, rpe = null, limiter = null, dismissed = false))
    }

    /**
     * A skip stays skipped.
     *
     * Absence is a state the lifter is allowed to choose, and a page that
     * reopens after being dismissed is a page that must be answered.
     */
    @Test
    fun `a dismissed page does not reopen`() {
        assertFalse(SetLimiterPolicy.prompts(failed = true, rpe = null, limiter = null, dismissed = true))
    }

    @Test
    fun `a set already carrying a reason is not asked again`() {
        assertFalse(SetLimiterPolicy.prompts(failed = true, rpe = null, limiter = SetLimiter.GRIP, dismissed = false))
    }

    /**
     * The correction stays reachable after a skip, so a skip is not a door
     * that locks.
     */
    @Test
    fun `the correction row is offered on any failed set and on any set carrying an answer`() {
        assertTrue(SetLimiterPolicy.offersCorrection(failed = true, rpe = null, limiter = null))
        assertTrue(SetLimiterPolicy.offersCorrection(failed = true, rpe = null, limiter = SetLimiter.PAIN))
        assertTrue(SetLimiterPolicy.offersCorrection(failed = false, rpe = null, limiter = SetLimiter.PAIN))
        assertFalse(SetLimiterPolicy.offersCorrection(failed = false, rpe = null, limiter = null))
    }

    @Test
    fun `a set with no reason reads as a named absence`() {
        assertEquals(
            SetLimiterPolicy.NOT_GIVEN,
            SetLimiterPolicy.lineText(limiter = null, note = "ignored", timed = false, failed = true),
        )
    }

    @Test
    fun `a listed answer reads with its own wording, and a hold reads the hold's`() {
        assertEquals("Grip gave out", SetLimiterPolicy.lineText(SetLimiter.GRIP, null, timed = false, failed = true))
        assertEquals("Muscle failure", SetLimiterPolicy.lineText(SetLimiter.MUSCLE, null, timed = false, failed = true))
        assertEquals(
            "Could not hold it any longer",
            SetLimiterPolicy.lineText(SetLimiter.MUSCLE, null, timed = true, failed = true),
        )
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
        assertEquals("Slipped", SetLimiterPolicy.lineText(SetLimiter.SLIP, "bar rolled", timed = false, failed = true))
    }

    @Test
    fun `other reads as the lifter's own words, normalized`() {
        assertEquals(
            "dog needed out",
            SetLimiterPolicy.lineText(SetLimiter.OTHER, "  dog\nneeded out ", timed = false, failed = true),
        )
    }

    /**
     * Other with no words falls back to the tile's own wording.
     *
     * An empty quotation would read as the app having lost the note.
     */
    @Test
    fun `other with no words reads as the tile's wording`() {
        assertEquals("Other", SetLimiterPolicy.lineText(SetLimiter.OTHER, "   ", timed = false, failed = true))
        assertEquals("Other", SetLimiterPolicy.lineText(SetLimiter.OTHER, null, timed = false, failed = true))
    }

    /** Nothing to ask and nothing tapped: the page is not drawn at all. */
    @Test
    fun `a set with nothing to ask draws the page nowhere`() {
        assertEquals(
            SetLimiterPagePlacement.NONE,
            SetLimiterPolicy.placement(failed = false, rpe = null, limiter = null, dismissed = false, changing = false),
        )
    }

    /** A skip leaves the page closed; the row is what stays reachable. */
    @Test
    fun `a dismissed page is drawn nowhere`() {
        assertEquals(
            SetLimiterPagePlacement.NONE,
            SetLimiterPolicy.placement(failed = true, rpe = null, limiter = null, dismissed = true, changing = false),
        )
    }

    /**
     * A page the lifter opened is drawn where they opened it.
     *
     * The reason row WAS at the foot of the rest screen beside the effort
     * line, and a page that answered a tap somewhere else is a page the
     * tapping finger cannot see. #237 deleted the row.
     */
    @Test
    fun `the lifter's own tap draws the page under the row they tapped`() {
        assertEquals(
            SetLimiterPagePlacement.CORRECTION,
            SetLimiterPolicy.placement(
                failed = true,
                rpe = null,
                limiter = SetLimiter.GRIP,
                dismissed = true,
                changing = true,
            ),
        )
    }

    /**
     * The lifter's tap WINS over an automatic offer, and that is what stops
     * the page being drawn twice on one screen.
     */
    @Test
    fun `a tap on an unanswered set still draws the page under the row`() {
        assertEquals(
            SetLimiterPagePlacement.CORRECTION,
            SetLimiterPolicy.placement(failed = true, rpe = null, limiter = null, dismissed = false, changing = true),
        )
    }

    /** No answer stands, so the way out of the page is a skip. */
    @Test
    fun `a page with no answer behind it leaves as a skip`() {
        assertTrue(SetLimiterPolicy.leavesPageAsSkip(null))
    }

    /**
     * An answer stands, so the way out is not a skip.
     *
     * Skipping an answered set records nothing and clears nothing: the answer
     * stays in the row and stays in the export. A foot captioned "records no
     * reason" over a stored answer describes an action the app does not
     * perform, and a lifter who tapped it to retract a mark has had the mark
     * survive them.
     */
    @Test
    fun `a page with an answer behind it does not leave as a skip`() {
        assertFalse(SetLimiterPolicy.leavesPageAsSkip(SetLimiter.GRIP))
        assertFalse(SetLimiterPolicy.leavesPageAsSkip(SetLimiter.OTHER))
    }

    /**
     * A page the app opened by ITSELF goes where the lifter is looking. This
     * is the differential.
     *
     * The rest screen scrolls to 0 on entering RESTING and the reason row
     * CAME after the header, the permission banner, the whole next-set
     * block and the session-close controls. A question drawn there is a
     * question the lifter starts the next set without ever seeing, and
     * starting the next set resets the stored answer to null, so it is not
     * merely unasked: it becomes unaskable.
     *
     * PROMPT and CORRECTION are two places for one page, which is why this is
     * a placement and not a boolean.
     */
    @Test
    fun `a page the app opened by itself is drawn where the lifter is looking`() {
        assertEquals(
            SetLimiterPagePlacement.PROMPT,
            SetLimiterPolicy.placement(failed = true, rpe = null, limiter = null, dismissed = false, changing = false),
        )
    }
}
