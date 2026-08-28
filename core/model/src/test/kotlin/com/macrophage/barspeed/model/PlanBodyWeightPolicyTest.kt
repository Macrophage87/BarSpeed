package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decision that turns a declared bodyweight into a stored one, issue #161.
 *
 * Green when written, and no pretence is made otherwise: [PlanBodyWeightPolicy]
 * is a new symbol, so nothing here could fail before the function existed.
 * Mutation stands in for the red, and the mutations are recorded in this
 * commit's message rather than claimed here.
 *
 * What CANNOT be tested anywhere in this repository is the write itself.
 * `SettingsStore.setBodyWeightKg` is a DataStore edit in `:app`, and no test
 * here reaches Android, so this pins which figure `:app` is handed and never
 * that it was stored, displayed, or read back.
 */
class PlanBodyWeightPolicyTest {
    private fun plan(kg: Double? = null, lb: Double? = null) = PlanFile(
        schemaVersion = PlanFile.SCHEMA_VERSION,
        planName = "P",
        bodyweightKg = kg,
        bodyweightLb = lb,
        sessions = listOf(
            PlanSessionDef(
                name = "S",
                exercises = listOf(PlanExerciseDef(exercise = "pull_up", sets = listOf(PlanSetDef(reps = 5)))),
            ),
        ),
    )

    @Test
    fun `a declared kilogram figure is written as it stands`() {
        assertEquals(84.0, PlanBodyWeightPolicy.acceptedKg(plan(kg = 84.0)))
    }

    /**
     * Pounds convert on import, kilograms being canonical everywhere in
     * storage. The expected figure is computed from [WeightUnit.LB_PER_KG]
     * rather than typed, so this pins the direction of the division and not a
     * rounding of the constant -- typing 83.9 here would fail for the right
     * answer and pass for a constant that had drifted.
     */
    @Test
    fun `a declared pound figure converts to kilograms`() {
        assertEquals(185.0 / WeightUnit.LB_PER_KG, PlanBodyWeightPolicy.acceptedKg(plan(lb = 185.0)))
        assertTrue(
            PlanBodyWeightPolicy.acceptedKg(plan(lb = 185.0))!! in 83.0..85.0,
            "185 lb did not land near 84 kg, so the conversion is inverted",
        )
    }

    /**
     * Omission and null are one case, and both leave the stored figure alone.
     * They are indistinguishable to the decoder, so this asserts the outcome
     * for the only state either of them produces.
     */
    @Test
    fun `a plan declaring no bodyweight writes nothing`() {
        assertNull(PlanBodyWeightPolicy.acceptedKg(plan()))
    }

    /**
     * Defence in depth, and the second half of a guard whose first half is
     * [PlanFile.validate]. The validator refuses the whole document; this
     * declines to write. A 0 arriving through some later path -- a plan built
     * in code, a document staged by an older build -- must not become the base
     * load of every bodyweight set.
     */
    @Test
    fun `a non-positive figure is not written, in either unit`() {
        assertNull(PlanBodyWeightPolicy.acceptedKg(plan(kg = 0.0)), "a zero kg bodyweight was accepted")
        assertNull(PlanBodyWeightPolicy.acceptedKg(plan(kg = -84.0)), "a negative kg bodyweight was accepted")
        assertNull(PlanBodyWeightPolicy.acceptedKg(plan(lb = 0.0)), "a zero lb bodyweight was accepted")
        assertNull(PlanBodyWeightPolicy.acceptedKg(plan(lb = -185.0)), "a negative lb bodyweight was accepted")
    }

    @Test
    fun `a figure that is not a number is not written`() {
        assertNull(PlanBodyWeightPolicy.acceptedKg(Double.NaN, null))
        assertNull(PlanBodyWeightPolicy.acceptedKg(Double.POSITIVE_INFINITY, null))
    }

    /**
     * Both units at once is refused by [PlanFile.validate], so no staged plan
     * reaches this. The tie-break is pinned anyway, because an unpinned one is
     * a coin flip the next reader has to re-derive: kg is canonical, so kg
     * wins.
     */
    @Test
    fun `kilograms win a contradiction the validator has already refused`() {
        assertEquals(84.0, PlanBodyWeightPolicy.acceptedKg(84.0, 999.0))
        assertTrue(
            plan(kg = 84.0, lb = 999.0).validate().any { "must not both be declared" in it },
            "a plan declaring both units validated clean, so the tie-break above is reachable",
        )
    }

    /**
     * The gate line quotes the figure in the lifter's own display unit.
     *
     * A body weight is the one number here the lifter can sanity-check at a
     * glance, and only in the unit they weigh themselves in. The assertion
     * compares against [WeightUnit.format] rather than a typed string, so it
     * pins that the line is unit-aware and not what the formatter rounds to.
     */
    @Test
    fun `the gate line states the applied figure in the lifter's unit`() {
        assertTrue(
            WeightUnit.KG.format(84.0) in PlanBodyWeightPolicy.appliedLine(84.0, WeightUnit.KG),
            "the kg line does not quote the figure",
        )
        assertTrue(
            WeightUnit.LB.format(84.0) in PlanBodyWeightPolicy.appliedLine(84.0, WeightUnit.LB),
            "the lb line quotes kilograms at a lifter who weighs in pounds",
        )
    }

    /**
     * The line says the change already happened and survives a discard.
     *
     * Narrow, and said so: this cannot check the sentence reads well, only that
     * it does not omit the one fact a lifter would otherwise get wrong -- the
     * write is made at acceptance, so discarding the plan does not put the old
     * figure back.
     */
    @Test
    fun `the gate line does not let a discard look like an undo`() {
        assertTrue(
            "discard" in PlanBodyWeightPolicy.appliedLine(84.0, WeightUnit.KG),
            "the gate line never says what discarding the plan does to the figure it just applied",
        )
    }
}
