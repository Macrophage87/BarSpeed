package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parses a real user-supplied plan (pounds-based, with non-seed exercises). */
class RealPlanFixtureTest {
    @Test
    fun `real lower-body plan parses, validates, and resolves loads`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/real-plan-lower-body.json")) {
                "fixture missing"
            }.bufferedReader().readText()
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString(PlanFile.serializer(), text)

        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
        assertEquals("Mon 20 Jul 2026 - Week 4 Lower Body", plan.planName)

        val session = plan.sessions.single()
        assertEquals(7, session.exercises.size)
        assertEquals(20, session.exercises.sumOf { it.sets.size })

        // 115 lb top squat sets resolve to ~52.2 kg.
        val topSet = session.exercises.first { it.exercise == "back_squat" }.sets[2]
        val kg = checkNotNull(topSet.resolvedLoadKg)
        assertTrue(abs(kg - 52.16) < 0.01, "115 lb should be ~52.16 kg, got $kg")
        assertEquals(0.55, topSet.targetMeanConcentricVelocityMps)

        // Bodyweight sets (load_lb: 0) are valid and resolve to 0 kg.
        val bodyweight = session.exercises.first { it.exercise == "cossack_squat" }.sets[0]
        assertEquals(0.0, bodyweight.resolvedLoadKg)

        // Non-seed exercise ids are allowed; the app creates custom exercises on import.
        assertTrue(session.exercises.any { ExerciseDef.seedById(it.exercise) == null })
    }

    /**
     * This is the actual plan behind the wrong-stroke leg-press session
     * issue #131 describes: five of its seven exercises are ids the app
     * does not ship (rear_foot_elevated_split_squat, cossack_squat,
     * seated_leg_curl, calf_raise, leg_extension), none of them declares
     * "start", and every one of their sets prescribes reps rather than
     * duration_s, so every one reaches segmentation on a guessed direction.
     * back_squat and romanian_deadlift are seeded, so their own omission is
     * a real declaration, not a guess, and neither should warn.
     */
    @Test
    fun `omitting start on a non-seed exercise now warns, once per exercise`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/real-plan-lower-body.json")) {
                "fixture missing"
            }.bufferedReader().readText()
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString(PlanFile.serializer(), text)
        val nonSeedIds = listOf(
            "rear_foot_elevated_split_squat",
            "cossack_squat",
            "seated_leg_curl",
            "calf_raise",
            "leg_extension",
        )
        assertTrue(
            plan.sessions.single().exercises.count { ExerciseDef.seedById(it.exercise) == null } == 5,
            "expected five non-seed exercises in this fixture",
        )

        val startWarnings = plan.warnings().filter { "does not declare \"start\"" in it }
        assertEquals(5, startWarnings.size, startWarnings.joinToString("\n"))
        nonSeedIds.forEach { id ->
            assertTrue(startWarnings.any { id in it }, "expected a start warning naming $id: $startWarnings")
        }
        assertTrue(
            startWarnings.none { "back_squat" in it || "romanian_deadlift" in it },
            "the two seeded lifts have a real start from ExerciseDef.SEED, not a guess: $startWarnings",
        )
    }
}
