package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a plan's `implement`, `bar_lb` and `bar_kg` declarations read once
 * decoded (#253).
 *
 * The resolved readings are pinned here rather than at the queue, because
 * `flattenPlan` is a suspend extension on `SessionRepository` and no test on
 * the CI path can call it. What that leaves ungated is three assignments in
 * that function, the same exposure every other field it copies already has;
 * what it does NOT leave ungated is any of the arithmetic or the defaulting,
 * which is all here.
 */
class PlanImplementDeclarationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(keys: String): PlanExerciseDef = json.decodeFromString(
        PlanExerciseDef.serializer(),
        """{ "exercise": "back_squat", $keys "sets": [ { "reps": 5, "load_lb": 195 } ] }""",
    )

    @Test
    fun `an omitted implement is other and implies no count`() {
        val e = exercise("")
        assertEquals(Implement.OTHER, e.resolvedImplement)
        assertNull(e.resolvedImplementCount)
        assertNull(e.resolvedBarKg)
    }

    @Test
    fun `the three declared words resolve to the three implements`() {
        assertEquals(Implement.BARBELL, exercise("\"implement\": \"barbell\",").resolvedImplement)
        assertEquals(Implement.DUMBBELL, exercise("\"implement\": \"dumbbell\",").resolvedImplement)
        assertEquals(Implement.OTHER, exercise("\"implement\": \"other\",").resolvedImplement)
    }

    @Test
    fun `a dumbbell means a pair with no count declared`() {
        val e = exercise("\"implement\": \"dumbbell\",")
        assertNull(e.implementCount, "the raw key still reads what the author wrote")
        assertEquals(2, e.resolvedImplementCount)
    }

    @Test
    fun `a declared count beats the implied pair`() {
        assertEquals(3, exercise("\"implement\": \"dumbbell\", \"implementCount\": 3,").resolvedImplementCount)
    }

    @Test
    fun `no other implement gains a count it was not given`() {
        assertNull(exercise("\"implement\": \"barbell\",").resolvedImplementCount)
        assertNull(exercise("\"implement\": \"other\",").resolvedImplementCount)
        assertEquals(2, exercise("\"implement\": \"other\", \"implementCount\": 2,").resolvedImplementCount)
    }

    @Test
    fun `a bar declared in pounds and the same bar in kilograms are one number`() {
        val pounds = exercise("\"implement\": \"barbell\", \"bar_lb\": 35,").resolvedBarKg!!
        val kilos = exercise("\"implement\": \"barbell\", \"bar_kg\": 15.876,").resolvedBarKg!!
        assertEquals(15.876, Math.round(pounds * 1000.0) / 1000.0)
        assertEquals(15.876, kilos)
    }

    @Test
    fun `an unrecognised implement resolves to other rather than throwing`() {
        assertEquals(Implement.OTHER, exercise("\"implement\": \"barbel\",").resolvedImplement)
    }
}
