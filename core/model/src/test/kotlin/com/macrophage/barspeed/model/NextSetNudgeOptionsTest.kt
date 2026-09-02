package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the post-set grid offers, per progression kind and per trigger
 * condition (#214).
 *
 * RED AT ITS OWN SHA. [NextSetNudgePolicy.options] is a declared seam
 * returning an empty list at the commit before this one, so every case here
 * that expects tiles fails and every case that expects none passes. That
 * asymmetry is the point: the cases expecting none would pass against a
 * function that has been deleted, so they are worth nothing on their own and
 * are kept only because they are the other half of the contract.
 *
 * The scenario the owner cited, field-37: set 1 of a seated overhead press at
 * 45 lb, rated 6 -- "could have added 10-15 lb" -- with three sets left. On
 * that state this grid must offer the pound row.
 */
class NextSetNudgeOptionsTest {
    private fun options(
        tier: HeadroomTier? = HeadroomTier.ONE_INCREMENT,
        failed: Boolean = false,
        warmup: Boolean = false,
        setsLeft: Int = 3,
        progression: ProgressionKind = ProgressionKind.WEIGHT,
        unit: WeightUnit = WeightUnit.LB,
    ) = NextSetNudgePolicy.options(tier, failed, warmup, setsLeft, progression, unit)

    // ---- the weight kind, which is also the default when the key is absent ----

    @Test
    fun `field-37 offers the pound row on a headroom rung with sets left`() {
        val offered = options()
        assertEquals(NextSetNudgePolicy.LB_STEPS, offered.map { it.amount })
        assertTrue(offered.all { it.kind == ProgressionKind.WEIGHT })
    }

    @Test
    fun `the pound tiles say what they add, in pounds`() {
        assertEquals(
            listOf("+5 lb", "+10 lb", "+15 lb", "+20 lb", "+25 lb", "+30 lb"),
            options().map { it.label },
        )
    }

    @Test
    fun `kilogram mode offers the authored kilogram row and never a conversion`() {
        val offered = options(unit = WeightUnit.KG)
        assertEquals(NextSetNudgePolicy.KG_STEPS, offered.map { it.amount })
        assertEquals(
            listOf("+2.5 kg", "+5 kg", "+7.5 kg", "+10 kg", "+12.5 kg", "+15 kg"),
            offered.map { it.label },
        )
    }

    @Test
    fun `an exercise declaring nothing gets the weight row, because absent means weight`() {
        assertEquals(
            options(progression = ProgressionKind.WEIGHT).map { it.label },
            options(progression = ProgressionKind.ofPlan(null)).map { it.label },
        )
    }

    @Test
    fun `every headroom rung offers the grid, not just the one-increment one`() {
        HeadroomTier.entries.forEach { tier ->
            assertEquals(
                NextSetNudgePolicy.LB_STEPS,
                options(tier = tier).map { it.amount },
                "$tier is a headroom rung and must offer the grid",
            )
        }
    }

    // ---- the reps kind ----

    @Test
    fun `a reps exercise offers one and two more reps`() {
        val offered = options(progression = ProgressionKind.REPS)
        assertEquals(listOf(1.0, 2.0), offered.map { it.amount })
        assertEquals(listOf("+1 rep", "+2 reps"), offered.map { it.label })
        assertTrue(offered.all { it.kind == ProgressionKind.REPS })
    }

    @Test
    fun `a reps exercise offers the same two rungs in either display unit`() {
        assertEquals(
            options(progression = ProgressionKind.REPS, unit = WeightUnit.LB).map { it.label },
            options(progression = ProgressionKind.REPS, unit = WeightUnit.KG).map { it.label },
        )
    }

    // ---- the time kind ----

    @Test
    fun `a timed exercise offers five, ten and fifteen more seconds`() {
        val offered = options(progression = ProgressionKind.TIME)
        assertEquals(listOf(5.0, 10.0, 15.0), offered.map { it.amount })
        assertEquals(listOf("+5 s", "+10 s", "+15 s"), offered.map { it.label })
        assertTrue(offered.all { it.kind == ProgressionKind.TIME })
    }

    // ---- the none kind, which is the second addendum's whole point ----

    @Test
    fun `an exercise declaring none offers nothing, however the set was rated`() {
        HeadroomTier.entries.forEach { tier ->
            assertEquals(
                emptyList(),
                options(tier = tier, progression = ProgressionKind.NONE),
                "$tier on a none exercise must still offer nothing",
            )
        }
    }

    // ---- the trigger conditions ----

    @Test
    fun `a counted-end rating offers nothing`() {
        assertEquals(emptyList(), options(tier = null))
    }

    @Test
    fun `a failed set offers nothing even when it was rated on a headroom rung`() {
        assertEquals(emptyList(), options(failed = true))
    }

    @Test
    fun `a warm-up offers nothing`() {
        assertEquals(emptyList(), options(warmup = true))
    }

    @Test
    fun `the last set of an exercise offers nothing`() {
        assertEquals(emptyList(), options(setsLeft = 0))
    }

    @Test
    fun `one set left is enough`() {
        assertTrue(options(setsLeft = 1).isNotEmpty())
    }

    @Test
    fun `every kind but none is suppressed by the same four conditions`() {
        listOf(ProgressionKind.WEIGHT, ProgressionKind.REPS, ProgressionKind.TIME).forEach { kind ->
            assertEquals(emptyList(), options(tier = null, progression = kind), "$kind on a counted rung")
            assertEquals(emptyList(), options(failed = true, progression = kind), "$kind on a failed set")
            assertEquals(emptyList(), options(warmup = true, progression = kind), "$kind on a warm-up")
            assertEquals(emptyList(), options(setsLeft = 0, progression = kind), "$kind with no sets left")
        }
    }
}
