package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ad-hoc exercise picker's order and prefill (UX review 2026-11-06,
 * record-setup F2/F3/F12, core-model F2/F3).
 *
 * DIFFERENTIALS. Against the symbols as they first landed -- which carry
 * today's behaviour: the 21 seeds whatever was lifted, "60" and 5 for every
 * lift, Both/Left/Right under every lift -- every ordering, prefill and side
 * case here fails. The `offPlanExercise` cases are characterization: that
 * function had no predecessor.
 */
class AdHocPickerTest {
    private fun lift(
        id: String,
        atMs: Long,
        addedKg: Double = 50.0,
        reps: Int? = 8,
        durationS: Int? = null,
        tempo: String? = null,
        side: String? = null,
        warmup: Boolean = false,
        voided: Boolean = false,
    ) = RecordedLift(id, addedKg, reps, durationS, tempo, side, warmup, voided, atMs)

    private fun plan(vararg blocks: PlanExerciseDef) = listOf(PlanSessionDef(name = "A", exercises = blocks.toList()))

    private fun block(id: String, side: String? = null, concentric: String? = null) =
        PlanExerciseDef(exercise = id, concentric = concentric, sets = listOf(PlanSetDef(reps = 10, side = side)))

    // ---- order ---------------------------------------------------------------

    @Test
    fun `recent lifts come first, most recent first, then the plan's, then the seeds`() {
        val ids =
            AdHocPicker.options(
                lifts = listOf(lift("lat_pulldown", atMs = 100), lift("seated_leg_curl", atMs = 300)),
                plan = plan(block("goblet_squat"), block("lat_pulldown")),
            ).map { it.id }
        assertEquals(listOf("seated_leg_curl", "lat_pulldown", "goblet_squat", "back_squat"), ids.take(4))
    }

    @Test
    fun `every id is offered once, at its first place`() {
        val ids =
            AdHocPicker.options(
                lifts = listOf(lift("bench_press", atMs = 1), lift("bench_press", atMs = 2)),
                plan = plan(block("bench_press")),
            ).map { it.id }
        assertEquals(1, ids.count { it == "bench_press" })
        assertEquals("bench_press", ids.first())
        assertEquals(ExerciseDef.SEED.size, ids.size)
    }

    @Test
    fun `a voided set does not make a lift recent`() {
        val ids = AdHocPicker.options(listOf(lift("lat_pulldown", atMs = 1, voided = true)), emptyList()).map { it.id }
        assertFalse("lat_pulldown" in ids)
    }

    @Test
    fun `an offered lift resolves the way an ad-hoc set of it does`() {
        val hang = AdHocPicker.options(emptyList(), emptyList()).first { it.id == "dead_hang" }
        assertEquals(ExerciseDef.resolvedById("dead_hang"), hang)
        assertTrue(hang.bodyweight)
    }

    // ---- which set is "last time" -------------------------------------------

    @Test
    fun `last time is the latest working set of that lift`() {
        val lifts =
            listOf(
                lift("rdl", atMs = 1, addedKg = 60.0),
                lift("rdl", atMs = 3, addedKg = 40.0, warmup = true),
                lift("rdl", atMs = 2, addedKg = 80.0),
                lift("rdl", atMs = 4, addedKg = 99.0, voided = true),
                lift("bench_press", atMs = 5, addedKg = 70.0),
            )
        assertEquals(80.0, AdHocPicker.lastLift(lifts, "rdl")?.addedKg)
    }

    @Test
    fun `a lift only ever warmed up on uses the warm-up`() {
        assertEquals(20.0, AdHocPicker.lastLift(listOf(lift("rdl", 1, addedKg = 20.0, warmup = true)), "rdl")?.addedKg)
    }

    @Test
    fun `a lift never done has no last time`() {
        assertNull(AdHocPicker.lastLift(listOf(lift("rdl", 1)), "bench_press"))
    }

    // ---- prefill ------------------------------------------------------------

    @Test
    fun `picking a lift fills the boxes from the last time`() {
        val fill =
            AdHocPicker.prefill(
                ExerciseDef.resolvedById("lat_pulldown"),
                lift("lat_pulldown", 1, addedKg = 45.0, reps = 12, tempo = "3010"),
                WeightUnit.KG,
            )
        assertEquals(AdHocPrefill(addedKg = 45.0, reps = 12, durationS = 60, tempo = "3010"), fill)
    }

    @Test
    fun `a hold fills its seconds from the last time`() {
        val fill =
            AdHocPicker.prefill(
                ExerciseDef.resolvedById("plank"),
                lift("plank", 1, addedKg = 0.0, reps = 0, durationS = 45),
                WeightUnit.KG,
            )
        assertEquals(45, fill.durationS)
        assertEquals(5, fill.reps)
    }

    @Test
    fun `a body-weight lift never done opens on zero added, not 60`() {
        assertEquals(0.0, AdHocPicker.prefill(ExerciseDef.resolvedById("dead_hang"), null, WeightUnit.LB).addedKg)
        assertEquals(0.0, AdHocPicker.prefill(ExerciseDef.resolvedById("pull_up"), null, WeightUnit.KG).addedKg)
    }

    @Test
    fun `a hold never done opens on zero added`() {
        assertEquals(0.0, AdHocPicker.prefill(ExerciseDef.resolvedById("plank"), null, WeightUnit.KG).addedKg)
    }

    @Test
    fun `a loaded lift never done opens on 60 in the lifter's unit`() {
        val kg = AdHocPicker.prefill(ExerciseDef.resolvedById("back_squat"), null, WeightUnit.KG)
        assertEquals(AdHocPrefill(addedKg = 60.0, reps = 5, durationS = 60, tempo = null), kg)
        val lb = AdHocPicker.prefill(ExerciseDef.resolvedById("back_squat"), null, WeightUnit.LB)
        assertEquals("60", WeightUnit.LB.inputValue(lb.addedKg))
    }

    @Test
    fun `an assisted lift keeps its negative added load`() {
        val fill =
            AdHocPicker.prefill(
                ExerciseDef.resolvedById("assisted_pull_up"),
                lift("assisted_pull_up", 1, addedKg = -20.0),
                WeightUnit.KG,
            )
        assertEquals(-20.0, fill.addedKg)
    }

    // ---- side ---------------------------------------------------------------

    @Test
    fun `a two-sided lift offers no side`() {
        assertFalse(AdHocPicker.offersSide("back_squat", emptyList(), emptyList()))
        assertFalse(AdHocPicker.offersSide("farmers_walk", emptyList(), emptyList()))
    }

    @Test
    fun `a one-sided lift offers a side`() {
        assertTrue(AdHocPicker.offersSide("suitcase_carry", emptyList(), emptyList()))
        assertTrue(AdHocPicker.offersSide("single_arm_dumbbell_row", emptyList(), emptyList()))
        val leftCurl = listOf(lift("dumbbell_curl", 1, side = "left"))
        assertTrue(AdHocPicker.offersSide("dumbbell_curl", leftCurl, emptyList()))
        assertTrue(AdHocPicker.offersSide("rfess", emptyList(), plan(block("rfess", side = "right"))))
    }

    // ---- off-plan sets in a plan session -----------------------------------

    @Test
    fun `an off-plan set of a lift the plan declares keeps the plan's geometry`() {
        val planned = AdHocPicker.offPlanExercise("lat_pulldown", plan(block("lat_pulldown", concentric = "down")))
        assertFalse(planned.exercise.concentricUp)
    }

    @Test
    fun `an off-plan set of a lift the plan does not declare is the ad-hoc definition`() {
        val planned = AdHocPicker.offPlanExercise("dead_hang", emptyList())
        assertEquals(ExerciseDef.resolvedById("dead_hang"), planned.exercise)
        assertEquals(SetGeometryPolicy.describe(planned.exercise, null), planned.geometry)
        assertFalse(planned.stackRuleApplied)
    }
}
