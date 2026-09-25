package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The derived shortfall is judged against the WORKING target (#157).
 *
 * Green pins on today's behaviour, lifted out of `RecordViewModel.endSet`'s
 * `stoppedEarly`. Nothing here is red: the lift preserves behaviour, and each
 * pin's strength is shown by a mutation run, not by a red.
 *
 * The plan's figure is not an argument of [SetShortfallPolicy]; where a test
 * names one, it is only to say which case the numbers are. The rep numbers are
 * field-45's (#157): sets 2, 3 and 12 ran raised targets of 10, 11 and 12
 * against plans of 8, 8 and 10, and are not failed; set 8 was lowered from
 * 10 to 6 and the guide said `Done` at 6. The hold numbers are field-45 set
 * 13's, planned 35 s: 90 % of 35 is 31.5, whose whole seconds are 31, so 30
 * is short and 31 is not.
 */
class SetShortfallPolicyTest {
    private fun reps(stated: Int?, working: Int?) =
        SetShortfallPolicy.atWrite(
            timed = false,
            recordedS = null,
            workingDurationS = null,
            statedReps = stated,
            workingReps = working,
        )

    private fun hold(recordedS: Int?, workingS: Int?) =
        SetShortfallPolicy.atWrite(
            timed = true,
            recordedS = recordedS,
            workingDurationS = workingS,
            statedReps = null,
            workingReps = null,
        )

    @Test
    fun `a set that met a raised working target met it`() {
        // Plans of 8, 8 and 10: none of these is judged against them.
        assertFalse(reps(stated = 10, working = 10), "set 2: raised 8 -> 10, ran to 10")
        assertFalse(reps(stated = 11, working = 11), "set 3: raised 8 -> 11, ran to 11")
        assertFalse(reps(stated = 12, working = 12), "set 12: raised 10 -> 12, ran to 12")
    }

    @Test
    fun `a raised working target missed is short even where the count beats the plan`() {
        // Plan 8, raised to 10, did 9: one over the plan, one under the target.
        // The owner: "if you can't do it, it's failed."
        assertTrue(reps(stated = 9, working = 10))
    }

    /**
     * The owner's ruling, 2026-09-25: "It's completed even if the target is
     * lowered, just note the discrepancy." Field-45 set 8, plan 10, lowered
     * to 6, did 6. The discrepancy is not this policy's to judge.
     */
    @Test
    fun `a set met at a lowered working target is completed`() {
        assertFalse(reps(stated = 6, working = 6))
    }

    @Test
    fun `a stated count one under the working target is short and at it is not`() {
        assertTrue(reps(stated = 5, working = 6))
        assertFalse(reps(stated = 6, working = 6))
        assertFalse(reps(stated = 7, working = 6))
    }

    @Test
    fun `a sensor-counted set with no stated count is never judged short`() {
        // A straight-reps set the sensor counted and the lifter did not correct.
        assertFalse(reps(stated = null, working = 10))
    }

    @Test
    fun `a set with no rep target cannot fall short of one`() {
        assertFalse(reps(stated = 1, working = null))
        assertFalse(reps(stated = null, working = null))
    }

    @Test
    fun `a hold is judged against its working seconds with the close-enough fraction`() {
        assertTrue(hold(recordedS = 30, workingS = 35), "30 of 35 is short")
        assertFalse(hold(recordedS = 31, workingS = 35), "31 of 35 is close enough")
        assertFalse(hold(recordedS = 35, workingS = 35), "35 of 35 met it")
    }

    @Test
    fun `a hold met at a lowered working target is completed`() {
        // Planned 45, lowered to 30, held 30.
        assertFalse(hold(recordedS = 30, workingS = 30))
    }

    @Test
    fun `a hold with no recorded seconds is not short`() {
        assertFalse(hold(recordedS = null, workingS = 35))
    }

    @Test
    fun `a timed set is judged on its clock and never on a stated count`() {
        val short = SetShortfallPolicy.atWrite(
            timed = true,
            recordedS = 35,
            workingDurationS = 35,
            statedReps = 1,
            workingReps = 10,
        )
        assertFalse(short, "the hold met its seconds; the rep figures are not asked")
    }

    @Test
    fun `seconds are not judged on a set that is not timed`() {
        val short = SetShortfallPolicy.atWrite(
            timed = false,
            recordedS = 10,
            workingDurationS = 35,
            statedReps = null,
            workingReps = null,
        )
        assertFalse(short)
    }

    @Test
    fun `a timed set with no hold target falls through to the rep rule, as the write always did`() {
        val figures = listOf(4 to true, 5 to false)
        figures.forEach { (stated, expected) ->
            val short = SetShortfallPolicy.atWrite(
                timed = true,
                recordedS = 3,
                workingDurationS = null,
                statedReps = stated,
                workingReps = 5,
            )
            assertEquals(expected, short, "stated $stated of 5")
        }
    }

    @Test
    fun `the correction and the write draw the same boundaries`() {
        fun corrected(reps: Int? = null, seconds: Int? = null, workingReps: Int? = null, workingS: Int? = null) =
            SetCorrectionPolicy.shortfall(
                CountAndRatingDraft(reps, seconds, rpe = null, tappedFailed = false, ratingChanged = false),
                plannedReps = workingReps,
                plannedDurationS = workingS,
                standing = false,
            )
        listOf(9 to 10, 10 to 10, 6 to 6, 5 to 6).forEach { (stated, working) ->
            assertEquals(
                reps(stated, working),
                corrected(reps = stated, workingReps = working),
                "$stated of $working reps",
            )
        }
        listOf(30 to 35, 31 to 35, 30 to 30).forEach { (recorded, working) ->
            assertEquals(
                hold(recorded, working),
                corrected(seconds = recorded, workingS = working),
                "$recorded of $working s",
            )
        }
    }
}
