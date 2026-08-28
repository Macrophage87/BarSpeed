package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the rest screen says the lifter has changed, once the controls that
 * changed it are behind a button.
 *
 * The no-deviation case is pinned first because it is the one the seam already
 * answers correctly, and because getting it wrong the other way — a line that
 * is always present — is what would make the lifter stop reading it. Every
 * case where the line has something to SAY is a differential, in its own
 * commit, left to run red.
 */
class SetDeviationSummaryTest {
    private fun parts(
        kind: ExerciseKind = ExerciseKind.DYNAMIC,
        bodyweight: Boolean = false,
        unit: WeightUnit = WeightUnit.KG,
        plannedLoadKg: Double? = 90.0,
        statedLoadKg: Double? = null,
        plannedReps: Int? = 5,
        statedReps: Int? = 5,
        plannedDurationS: Int? = null,
        statedDurationS: Int? = null,
        plannedTempo: String? = "4010",
        tempo: String? = "4010",
        plannedPrepS: Int = 10,
        prepS: Int = 10,
    ) = SetDeviationSummary.parts(
        kind = kind,
        bodyweight = bodyweight,
        unit = unit,
        plannedLoadKg = plannedLoadKg,
        statedLoadKg = statedLoadKg,
        plannedReps = plannedReps,
        statedReps = statedReps,
        plannedDurationS = plannedDurationS,
        statedDurationS = statedDurationS,
        plannedTempo = plannedTempo,
        tempo = tempo,
        plannedPrepS = plannedPrepS,
        prepS = prepS,
    )

    @Test
    fun `a set the lifter has not touched says nothing`() {
        // Absence is absence. A line reading "no changes" would be present on
        // every rest screen of every session, which is how a line stops being
        // read before the one time it matters.
        assertEquals(emptyList(), parts())
    }

    @Test
    fun `a stated load equal to the plan's is not a change`() {
        // The lifter typed the number that was already there, or the #124 load
        // carry re-stated it for them. Either way nothing deviates, and saying
        // so would train the lifter to ignore the line.
        assertEquals(emptyList(), parts(statedLoadKg = 90.0))
    }

    @Test
    fun `a stated zero against a plan that declared no load is not a change`() {
        // SetLoadPolicy.resolve reads a null declaration as nothing added, so
        // a stated 0 records exactly what the plan asked for. The comparison
        // has to be between what will be RECORDED and what was prescribed, not
        // between a Double and a null.
        assertEquals(emptyList(), parts(plannedLoadKg = null, statedLoadKg = 0.0))
    }

    @Test
    fun `a load the lifter changed is named where the card still says the plan's`() {
        // The whole reason the button is allowed to hide the box. The "Up
        // next" card goes on saying 90; this is the only thing on the screen
        // that says the set will record 100.
        assertEquals(listOf("100 kg"), parts(statedLoadKg = 100.0))
    }

    @Test
    fun `stripping the bar is a change and is said as one`() {
        // Zero is a statement, not an absence -- the same rule
        // SetLoadPolicy.standingStatedAddedKg keeps one module over. A lifter
        // who took every plate off has said something, and the line has to
        // carry it or the card's 90 stands unchallenged.
        assertEquals(listOf("0 kg"), parts(statedLoadKg = 0.0))
    }

    @Test
    fun `a changed load on body-weight work keeps the BW notation`() {
        // One notation for the added load, not two: the card above says
        // "BW − 50 kg" and this must not say "-50 kg" under it. #160.
        assertEquals(
            listOf("BW − 20 kg"),
            parts(bodyweight = true, plannedLoadKg = -50.0, statedLoadKg = -20.0),
        )
    }

    @Test
    fun `a changed rep count is named`() {
        assertEquals(listOf("8 reps"), parts(statedReps = 8))
    }

    @Test
    fun `a changed hold is named with the word for the movement`() {
        // A carry is not a hold. Both screens that render this pair already
        // choose the word from the kind; deciding it here rather than in a
        // third place is why the kind is a parameter.
        assertEquals(
            listOf("45s hold"),
            parts(
                kind = ExerciseKind.HOLD,
                plannedReps = null,
                statedReps = null,
                plannedDurationS = 30,
                statedDurationS = 45,
            ),
        )
        assertEquals(
            listOf("45s carry"),
            parts(
                kind = ExerciseKind.CARRY,
                plannedReps = null,
                statedReps = null,
                plannedDurationS = 30,
                statedDurationS = 45,
            ),
        )
    }

    @Test
    fun `a changed tempo is named`() {
        assertEquals(listOf("tempo 6010"), parts(tempo = "6010"))
    }

    @Test
    fun `a tempo written with dashes is not a change from the same tempo without`() {
        // "4-0-1-0" and "4010" are one prescription in two spellings, and a
        // plan may write either. A string compare would report a deviation the
        // lifter never made, on every set of every plan written the long way
        // -- and a line that cries wolf on set one is not read on set five.
        assertEquals(emptyList(), parts(plannedTempo = "4-0-1-0", tempo = "4010"))
        assertEquals(emptyList(), parts(plannedTempo = "4010", tempo = "4-0-1-0"))
    }

    @Test
    fun `a changed prep is named`() {
        assertEquals(listOf("prep 20s"), parts(prepS = 20))
    }

    @Test
    fun `every change is named, in the order the controls behind the button run`() {
        // Load, reps, tempo, prep -- the layout order inside the dialog, so
        // the line reads as an index of what was touched rather than as an
        // arbitrary list.
        assertEquals(
            listOf("100 kg", "8 reps", "tempo 6010", "prep 20s"),
            parts(statedLoadKg = 100.0, statedReps = 8, tempo = "6010", prepS = 20),
        )
    }

    @Test
    fun `nothing is invented from a declaration the plan never made`() {
        // A tempo cannot be ADDED by the control -- TempoAdjuster refuses a
        // slot with no tempo -- so a null declaration has nothing to deviate
        // from, and neither has a rep count on a timed set. Reporting either
        // would put a "change" on screen for a set the lifter never touched.
        assertEquals(emptyList(), parts(plannedTempo = null, tempo = "4010"))
        assertEquals(emptyList(), parts(plannedReps = null, statedReps = 8))
    }
}
