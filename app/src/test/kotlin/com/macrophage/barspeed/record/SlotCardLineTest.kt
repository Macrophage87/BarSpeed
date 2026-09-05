package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the "Up next" card draws under the set's figures, run for the first
 * time.
 *
 * The decision has always lived inside `SlotCard`, a composable no test in
 * this repository can reach, so its rules -- which of two lines wins, which
 * load either divides, when neither is drawn -- were held by review alone.
 * #253 changes all three, and this file is the characterization of what they
 * were, taken at the seam before anything moves.
 */
class SlotCardLineTest {
    private val squat = ExerciseDef("back_squat", "Back Squat")
    private val pullUp = ExerciseDef("pull_up", "Pull-up", bodyweight = true)
    private val cable = ExerciseDef("cable_fly", "Cable Fly", usesBarbell = false)

    private fun slot(exercise: ExerciseDef, loadKg: Double?, implementCount: Int? = null) = PlannedSlot(
        exercise = exercise,
        geometry = SetGeometryPolicy.describe(exercise, null),
        setIndexInExercise = 0,
        setsInExercise = 3,
        reps = 5,
        loadKg = loadKg,
        plannedLoadKg = loadKg,
        tempo = null,
        implementCount = implementCount,
    )

    private fun line(
        exercise: ExerciseDef,
        loadKg: Double?,
        implementCount: Int? = null,
        unit: WeightUnit = WeightUnit.LB,
        statedAddedKg: Double? = null,
    ) = slot(exercise, loadKg, implementCount).cardInstruction(unit, statedAddedKg)

    private fun lb(pounds: Double) = pounds / WeightUnit.LB_PER_KG

    @Test
    fun `a barbell id draws the plate line, inferred from the id alone`() {
        assertEquals("Plates/side: 45 + 25 + 5 (45 lb bar)", line(squat, lb(195.0)))
    }

    @Test
    fun `a declared count draws the pick-up line and beats the inferred bar`() {
        assertEquals("Pick up: 2 × 40 lb", line(squat, lb(80.0), implementCount = 2))
    }

    @Test
    fun `an id the matcher does not read as a barbell draws nothing`() {
        assertNull(line(cable, lb(195.0)))
    }

    @Test
    fun `body-weight work draws no plate line`() {
        assertNull(line(pullUp, lb(20.0)))
    }

    @Test
    fun `the lifter's stated load replaces the plan's in the instruction`() {
        assertEquals(
            "Plates/side: 45 (45 lb bar)",
            line(squat, lb(195.0), statedAddedKg = lb(135.0)),
        )
    }

    @Test
    fun `no positive load draws nothing`() {
        assertNull(line(squat, null))
        assertNull(line(squat, 0.0))
    }

    @Test
    fun `the plate line reads the display unit`() {
        assertEquals("Plates/side: 25 + 15 (20 kg bar)", line(squat, 100.0, unit = WeightUnit.KG))
    }
}
