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
}
