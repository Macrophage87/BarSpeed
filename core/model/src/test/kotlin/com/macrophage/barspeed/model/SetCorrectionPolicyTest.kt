package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rating row a Correct-popup SAVE writes (#310).
 *
 * Green pins on a new symbol. The race this exists for lives in `:app`, and
 * its differentials are in `CountAndRatingOrderTest` there, pushed on their
 * own so the red is a durable artifact.
 */
class SetCorrectionPolicyTest {
    private fun draft(
        reps: Int? = null,
        seconds: Int? = null,
        rpe: Int? = null,
        tappedFailed: Boolean = false,
        ratingChanged: Boolean = true,
    ) = CountAndRatingDraft(reps, seconds, rpe, tappedFailed, ratingChanged)

    @Test
    fun `a failure the lifter states carries no rpe even when the draft still holds one`() {
        val row = SetCorrectionPolicy.row(draft(seconds = 30, rpe = 10, tappedFailed = true), shortfall = true)
        assertEquals(CorrectedRatingRow(rpe = null, failed = true, failedByLifter = true), row)
    }

    @Test
    fun `a stated failure fails the set with no shortfall behind it`() {
        val row = SetCorrectionPolicy.row(draft(tappedFailed = true), shortfall = false)
        assertEquals(CorrectedRatingRow(rpe = null, failed = true, failedByLifter = true), row)
    }

    @Test
    fun `a rung withdraws the tap and leaves the derived shortfall standing`() {
        val row = SetCorrectionPolicy.row(draft(rpe = 8, tappedFailed = false), shortfall = true)
        assertEquals(CorrectedRatingRow(rpe = 8, failed = true, failedByLifter = false), row)
    }

    @Test
    fun `a rung with no shortfall is not failed`() {
        val row = SetCorrectionPolicy.row(draft(rpe = 8), shortfall = false)
        assertEquals(CorrectedRatingRow(rpe = 8, failed = false, failedByLifter = false), row)
    }

    @Test
    fun `corrected seconds are judged by the set write's own boundary`() {
        // 35 s planned: TimedSetEndPolicy's close-enough fraction puts the
        // boundary at 31 s, so 30 is short and 31 is not.
        assertTrue(SetCorrectionPolicy.shortfall(draft(seconds = 30), null, 35, standing = false))
        assertFalse(SetCorrectionPolicy.shortfall(draft(seconds = 31), null, 35, standing = true))
    }

    @Test
    fun `corrected reps are short only of a planned count`() {
        assertTrue(SetCorrectionPolicy.shortfall(draft(reps = 4), 5, null, standing = false))
        assertFalse(SetCorrectionPolicy.shortfall(draft(reps = 5), 5, null, standing = true))
        assertFalse(SetCorrectionPolicy.shortfall(draft(reps = 1), null, null, standing = true))
    }

    @Test
    fun `a draft that moves no count leaves the standing verdict`() {
        assertTrue(SetCorrectionPolicy.shortfall(draft(rpe = 8), 5, 35, standing = true))
        assertFalse(SetCorrectionPolicy.shortfall(draft(rpe = 8), 5, 35, standing = false))
    }

    @Test
    fun `a draft changes something only where it carries a count or a new rating`() {
        assertFalse(draft(ratingChanged = false).changesAnything)
        assertTrue(draft(reps = 4, ratingChanged = false).changesAnything)
        assertTrue(draft(seconds = 30, ratingChanged = false).changesAnything)
        assertTrue(draft(ratingChanged = true).changesAnything)
    }
}
