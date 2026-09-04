package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correcting the load of the set that has just been recorded (#205).
 *
 * Its own file rather than more of `SetLoadPolicyTest`, because these pins are
 * about a value being changed AFTER the row was written and the rest of that
 * file is about which load a set is recorded and pre-filled against before it
 * runs. The two share an object and not a question.
 *
 * Nothing here reaches the screen, the database or the ViewModel. What the row
 * draws, what it writes and where it draws it are `:app`'s, and no test in this
 * repo renders a composable; what is pinned here is every decision the control
 * makes, lifted out so that a decision has a test on it.
 */
class SetLoadCorrectionTest {
    @Test
    fun `one tap moves a plate's worth in kilograms`() {
        assertEquals(2.5, SetLoadPolicy.correctionStepKg(WeightUnit.KG))
    }

    @Test
    fun `one tap moves five pounds in pounds`() {
        // Asserted through the unit's own formatter rather than against a
        // constant, because what the step has to be is a number the lifter can
        // put on the bar in the unit they read -- 2.5 kg renders as "5.5 lb".
        assertEquals("5 lb", WeightUnit.LB.format(SetLoadPolicy.correctionStepKg(WeightUnit.LB)))
    }

    @Test
    fun `a loaded set takes the delta`() {
        assertEquals(62.5, SetLoadPolicy.correctedAddedKg(60.0, 2.5, bodyweight = false))
    }

    @Test
    fun `a loaded set cannot be corrected below an empty bar`() {
        assertEquals(0.0, SetLoadPolicy.correctedAddedKg(1.0, -2.5, bodyweight = false))
    }

    @Test
    fun `a loaded set already at nothing stays at nothing`() {
        assertEquals(0.0, SetLoadPolicy.correctedAddedKg(0.0, -2.5, bodyweight = false))
    }

    @Test
    fun `a body-weight set may be corrected below zero for assistance`() {
        assertEquals(-2.5, SetLoadPolicy.correctedAddedKg(0.0, -2.5, bodyweight = true))
    }

    @Test
    fun `assistance deepens with no floor under it`() {
        assertEquals(-52.5, SetLoadPolicy.correctedAddedKg(-50.0, -2.5, bodyweight = true))
    }

    @Test
    fun `a body-weight set corrected upward gains the plate`() {
        assertEquals(12.5, SetLoadPolicy.correctedAddedKg(10.0, 2.5, bodyweight = true))
    }

    @Test
    fun `a loaded set's stored total is its added load`() {
        assertEquals(65.0, SetLoadPolicy.correctedTotalKg(60.0, 60.0, 65.0))
    }

    @Test
    fun `a body-weight total moves by the correction and not by the body weight`() {
        // 80 kg lifter, 10 kg on the belt, corrected to 12.5. The body-weight
        // term is recovered from the pair the write already stored rather than
        // read again, so a body weight edited between the set and the
        // correction cannot silently move the recorded load with it.
        assertEquals(92.5, SetLoadPolicy.correctedTotalKg(90.0, 10.0, 12.5))
    }

    @Test
    fun `an assisted total rises as the assistance is reduced`() {
        assertEquals(35.0, SetLoadPolicy.correctedTotalKg(30.0, -50.0, -45.0))
    }

    @Test
    fun `a correction to the same number leaves the total where it was`() {
        assertEquals(90.0, SetLoadPolicy.correctedTotalKg(90.0, 10.0, 10.0))
    }

    @Test
    fun `nothing stands for the coming set so nothing follows`() {
        assertFalse(SetLoadPolicy.carryFollowsCorrection(null, 60.0, WeightUnit.KG, sameExerciseBlock = true))
    }

    @Test
    fun `the carry follows when the coming set stands at the number being corrected`() {
        assertTrue(SetLoadPolicy.carryFollowsCorrection(60.0, 60.0, WeightUnit.KG, sameExerciseBlock = true))
    }

    @Test
    fun `the carry does not follow a different prescription`() {
        assertFalse(SetLoadPolicy.carryFollowsCorrection(80.0, 60.0, WeightUnit.KG, sameExerciseBlock = true))
    }

    @Test
    fun `the carry follows through the load box's rounding`() {
        // The box quantises to 0.1 of the DISPLAY unit, so what is standing
        // for the coming set is a rounded copy of what was recorded and
        // compares unequal to it as a Double. #45 is the same rounding, and
        // an exact comparison here would drop the carry on every pound-unit
        // session.
        val recorded = 60.0
        val standing = WeightUnit.LB.parseToKg(WeightUnit.LB.inputValue(recorded))!!
        assertTrue(standing != recorded)
        assertTrue(SetLoadPolicy.carryFollowsCorrection(standing, recorded, WeightUnit.LB, sameExerciseBlock = true))
    }

    @Test
    fun `a stripped bar is a statement the carry follows`() {
        assertTrue(SetLoadPolicy.carryFollowsCorrection(0.0, 0.0, WeightUnit.KG, sameExerciseBlock = true))
    }

    @Test
    fun `an ad-hoc set carries into the same movement`() {
        // No planned slot on either side, so the plan's own block answer is
        // false and cannot be the whole rule: ad-hoc is the session where one
        // load repeats set after set, because nothing re-seeds the load box
        // from a declaration.
        assertTrue(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = null,
                nextSetIndexInExercise = null,
                comingExerciseId = "back_squat",
            ),
        )
    }

    @Test
    fun `an ad-hoc set does not carry into a different movement`() {
        assertFalse(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = null,
                nextSetIndexInExercise = null,
                comingExerciseId = "bench_press",
            ),
        )
        assertFalse(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = null,
                nextExerciseId = null,
                nextSetIndexInExercise = null,
                comingExerciseId = null,
            ),
        )
    }

    @Test
    fun `a planned next set is decided by the plan, not by the selection`() {
        // The chips can hold a movement the plan is not about to run, so where
        // there IS a slot coming up it is that slot that decides.
        assertTrue(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = "back_squat",
                nextSetIndexInExercise = 1,
                comingExerciseId = "bench_press",
            ),
        )
    }

    @Test
    fun `a second block of the same exercise is not a continuation`() {
        // `flattenPlan` can emit the same movement in two blocks and nothing
        // in a slot carries a block identity, so `setIndexInExercise == 0` is
        // what separates them. Delegating on the exercise id alone would let
        // a correction to the last set of block one move the load of block
        // two's opener, which is a fresh prescription and not a continuation.
        assertFalse(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = "back_squat",
                nextSetIndexInExercise = 0,
                comingExerciseId = "back_squat",
            ),
        )
    }

    @Test
    fun `a next slot switched during the rest is a different block`() {
        // SWITCH EXERCISE replaces the slot coming up while the rest screen
        // is drawn, and the load correction is tapped after that. The block
        // answer has to be read from the slot that is next NOW, because a
        // correction to the past may move the load of the set coming up and
        // may never move the load of a different movement -- and where the
        // two declarations render equal, two body-weight blocks being the
        // guaranteed pair, the carry writes both the box and the standing
        // statement, so the next set is RECORDED at that load.
        assertFalse(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = "bench_press",
                nextSetIndexInExercise = 1,
                comingExerciseId = "bench_press",
            ),
        )
        // The chips still holding the finished movement does not rescue it:
        // where there is a slot, the slot decides.
        assertFalse(
            SetLoadPolicy.correctionCarryBlock(
                lastExerciseId = "back_squat",
                nextExerciseId = "bench_press",
                nextSetIndexInExercise = 1,
                comingExerciseId = "back_squat",
            ),
        )
    }

    @Test
    fun `the carry does not follow across an exercise change`() {
        // Every other carry on this transition is bounded by the block the
        // statement was made in -- standingStatedAddedKg, standingAdjustedTempo,
        // standingStatedReps, standingStatedDurationS -- because #124 leaked one
        // exercise's load into the next. Two consecutive body-weight blocks are
        // the sharpest case: both seed nothing added, the finished set was
        // recorded at nothing added, and without the bound a correction to the
        // set just finished writes a load onto a set of a DIFFERENT movement.
        assertFalse(SetLoadPolicy.carryFollowsCorrection(0.0, 0.0, WeightUnit.KG, sameExerciseBlock = false))
        assertFalse(SetLoadPolicy.carryFollowsCorrection(60.0, 60.0, WeightUnit.KG, sameExerciseBlock = false))
    }

    @Test
    fun `the caption names the set just finished`() {
        assertEquals("Corrects the set you just finished", SetLoadPolicy.correctionCaption(carryFollows = false))
    }

    @Test
    fun `the caption mentions the next set only where the carry follows`() {
        // #188 is the neighbouring control that named the upcoming exercise
        // when it meant the finished one. A caption that can be read as
        // changing what is coming is the defect, so the word is pinned out of
        // the case where it would be false.
        assertFalse(SetLoadPolicy.correctionCaption(carryFollows = false).contains("next"))
        assertEquals(
            "Corrects the set you just finished, and the load offered for the next one",
            SetLoadPolicy.correctionCaption(carryFollows = true),
        )
    }

    @Test
    fun `the label says when the figure is no longer the one recorded`() {
        assertEquals("Load recorded", SetLoadPolicy.correctionLabel(corrected = false))
        assertEquals("Load (corrected)", SetLoadPolicy.correctionLabel(corrected = true))
    }
}
