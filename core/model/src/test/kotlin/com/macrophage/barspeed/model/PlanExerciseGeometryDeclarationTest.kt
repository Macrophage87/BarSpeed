package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a plan exercise declares about direction and geometry, as the record
 * queue consumes it.
 *
 * `PlanQueue.flattenPlan` builds the one [ExerciseDef] every set of an exercise
 * is recorded and analysed against, by copying the built-in definition and
 * overlaying eight declarations. That file lives in `:app`, which has no test
 * source set, so the precedence it relies on is pinned here on the properties
 * it reads -- before any of it moves.
 *
 * The last test is the awkward one and the reason this file exists: three of
 * the eight declarations cannot express omission at all, so no amount of care
 * downstream can tell a plan that said `false` from a plan that said nothing.
 */
class PlanExerciseGeometryDeclarationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(id: String, declarations: String = ""): PlanExerciseDef {
        val plan =
            """
            {
              "schemaVersion": "1.4",
              "planName": "P",
              "sessions": [
                {
                  "name": "S",
                  "exercises": [
                    { "exercise": "$id"$declarations, "sets": [ { "reps": 5 } ] }
                  ]
                }
              ]
            }
            """.trimIndent()
        return json.decodeFromString(PlanFile.serializer(), plan).sessions[0].exercises[0]
    }

    // ---- start: declared wins, omitted defers -------------------------------

    @Test
    fun `a declared start position overrides the built-in definition`() {
        // bench_press is seeded eccentric-first; "bottom" asks for the opposite.
        assertEquals(StartPhase.CONCENTRIC, exercise("bench_press", ""","start":"bottom"""").startPhaseOverride)
        assertEquals(StartPhase.ECCENTRIC, exercise("bench_press", ""","start":"top"""").startPhaseOverride)
    }

    @Test
    fun `the legacy first-movement spelling means the same as the position spelling`() {
        assertEquals(
            exercise("bench_press", ""","start":"top"""").startPhaseOverride,
            exercise("bench_press", ""","start":"down"""").startPhaseOverride,
        )
        assertEquals(
            exercise("bench_press", ""","start":"bottom"""").startPhaseOverride,
            exercise("bench_press", ""","start":"up"""").startPhaseOverride,
        )
    }

    @Test
    fun `an omitted start declares nothing, leaving the built-in definition to stand`() {
        assertNull(exercise("bench_press").startPhaseOverride)
    }

    /**
     * The start phase is the combination of two declarations, not one. Which
     * phase opens the rep depends on which way the drive goes: starting at the
     * top of a leg curl is starting with the CONCENTRIC.
     */
    @Test
    fun `the start phase combines the start position with the drive direction`() {
        assertEquals(
            StartPhase.ECCENTRIC,
            exercise("leg_curl", ""","start":"top","concentric":"up"""").startPhaseOverride,
        )
        assertEquals(
            StartPhase.CONCENTRIC,
            exercise("leg_curl", ""","start":"top","concentric":"down"""").startPhaseOverride,
        )
    }

    // ---- concentric, plane, travelRatio: declared or absent -----------------

    @Test
    fun `a declared drive direction is readable, and an omitted one reads as up`() {
        assertEquals("down", exercise("leg_curl", ""","concentric":"down"""").concentric)
        assertEquals("up", exercise("leg_curl", ""","concentric":"up"""").concentric)
        assertNull(exercise("leg_curl").concentric)
    }

    @Test
    fun `a declared plane is readable and an omitted plane is null, not vertical`() {
        assertEquals("horizontal", exercise("seated_row", ""","plane":"horizontal"""").plane)
        assertEquals("vertical", exercise("seated_row", ""","plane":"vertical"""").plane)
        assertNull(exercise("seated_row").plane)
    }

    @Test
    fun `a declared travel ratio is readable and an omitted one is null, not one`() {
        assertEquals(2.0, exercise("lat_pulldown", ""","travelRatio":2.0""").travelRatio)
        assertNull(exercise("lat_pulldown").travelRatio)
    }

    // ---- kind: declared, then built in, then guessed ------------------------

    @Test
    fun `a declared kind beats the built-in definition`() {
        // back_squat is seeded DYNAMIC.
        assertEquals(ExerciseKind.EXPLOSIVE, exercise("back_squat", ""","kind":"explosive"""").effectiveKind)
        assertEquals(ExerciseKind.EXPLOSIVE, exercise("back_squat", ""","kind":"explosive"""").kindOverride)
    }

    @Test
    fun `an undeclared kind takes the built-in definition when the id is one the app ships`() {
        assertNull(exercise("plank").kindOverride)
        assertEquals(ExerciseKind.HOLD, exercise("plank").effectiveKind)
    }

    @Test
    fun `an undeclared kind on an unknown id is guessed from the id`() {
        assertNull(ExerciseDef.seedById("pallof_hold"))
        assertEquals(ExerciseKind.HOLD, exercise("pallof_hold").effectiveKind)
        assertEquals(ExerciseDef.inferKind("pallof_hold"), exercise("pallof_hold").effectiveKind)
    }

    /** A kind naming something the app does not have is no declaration at all. */
    @Test
    fun `a kind the app does not know overrides nothing`() {
        assertNull(exercise("back_squat", ""","kind":"isometric"""").kindOverride)
    }

    // ---- the three that cannot say "omitted" --------------------------------

    /**
     * `sensorInverted`, `sensorOnStack` and `bodyweight` are non-nullable
     * `Boolean` on [PlanExerciseDef], so a plan that declared `false` and a
     * plan that said nothing decode to the same value. There is no `?:` for
     * `PlanQueue` to test and nothing downstream can recover the difference.
     *
     * This is why the export publishes no provenance for those three: it would
     * have to invent one. Pinned so the day they become `Boolean?` this test
     * reds and says what changed.
     */
    @Test
    fun `three geometry flags cannot tell a declared false from an omitted key`() {
        val declaredFalse =
            exercise(
                "seated_row",
                ""","sensorInverted":false,"sensorOnStack":false,"bodyweight":false""",
            )
        val omitted = exercise("seated_row")
        assertEquals(declaredFalse.sensorInverted, omitted.sensorInverted)
        assertEquals(declaredFalse.sensorOnStack, omitted.sensorOnStack)
        assertEquals(declaredFalse.bodyweight, omitted.bodyweight)
        assertTrue(!omitted.sensorInverted && !omitted.sensorOnStack && !omitted.bodyweight)
    }

    /** A declared true is readable; only the false/omitted pair collapses. */
    @Test
    fun `a declared true on those three flags is readable`() {
        val on =
            exercise(
                "seated_row",
                ""","sensorInverted":true,"sensorOnStack":true,"bodyweight":true""",
            )
        assertTrue(on.sensorInverted && on.sensorOnStack && on.bodyweight)
    }
}
