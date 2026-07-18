package com.macrophage.accelerometerlifting.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val validPlan =
        """
        {
          "schemaVersion": "1.0",
          "planName": "Test Block",
          "sessions": [{
            "name": "Lower A",
            "exercises": [{
              "exercise": "back_squat",
              "sets": [{ "reps": 5, "load_kg": 120, "tempo": "4010",
                         "targetMeanConcentricVelocity_mps": 0.5,
                         "velocityLossStop_pct": 20, "rest_s": 180 }]
            }]
          }]
        }
        """.trimIndent()

    @Test
    fun `valid plan round-trips and validates clean`() {
        val plan = json.decodeFromString(PlanFile.serializer(), validPlan)
        assertEquals("Test Block", plan.planName)
        assertEquals(120.0, plan.sessions[0].exercises[0].sets[0].loadKg)
        assertTrue(plan.validate().isEmpty())
    }

    @Test
    fun `invalid tempo and reps are reported with paths`() {
        val bad =
            validPlan
                .replace("\"4010\"", "\"9z99\"")
                .replace("\"reps\": 5", "\"reps\": 0")
        val plan = json.decodeFromString(PlanFile.serializer(), bad)
        val errors = plan.validate()
        assertTrue(errors.any { it.contains("reps must be positive") })
        assertTrue(errors.any { it.contains("tempo") })
    }

    @Test
    fun `wrong schema version is rejected`() {
        val plan = json.decodeFromString(PlanFile.serializer(), validPlan.replace("1.0", "9.9"))
        assertTrue(plan.validate().any { it.contains("schemaVersion") })
    }
}
