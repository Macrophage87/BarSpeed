package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failure DERIVED at the write clears a stored rating exactly as a failure
 * the lifter stated does (#313).
 *
 * The owner's rule: "Don't ask for an RPE on failed sets, if you can't do it,
 * it's failed." #310 made the Correct popup's SAVE store no rpe beside a
 * failure the lifter tapped. The derived half kept its rating: rate a hold,
 * then correct its seconds below the close-enough fraction of the target, and
 * the row carried an rpe beside a failure nobody tapped.
 *
 * The numbers are field-45 set 13's, a rope dead hang planned at 35 s and
 * restated at 30: `TimedSetEndPolicy.fellShort(30, 35)` is true, because 30 is
 * under 31, the whole seconds of 90 % of 35. 31 of 35 is not short.
 */
class FailedSetRatingPolicyTest {
    private fun hold(seconds: Int, rpe: Int?, tappedFailed: Boolean = false) = CountAndRatingDraft(
        reps = null,
        seconds = seconds,
        rpe = rpe,
        tappedFailed = tappedFailed,
        ratingChanged = false,
    )

    /** The derived shortfall of [draft] against field-45 set 13's 35 s plan. */
    private fun shortOf35(draft: CountAndRatingDraft) =
        SetCorrectionPolicy.shortfall(draft, plannedReps = null, plannedDurationS = 35, standing = false)

    private fun count(reps: Int, rpe: Int?) =
        CountAndRatingDraft(reps = reps, seconds = null, rpe = rpe, tappedFailed = false, ratingChanged = false)

    @Test
    fun `a derived shortfall stores no rpe`() {
        assertNull(FailedSetRatingPolicy.storedRpe(8, failedByLifter = false, shortfall = true))
    }

    @Test
    fun `a rated hold corrected below 90 percent keeps no rpe, and the failure stays the app's`() {
        val draft = hold(seconds = 30, rpe = 8)
        val shortfall = shortOf35(draft)
        assertTrue(shortfall, "30 of 35 is short: the premise of this pin")
        assertEquals(
            CorrectedRatingRow(rpe = null, failed = true, failedByLifter = false),
            SetCorrectionPolicy.row(draft, shortfall),
            "the rating stood beside a failure the app derived",
        )
    }

    @Test
    fun `a rated count corrected short of the plan keeps no rpe`() {
        val draft = count(reps = 4, rpe = 9)
        val shortfall = SetCorrectionPolicy.shortfall(draft, plannedReps = 5, plannedDurationS = null, standing = false)
        assertEquals(
            CorrectedRatingRow(rpe = null, failed = true, failedByLifter = false),
            SetCorrectionPolicy.row(draft, shortfall),
        )
    }

    // ---- what must not move ------------------------------------------------

    @Test
    fun `a set that did not fail keeps its rating`() {
        assertEquals(8, FailedSetRatingPolicy.storedRpe(8, failedByLifter = false, shortfall = false))
        val draft = hold(seconds = 31, rpe = 8)
        val shortfall = shortOf35(draft)
        assertEquals(
            CorrectedRatingRow(rpe = 8, failed = false, failedByLifter = false),
            SetCorrectionPolicy.row(draft, shortfall),
            "31 of 35 is not short and keeps its 8",
        )
    }

    @Test
    fun `a failure the lifter stated still stores no rpe and still says it was theirs`() {
        assertNull(FailedSetRatingPolicy.storedRpe(10, failedByLifter = true, shortfall = false))
        assertEquals(
            CorrectedRatingRow(rpe = null, failed = true, failedByLifter = true),
            SetCorrectionPolicy.row(hold(seconds = 35, rpe = 10, tappedFailed = true), shortfall = false),
        )
    }

    @Test
    fun `no rating is invented where none was given`() {
        for (failedByLifter in listOf(false, true)) {
            for (shortfall in listOf(false, true)) {
                assertNull(FailedSetRatingPolicy.storedRpe(null, failedByLifter, shortfall))
            }
        }
    }
}
