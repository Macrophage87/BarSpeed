package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Red differentials for #308 and #157 L3: the history card grades a v20 row
 * against the target the set RAN against, and says whose figure it read.
 *
 * Every test here fails against the lift, which reads the plan's columns on
 * every row and always shows the frozen sentence.
 *
 * The owner's rulings, 2026-09-25, are the specification. A lowered target
 * met: "It's completed even if the target is lowered, just note the
 * discrepancy." So a lowered target is a noted fact on the card, never a
 * short or failed grade. A raised target missed is failed ("if you can't do
 * it, it's failed"), and a raised target met reads met. A row written before
 * v20 (no `workingLoadKg`) could not say what the set ran against, so its
 * figure stays the plan's and is labelled as the plan's.
 */
class HistoryTargetDifferentialTest {
    private fun row(
        actualReps: Int = 0,
        plannedReps: Int? = null,
        workingReps: Int? = null,
        actualDurationS: Int? = null,
        plannedDurationS: Int? = null,
        workingDurationS: Int? = null,
        loadKg: Double = 100.0,
        plannedLoadKg: Double? = null,
        workingLoadKg: Double? = null,
        durationEndedBy: String? = null,
    ) = HistoryTarget.Row(
        actualReps = actualReps,
        plannedReps = plannedReps,
        workingReps = workingReps,
        actualDurationS = actualDurationS,
        plannedDurationS = plannedDurationS,
        workingDurationS = workingDurationS,
        loadKg = loadKg,
        plannedLoadKg = plannedLoadKg,
        workingLoadKg = workingLoadKg,
        durationEndedBy = durationEndedBy,
    )

    private fun of(row: HistoryTarget.Row) = HistoryTarget.of(row, WeightUnit.KG)

    /** Plan 45 s, lowered to 30 s in the change-set dialog, held 30 s. */
    private val lowered30of45 =
        row(actualDurationS = 30, plannedDurationS = 45, workingDurationS = 30, workingLoadKg = 100.0)

    @Test
    fun `working 30 planned 45 held 30 grades OK against the working target`() {
        val target = of(lowered30of45)
        assertEquals(HistoryTarget.HeldChip("Held 30/30s", HistoryTarget.Tone.OK), target.heldChip)
        assertEquals(HistoryTarget.Source.WORKING, target.source)
    }

    @Test
    fun `working 30 planned 45 held 30 re-grades the sentence against the working target`() {
        assertEquals(HistoryTarget.Verdict.Regrade(actualS = 30, targetS = 30), of(lowered30of45).verdict)
    }

    @Test
    fun `a lowered hold target is noted in the header, not graded`() {
        assertEquals("30s (target 30s · lowered from 45s)", of(lowered30of45).holdHeader)
    }

    @Test
    fun `a raised hold target that was missed is graded against the raised target`() {
        // Plan 45, raised to 60, held 50: over the plan, short of the target.
        val target =
            of(row(actualDurationS = 50, plannedDurationS = 45, workingDurationS = 60, workingLoadKg = 100.0))
        assertEquals(HistoryTarget.HeldChip("Held 50/60s", HistoryTarget.Tone.BAD), target.heldChip)
        assertEquals("50s (target 60s · raised from 45s)", target.holdHeader)
    }

    @Test
    fun `a lowered rep target met is noted as the discrepancy, and the slots are the working count`() {
        // Field-45 set 8: plan 10, lowered to 6, did 6.
        val target = of(row(actualReps = 6, plannedReps = 10, workingReps = 6, workingLoadKg = 100.0))
        assertEquals("6 of 6 · lowered from 10", target.note)
        assertEquals(6, target.repSlots)
        assertEquals(HistoryTarget.Source.WORKING, target.source)
    }

    @Test
    fun `a raised rep target reads against the raised count`() {
        // Field-45 set 2: plan 8, raised to 10, did 10.
        val target = of(row(actualReps = 10, plannedReps = 8, workingReps = 10, workingLoadKg = 100.0))
        assertEquals("10 of 10 · raised from 8", target.note)
        assertEquals(10, target.repSlots)
    }

    @Test
    fun `a hold on a row written before v20 labels its target as the plan's`() {
        assertEquals("30s (plan's target 45s)", of(row(actualDurationS = 30, plannedDurationS = 45)).holdHeader)
    }

    @Test
    fun `a rep set on a row written before v20 labels its slots as the plan's`() {
        assertEquals("Plan's target: 6 reps", of(row(actualReps = 5, plannedReps = 6)).note)
    }

    @Test
    fun `a corrected hold on a row written before v20 is labelled as graded before the correction`() {
        val target = of(row(actualDurationS = 30, plannedDurationS = 45, durationEndedBy = "corrected"))
        assertEquals(
            HistoryTarget.Verdict.Frozen("Graded at set end, before the hold was corrected:"),
            target.verdict,
        )
    }

    @Test
    fun `a v20 load raised from the plan is a note, not a deviation`() {
        val target =
            of(
                row(
                    actualReps = 5,
                    plannedReps = 5,
                    workingReps = 5,
                    loadKg = 110.0,
                    plannedLoadKg = 100.0,
                    workingLoadKg = 110.0,
                ),
            )
        assertNull(target.loadDeviation, "a raised load is intended use, not a deviation")
        assertEquals("5 of 5 · load raised from 100 kg", target.note)
    }

    @Test
    fun `a v20 load corrected after the set deviates from the working figure`() {
        val target = of(row(actualReps = 5, loadKg = 105.0, plannedLoadKg = 110.0, workingLoadKg = 110.0))
        assertEquals("Deviation (working 110 kg)", target.loadDeviation)
    }
}
