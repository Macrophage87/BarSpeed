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
 * overlaying eight declarations. It is a suspend extension on
 * `SessionRepository` in `:app` that no test on the CI path calls, so the
 * precedence it relies on is pinned here on the properties it reads -- before
 * any of it moves.
 *
 * The last test used to be the awkward one and the reason this file exists:
 * three of the eight declarations could not express omission at all, so no
 * amount of care downstream could tell a plan that said `false` from a plan
 * that said nothing. `sensorOnStack` (#223) and `bodyweight` (#227) have since
 * both become nullable; `sensorInverted` is the one still left, and is the
 * rest of #64.
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

    // ---- all three can now say "omitted" ------------------------------------

    /**
     * The third declaration can tell an omitted key from a declared `false` on
     * the wire, which is where that difference has to exist before any
     * precedence can act on it.
     *
     * Read off the SERIALIZER's descriptor rather than off a decoded value,
     * because a decoded value cannot express the difference while the property
     * is a non-null `Boolean`: the assertion would not compile, and a test that
     * fails to compile reds the build for something other than the defect. The
     * descriptor states the wire contract either way, so this test reads the
     * same before and after the widening.
     *
     * The test this replaces asserted the opposite -- that a declared `false`
     * and an omitted key are one value, with nothing downstream able to recover
     * the difference. It is deleted rather than reworded, the way the same
     * sentence was deleted for `sensorOnStack` at #223 and for `bodyweight` at
     * #227.
     */
    @Test
    fun `the sensorInverted declaration can express omission`() {
        val d = PlanExerciseDef.serializer().descriptor
        val i = (0 until d.elementsCount).first { d.getElementName(it) == "sensorInverted" }
        assertTrue(
            d.getElementDescriptor(i).isNullable,
            "sensorInverted is not nullable, so an omitted key and a declared false are one value",
        )
    }

    /** The other two: null is not false, and false is not null. */
    @Test
    fun `an omitted stack key decodes to null and a declared false to false`() {
        assertNull(exercise("seated_row").sensorOnStack)
        assertEquals(false, exercise("seated_row", ""","sensorOnStack":false""").sensorOnStack)
        assertEquals(true, exercise("seated_row", ""","sensorOnStack":true""").sensorOnStack)
    }

    /** The other one: an omitted `bodyweight` key decodes to null, not false (#227). */
    @Test
    fun `an omitted bodyweight key decodes to null and a declared false to false`() {
        assertNull(exercise("pull_up").bodyweight)
        assertEquals(false, exercise("pull_up", ""","bodyweight":false""").bodyweight)
        assertEquals(true, exercise("pull_up", ""","bodyweight":true""").bodyweight)
    }

    /**
     * A declared true is readable.
     *
     * Written as an equality against `true` rather than as a truth assertion on
     * the property itself, so that it reads the same before and after the
     * declaration becomes nullable and cannot be satisfied by a change of type.
     */
    @Test
    fun `a declared true on sensorInverted is readable`() {
        assertEquals(true, exercise("seated_row", ""","sensorInverted":true""").sensorInverted)
    }
}
