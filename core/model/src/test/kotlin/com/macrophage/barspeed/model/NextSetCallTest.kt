package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The line the voice says about the next set at the end of a rest (voice F4). */
class NextSetCallTest {
    private fun phrase(
        name: String = "Bench press",
        bodyweight: Boolean = false,
        timed: Boolean = false,
        unit: WeightUnit = WeightUnit.KG,
        loadKg: Double? = 100.0,
        reps: Int? = 5,
        durationS: Int? = null,
        side: String? = null,
    ) = NextSetCall.phrase(name, bodyweight, timed, unit, loadKg, reps, durationS, side)

    @Test
    fun `a loaded rep set names the exercise, the load and the reps`() {
        assertEquals("Next: Bench press, 100 kilos, 5", phrase())
        assertEquals("Next: Bench press, 102.5 kilos, 5", phrase(loadKg = 102.5))
    }

    @Test
    fun `the load is said in the lifter's unit`() {
        assertEquals("Next: Bench press, 220.5 pounds, 5", phrase(unit = WeightUnit.LB))
    }

    @Test
    fun `body-weight work says body weight and what was added or taken off`() {
        assertEquals(
            "Next: Pull-up, body weight, 6",
            phrase(name = "Pull-up", bodyweight = true, loadKg = null, reps = 6),
        )
        assertEquals(
            "Next: Pull-up, body weight, 6",
            phrase(name = "Pull-up", bodyweight = true, loadKg = 0.0, reps = 6),
        )
        assertEquals(
            "Next: Pull-up, body weight plus 10 kilos, 6",
            phrase(name = "Pull-up", bodyweight = true, loadKg = 10.0, reps = 6),
        )
        assertEquals(
            "Next: Pull-up, body weight minus 20 kilos, 6",
            phrase(name = "Pull-up", bodyweight = true, loadKg = -20.0, reps = 6),
        )
    }

    @Test
    fun `a hold says its seconds and is loaded by the body when nothing is added`() {
        assertEquals(
            "Next: Plank, body weight, 60 seconds",
            phrase(name = "Plank", timed = true, loadKg = null, reps = null, durationS = 60),
        )
        assertEquals(
            "Next: Farmer's walk, 32 kilos, 40 seconds",
            phrase(name = "Farmer's walk", timed = true, loadKg = 32.0, reps = null, durationS = 40),
        )
    }

    @Test
    fun `a unilateral set says which arm, lower case`() {
        assertEquals(
            "Next: Single-arm row, left, 20 kilos, 8",
            phrase(name = "Single-arm row", loadKg = 20.0, reps = 8, side = "left"),
        )
        assertEquals(
            "Next: Single-arm row, right, 20 kilos, 8",
            phrase(name = "Single-arm row", loadKg = 20.0, reps = 8, side = "Right"),
        )
    }

    @Test
    fun `an unloaded rep set and an unknown count say only what is known`() {
        assertEquals("Next: Back squat, 5", phrase(name = "Back squat", loadKg = null))
        assertEquals("Next: Back squat, 5", phrase(name = "Back squat", loadKg = 0.0))
        assertEquals("Next: Back squat, 60 kilos", phrase(name = "Back squat", loadKg = 60.0, reps = null))
    }

    @Test
    fun `a set with no name says nothing`() {
        assertNull(phrase(name = " "))
    }

    @Test
    fun `rest over carries the next set when there is one and stands alone when there is not`() {
        assertEquals("Rest over", NextSetCall.restOver(null))
        assertEquals("Rest over. Next: Bench press, 100 kilos, 5", NextSetCall.restOver(phrase()))
    }

    /** The rest words are not rep calls, so the engine is told nothing about them (#339). */
    @Test
    fun `the next-set line marks no rep call`() {
        assertEquals(emptyList(), SpokenRepCall.spansOf(NextSetCall.restOver(phrase())))
    }
}
