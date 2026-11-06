package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Which sets open the "What limited that set?" page by themselves, and which
 * get a one-line row instead (UX review 2026-11-06, rest F4).
 *
 * RIR 1-2 is the target, so nearly every working set is rated 8 or 9. Opening
 * a ten-row page on every one of them pushed the Up next card and FINISH below
 * the fold, on a set where the honest answer is that the plan stopped it. The
 * page now opens by itself only where something did limit the set: a failure,
 * or "Nothing left" (rpe 10). Rpe 7 to 9 get a collapsed row that costs nothing
 * to ignore and one tap to open.
 */
class SetLimiterCollapsedRowTest {
    private fun placement(
        failed: Boolean = false,
        rpe: Int? = null,
        limiter: SetLimiter? = null,
        dismissed: Boolean = false,
        expanded: Boolean = false,
    ) = SetLimiterPolicy.placement(
        failed = failed,
        rpe = rpe,
        limiter = limiter,
        dismissed = dismissed,
        changing = false,
        expanded = expanded,
    )

    @Test
    fun `an on-target set rated 7 to 9 gets the collapsed row, not the page`() {
        for (rpe in 7..9) {
            assertEquals(SetLimiterPagePlacement.COLLAPSED, placement(rpe = rpe), "rpe $rpe")
        }
    }

    @Test
    fun `nothing left opens the page by itself`() {
        assertEquals(SetLimiterPagePlacement.PROMPT, placement(rpe = 10))
    }

    @Test
    fun `a failed set opens the page by itself whatever it is rated`() {
        for (rpe in listOf(null, 4, 8, 10)) {
            assertEquals(SetLimiterPagePlacement.PROMPT, placement(failed = true, rpe = rpe), "rpe $rpe")
        }
    }

    @Test
    fun `tapping the collapsed row opens the page`() {
        assertEquals(SetLimiterPagePlacement.PROMPT, placement(rpe = 8, expanded = true))
    }

    @Test
    fun `a skipped or answered set draws neither the row nor the page`() {
        assertEquals(SetLimiterPagePlacement.NONE, placement(rpe = 8, dismissed = true))
        assertEquals(SetLimiterPagePlacement.NONE, placement(rpe = 8, limiter = SetLimiter.PACE))
        assertEquals(SetLimiterPagePlacement.NONE, placement(rpe = 8, dismissed = true, expanded = true))
    }

    @Test
    fun `a headroom set draws neither, even if something asked it to expand`() {
        assertEquals(SetLimiterPagePlacement.NONE, placement(rpe = 6))
        assertEquals(SetLimiterPagePlacement.NONE, placement(rpe = 6, expanded = true))
    }

    @Test
    fun `the collapsed row says what it is and that it is optional`() {
        assertEquals("What limited it? (optional)", SetLimiterPolicy.COLLAPSED_ROW)
    }

    /**
     * The Last set box no longer reads "Limited by: Not given" in amber on a
     * set nothing failed. Amber is the colour this screen keeps for something
     * the lifter still has to do, and on an on-target set there is nothing.
     */
    @Test
    fun `an unanswered on-target set carries no limiter clause in the last set box`() {
        for (rpe in 7..10) {
            val status =
                LastSetRecordPolicy.status(
                    ratedDescription = "2 reps left",
                    failed = false,
                    tappedFailed = false,
                    limiter = null,
                    limiterNote = null,
                    timed = false,
                    warmupDeclared = false,
                    warmupMark = null,
                )
            assertEquals("2 reps left", status, "rpe $rpe")
            assertFalse(status.contains(SetLimiterPolicy.NOT_GIVEN), "rpe $rpe")
        }
    }
}
