package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The history card's targets (#308), lifted out of `SessionDetailScreen`.
 *
 * Green pins on what the card drew before the lift, chosen so that each one
 * still holds once the card reads the working figure on a v20 row: a row
 * written before v20, a v20 row whose target nobody changed, and a raised
 * target that was met. Nothing here is red; each pin's strength is shown by a
 * mutation run.
 *
 * A row "written before v20" is one with no `workingLoadKg`; a "v20 row"
 * carries one. The hold numbers are the tone bands at a 45 s target: 90 % of
 * 45 is 40.5, whose whole seconds are 40, so 40 is close and 39 is well short.
 */
class HistoryTargetTest {
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

    @Test
    fun `a hold on a row written before v20 is graded against the plan's figure`() {
        val target = of(row(actualDurationS = 30, plannedDurationS = 45, durationEndedBy = "lifter"))
        assertEquals(HistoryTarget.Source.PLAN, target.source)
        assertEquals(HistoryTarget.HeldChip("Held 30/45s", HistoryTarget.Tone.BAD), target.heldChip)
        assertEquals(HistoryTarget.Verdict.Frozen(null), target.verdict, "an uncorrected old row keeps its sentence")
    }

    @Test
    fun `the held chip's tone bands at a 45 s plan target`() {
        fun tone(held: Int) = of(row(actualDurationS = held, plannedDurationS = 45)).heldChip?.tone
        assertEquals(HistoryTarget.Tone.OK, tone(50), "past the target")
        assertEquals(HistoryTarget.Tone.OK, tone(45), "at the target")
        assertEquals(HistoryTarget.Tone.WARN, tone(44), "just under")
        assertEquals(HistoryTarget.Tone.WARN, tone(40), "40 is the whole seconds of 90 % of 45")
        assertEquals(HistoryTarget.Tone.BAD, tone(39), "one under the close-enough line")
    }

    @Test
    fun `a hold with no target reads its seconds alone and is not graded`() {
        for (workingLoadKg in listOf(null, 100.0)) {
            val target = of(row(actualDurationS = 30, workingLoadKg = workingLoadKg))
            assertEquals("30s", target.holdHeader, "workingLoadKg=$workingLoadKg")
            assertEquals(HistoryTarget.HeldChip("Held 30s", HistoryTarget.Tone.OK), target.heldChip)
            assertEquals(HistoryTarget.Source.NONE, target.source)
        }
    }

    @Test
    fun `a rep set on a row written before v20 keeps the plan's slots and its frozen sentence`() {
        val target = of(row(actualReps = 5, plannedReps = 6))
        assertEquals(6, target.repSlots)
        assertEquals(HistoryTarget.Source.PLAN, target.source)
        assertEquals(HistoryTarget.Verdict.Frozen(null), target.verdict)
        assertNull(target.holdHeader, "a rep set has no hold header")
        assertNull(target.heldChip, "a rep set has no held chip")
    }

    @Test
    fun `a rep set with no plan figure has no slots and no target`() {
        val target = of(row(actualReps = 5))
        assertNull(target.repSlots)
        assertEquals(HistoryTarget.Source.NONE, target.source)
        assertNull(target.note, "nothing to say about a target that does not exist")
    }

    @Test
    fun `a rep set on a v20 row keeps its frozen sentence`() {
        val target = of(row(actualReps = 5, plannedReps = 5, workingReps = 5, workingLoadKg = 100.0))
        assertEquals(HistoryTarget.Verdict.Frozen(null), target.verdict, "only a hold is re-graded")
        assertEquals(5, target.repSlots)
        assertNull(target.holdHeader)
    }

    @Test
    fun `a v20 hold whose target nobody changed reads the one figure`() {
        val target =
            of(row(actualDurationS = 45, plannedDurationS = 45, workingDurationS = 45, workingLoadKg = 100.0))
        assertEquals("45s (target 45s)", target.holdHeader, "no discrepancy to note")
        assertEquals(HistoryTarget.HeldChip("Held 45/45s", HistoryTarget.Tone.OK), target.heldChip)
    }

    @Test
    fun `a raised hold target that was met reads met`() {
        // Plan 45, raised to 60 with the in-app control, held 60.
        val target =
            of(row(actualDurationS = 60, plannedDurationS = 45, workingDurationS = 60, workingLoadKg = 100.0))
        assertEquals(HistoryTarget.Tone.OK, target.heldChip?.tone)
    }

    @Test
    fun `a row written before v20 shows the plan's load as the deviation`() {
        assertEquals(
            "Deviation (planned 100 kg)",
            of(row(loadKg = 90.0, plannedLoadKg = 100.0)).loadDeviation,
        )
        assertNull(of(row(loadKg = 100.0, plannedLoadKg = 100.0)).loadDeviation, "the plan's load, lifted")
        assertNull(of(row(loadKg = 100.0, plannedLoadKg = null)).loadDeviation, "no plan load to deviate from")
    }

    @Test
    fun `a v20 row lifted at the plan's load and not corrected shows no deviation and no load note`() {
        val target =
            of(row(actualReps = 5, loadKg = 100.0, plannedLoadKg = 100.0, workingLoadKg = 100.0))
        assertNull(target.loadDeviation)
        assertNull(target.note?.takeIf { it.contains("load") }, "no load discrepancy to note")
    }

    @Test
    fun `a load difference too small to print is not noted`() {
        // 100.04 kg prints as 100 kg, so a note would read "raised from 100 kg"
        // beside a working load that also prints 100 kg.
        val target =
            of(row(actualReps = 5, loadKg = 100.0, plannedLoadKg = 100.04, workingLoadKg = 100.0))
        assertNull(target.note?.takeIf { it.contains("load") })
    }
}
