package com.macrophage.barspeed.model

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
    fun `accepts load in pounds and resolves to kg`() {
        val lbPlan = validPlan.replace("\"load_kg\": 120", "\"load_lb\": 225")
        val plan = json.decodeFromString(PlanFile.serializer(), lbPlan)
        assertTrue(plan.validate().isEmpty())
        val resolved = plan.sessions[0].exercises[0].sets[0].resolvedLoadKg
        assertTrue(resolved != null && kotlin.math.abs(resolved - 102.06) < 0.01)
    }

    @Test
    fun `rejects both load units, accepts bodyweight (neither)`() {
        val both = validPlan.replace("\"load_kg\": 120", "\"load_kg\": 120, \"load_lb\": 265")
        val neither = validPlan.replace("\"load_kg\": 120, ", "")
        val bothErrors = json.decodeFromString(PlanFile.serializer(), both).validate()
        val neitherErrors = json.decodeFromString(PlanFile.serializer(), neither).validate()
        assertTrue(bothErrors.any { it.contains("both load_kg and load_lb") })
        assertTrue(neitherErrors.isEmpty(), "bodyweight sets (no load) must validate: $neitherErrors")
    }

    @Test
    fun `wrong schema version is rejected`() {
        val plan = json.decodeFromString(PlanFile.serializer(), validPlan.replace("1.0", "9.9"))
        assertTrue(plan.validate().any { it.contains("schemaVersion") })
    }

    @Test
    fun `timed sets validate with duration_s and no load`() {
        val timed =
            """
            {
              "schemaVersion": "1.1",
              "planName": "Core Block",
              "sessions": [{
                "name": "Core",
                "exercises": [
                  { "exercise": "plank", "sets": [{ "duration_s": 60, "rest_s": 60 }] },
                  { "exercise": "farmers_walk", "sets": [{ "duration_s": 40, "load_lb": 106, "rest_s": 90 }] }
                ]
              }]
            }
            """.trimIndent()
        val plan = json.decodeFromString(PlanFile.serializer(), timed)
        assertTrue(plan.validate().isEmpty(), plan.validate().joinToString())
        val plankSet = plan.sessions[0].exercises[0].sets[0]
        assertTrue(plankSet.isTimed)
        assertEquals(60, plankSet.durationS)
        assertEquals(null, plankSet.resolvedLoadKg)
    }

    @Test
    fun `rejects reps plus duration, tempo on timed sets, and zero duration`() {
        fun errorsFor(set: String): List<String> {
            val plan =
                """
                {"schemaVersion": "1.1", "planName": "t", "sessions": [{"name": "s",
                  "exercises": [{"exercise": "plank", "sets": [$set]}]}]}
                """.trimIndent()
            return json.decodeFromString(PlanFile.serializer(), plan).validate()
        }
        assertTrue(errorsFor("""{"reps": 5, "duration_s": 60}""").any { it.contains("both reps and duration_s") })
        assertTrue(errorsFor("""{"duration_s": 60, "tempo": "4010"}""").any { it.contains("does not apply") })
        assertTrue(errorsFor("""{"duration_s": 0}""").any { it.contains("duration_s must be positive") })
        assertTrue(errorsFor("""{"rest_s": 60}""").any { it.contains("reps (dynamic set) or duration_s") })
        assertTrue(errorsFor("""{"duration_s": 30, "side": "top"}""").any { it.contains("side") })
        assertTrue(errorsFor("""{"duration_s": 30, "side": "left"}""").isEmpty())
        assertTrue(errorsFor("""{"reps": 8, "load_kg": 20, "side": "right"}""").isEmpty())
    }

    @Test
    fun `exercise start override validates and maps to a phase`() {
        fun planWith(startJson: String): PlanFile {
            val plan =
                """
                {"schemaVersion": "1.2", "planName": "t", "sessions": [{"name": "s",
                  "exercises": [{"exercise": "bench_press"$startJson, "sets": [{"reps": 5, "load_kg": 60}]}]}]}
                """.trimIndent()
            return json.decodeFromString(PlanFile.serializer(), plan)
        }
        val up = planWith(""", "start": "up"""")
        assertTrue(up.validate().isEmpty(), up.validate().joinToString())
        assertEquals(StartPhase.CONCENTRIC, up.sessions[0].exercises[0].startPhaseOverride)

        val down = planWith(""", "start": "down"""")
        assertEquals(StartPhase.ECCENTRIC, down.sessions[0].exercises[0].startPhaseOverride)

        val omitted = planWith("")
        assertTrue(omitted.validate().isEmpty())
        assertEquals(null, omitted.sessions[0].exercises[0].startPhaseOverride)

        assertTrue(planWith(""", "start": "sideways"""").validate().any { it.contains("start") })
    }

    private fun planWith(exercise: String, direction: String): PlanFile {
        val plan =
            """
            {"schemaVersion": "1.3", "planName": "t", "sessions": [{"name": "s",
              "exercises": [{"exercise": "$exercise"$direction,
                "sets": [{"reps": 5, "load_kg": 60, "tempo": "3010"}]}]}]}
            """.trimIndent()
        return json.decodeFromString(PlanFile.serializer(), plan)
    }

    @Test
    fun `start declares where the lift begins, in either vocabulary`() {
        // A machine chest press started from the bottom pins: the same movement
        // pattern as a bench, opposite start. The declaration must win.
        val bottom = planWith("bench_press", """, "start": "bottom"""")
        assertTrue(bottom.validate().isEmpty(), bottom.validate().joinToString())
        assertEquals(StartPhase.CONCENTRIC, bottom.sessions[0].exercises[0].startPhaseOverride)
        // ...but it is worth a non-blocking note, since a bench normally starts at the top.
        assertTrue(bottom.warnings().any { it.contains("normally starts at the top") })

        // "down"/"up" name the first movement and mean the same thing.
        assertEquals(
            planWith("bench_press", """, "start": "top"""").sessions[0].exercises[0].startPhaseOverride,
            planWith("bench_press", """, "start": "down"""").sessions[0].exercises[0].startPhaseOverride,
        )
        // Declaring the natural direction raises no warning.
        assertTrue(planWith("bench_press", """, "start": "top"""").warnings().isEmpty())
        // Omitted → inferred, no override.
        assertEquals(null, planWith("bench_press", "").sessions[0].exercises[0].startPhaseOverride)
        assertTrue(planWith("bench_press", """, "start": "sideways"""").validate().any { it.contains("start") })
    }

    @Test
    fun `concentric direction is independent of where the lift starts`() {
        // A seated leg curl starts at the top (legs extended) and its DRIVE goes
        // down — so the first phase is the concentric, which no start value alone
        // could express.
        val curl = planWith("seated_leg_curl", """, "start": "top", "concentric": "down"""")
        assertTrue(curl.validate().isEmpty(), curl.validate().joinToString())
        val def = curl.sessions[0].exercises[0]
        assertEquals(StartPhase.CONCENTRIC, def.startPhaseOverride)
        assertEquals(false, def.concentricUp)
        assertTrue(def.startsAtTop)

        // Same start, ordinary upward drive → eccentric first (a squat).
        val squat = planWith("back_squat", """, "start": "top"""")
        assertEquals(StartPhase.ECCENTRIC, squat.sessions[0].exercises[0].startPhaseOverride)
        assertTrue(squat.sessions[0].exercises[0].concentricUp)

        // A leg press: starts at the bottom, drives up.
        val press = planWith("single_leg_press", """, "start": "bottom"""")
        assertEquals(StartPhase.CONCENTRIC, press.sessions[0].exercises[0].startPhaseOverride)

        val bad = planWith("back_squat", """, "concentric": "sideways"""")
        assertTrue(bad.validate().any { it.contains("concentric") })
    }

    @Test
    fun `set-level note and exercise-level optional are accepted`() {
        val plan =
            """
            {"schemaVersion": "1.3", "planName": "t", "sessions": [{"name": "s", "exercises": [
              {"exercise": "back_squat", "sets": [{"reps": 5, "load_kg": 60}]},
              {"exercise": "cable_face_pull", "optional": true, "notes": "accessory",
               "sets": [{"reps": 12, "load_kg": 20, "note": "closer to the plate, higher ROM"}]}
            ]}]}
            """.trimIndent()
        val file = json.decodeFromString(PlanFile.serializer(), plan)
        assertTrue(file.validate().isEmpty(), file.validate().joinToString())
        assertEquals(false, file.sessions[0].exercises[0].optional)
        assertEquals(true, file.sessions[0].exercises[1].optional)
        assertEquals("closer to the plate, higher ROM", file.sessions[0].exercises[1].sets[0].note)
    }
}
