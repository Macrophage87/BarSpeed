package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the "Up next" card draws under the set's figures, once the PLAN decides
 * it (#253).
 *
 * One commit ago these cases recorded the old rule: a plate line inferred from
 * the exercise id through `ExerciseDef.usesBarbell`, and a pair line drawn off
 * `implementCount` alone. Both inferences are gone. The card draws what the
 * plan's `implement` says and nothing otherwise, which is a REAL LOSS for
 * every plan written before schema 1.12 -- those declare no implement, so
 * their barbell sets lose the loading line they had. That is what the import
 * gate's new warning and the version log are for, and it is the deliberate
 * price of never printing a loading a guess produced.
 *
 * The composable is still out of reach: which text style and colour the line
 * draws in, and where on the card it sits, are compile- and lint-gated only.
 */
class SlotCardLineTest {
    private val squat = ExerciseDef("back_squat", "Back Squat")
    private val pullUp = ExerciseDef("pull_up", "Pull-up", bodyweight = true)
    private val cable = ExerciseDef("cable_fly", "Cable Fly", usesBarbell = false)

    private fun slot(
        exercise: ExerciseDef,
        loadKg: Double?,
        implement: Implement = Implement.OTHER,
        implementCount: Int? = null,
        barKg: Double? = null,
    ) = PlannedSlot(
        exercise = exercise,
        geometry = SetGeometryPolicy.describe(exercise, null),
        setIndexInExercise = 0,
        setsInExercise = 3,
        reps = 5,
        loadKg = loadKg,
        plannedLoadKg = loadKg,
        tempo = null,
        implementCount = implementCount,
        implement = implement,
        barKg = barKg,
    )

    private fun lb(pounds: Double) = pounds / WeightUnit.LB_PER_KG

    @Test
    fun `a declared barbell draws the per-side loading`() {
        assertEquals(
            "45 + 25 + 5 per side",
            slot(squat, lb(195.0), Implement.BARBELL).cardInstruction(WeightUnit.LB, null),
        )
    }

    @Test
    fun `a barbell id with no declaration draws nothing at all`() {
        // The line this replaced read "Plates/side: 45 + 25 + 5 (45 lb bar)"
        // here, off the id alone. Nothing is inferred now.
        assertNull(slot(squat, lb(195.0)).cardInstruction(WeightUnit.LB, null))
    }

    @Test
    fun `a declared dumbbell draws the pair`() {
        assertEquals(
            "2 × 40 lb dumbbells",
            slot(squat, lb(80.0), Implement.DUMBBELL).cardInstruction(WeightUnit.LB, null),
        )
    }

    @Test
    fun `a count with no declared implement draws nothing`() {
        // "Pick up: 2 × 40 lb" until this change. A plan declaring two objects
        // and no implement is a plan the gate warns about at import.
        assertNull(slot(squat, lb(80.0), implementCount = 2).cardInstruction(WeightUnit.LB, null))
    }

    @Test
    fun `a declared other draws nothing, count or no count`() {
        assertNull(slot(cable, lb(195.0), Implement.OTHER).cardInstruction(WeightUnit.LB, null))
        assertNull(
            slot(cable, lb(80.0), Implement.OTHER, implementCount = 2).cardInstruction(WeightUnit.LB, null),
        )
    }

    @Test
    fun `a declared barbell beats the exercise's own body-weight flag`() {
        // usesBarbell no longer decides anything on this card, and neither
        // does bodyweight: the suppression existed because barbell-ness was
        // GUESSED and "pull_up" guessed true. A plan that writes "barbell" has
        // said what it means.
        assertEquals(
            "Below the 45 lb bar",
            slot(pullUp, lb(20.0), Implement.BARBELL).cardInstruction(WeightUnit.LB, null),
        )
    }

    @Test
    fun `the lifter's stated load replaces the plan's in the instruction`() {
        assertEquals(
            "45 per side",
            slot(squat, lb(195.0), Implement.BARBELL).cardInstruction(WeightUnit.LB, lb(135.0)),
        )
    }

    @Test
    fun `a declared bar is used and named`() {
        assertEquals(
            "45 + 25 + 10 per side, 35 lb bar",
            slot(squat, lb(195.0), Implement.BARBELL, barKg = lb(35.0)).cardInstruction(WeightUnit.LB, null),
        )
    }

    @Test
    fun `no positive load draws nothing`() {
        assertNull(slot(squat, null, Implement.BARBELL).cardInstruction(WeightUnit.LB, null))
        assertNull(slot(squat, 0.0, Implement.BARBELL).cardInstruction(WeightUnit.LB, null))
        assertNull(slot(squat, null, Implement.DUMBBELL).cardInstruction(WeightUnit.LB, null))
    }

    @Test
    fun `the line reads the display unit`() {
        assertEquals(
            "25 + 15 per side",
            slot(squat, 100.0, Implement.BARBELL).cardInstruction(WeightUnit.KG, null),
        )
    }
}
