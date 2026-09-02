package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The precedence that decides which way a lift moves, and where each answer
 * came from.
 *
 * This is the code that used to live in `app/.../PlanQueue.kt`, inside a
 * suspend extension on `SessionRepository` that no test on the CI path calls
 * — so a mistake here reached the DSP, the voice guide and every recorded set
 * with nothing to catch it. [SetGeometryPolicy.resolve] is
 * that code moved without change and is pinned branch by branch below.
 *
 * [SetGeometryPolicy.describe] is the new half. It reads its values off the
 * definition that was actually used rather than recomputing them from the plan,
 * which is the property that keeps a published declaration from drifting away
 * from the numbers it describes.
 */
class SetGeometryPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun declared(id: String, declarations: String = ""): PlanExerciseDef {
        val plan =
            """
            {
              "schemaVersion": "1.4",
              "planName": "P",
              "sessions": [
                { "name": "S", "exercises": [ { "exercise": "$id"$declarations, "sets": [ { "reps": 5 } ] } ] }
              ]
            }
            """.trimIndent()
        return json.decodeFromString(PlanFile.serializer(), plan).sessions[0].exercises[0]
    }

    private fun seed(id: String): ExerciseDef = ExerciseDef.seedById(id)!!

    /** A built-in definition with every geometry value set away from its default. */
    private val opinionated =
        ExerciseDef(
            id = "cable_row",
            displayName = "Cable Row",
            startsWith = StartPhase.CONCENTRIC,
            kind = ExerciseKind.EXPLOSIVE,
            concentricUp = false,
            sensorInverted = true,
            travelRatio = 2.0,
            horizontal = true,
            sensorOnStack = true,
            bodyweight = true,
        )

    // ---- resolve: what the record queue does --------------------------------

    /**
     * The whole point of the precedence: an omitted key defers to the built-in
     * definition instead of overwriting it with a Kotlin default. Asserted
     * against a base whose every value differs from its default, so a policy
     * that silently reset one would red rather than agree by coincidence.
     */
    @Test
    fun `an omitted declaration leaves every built-in value standing`() {
        val out = SetGeometryPolicy.resolve(opinionated, declared("cable_row"))
        assertEquals(StartPhase.CONCENTRIC, out.startsWith)
        assertEquals(false, out.concentricUp)
        assertEquals(2.0, out.travelRatio)
        assertEquals(true, out.horizontal)
        // kind comes from effectiveKind, which consults the SEED list rather
        // than `base`; cable_row is not seeded, so it is guessed from the id.
        assertEquals(ExerciseDef.inferKind("cable_row"), out.kind)
    }

    @Test
    fun `no plan at all leaves the definition exactly as it was`() {
        assertEquals(opinionated, SetGeometryPolicy.resolve(opinionated, null))
    }

    @Test
    fun `a declared start position overrides the built-in start phase`() {
        assertEquals(StartPhase.ECCENTRIC, seed("bench_press").startsWith)
        val out = SetGeometryPolicy.resolve(seed("bench_press"), declared("bench_press", ""","start":"bottom""""))
        assertEquals(StartPhase.CONCENTRIC, out.startsWith)
    }

    @Test
    fun `a declared drive direction overrides the built-in one`() {
        assertEquals(true, seed("back_squat").concentricUp)
        val down = SetGeometryPolicy.resolve(seed("back_squat"), declared("back_squat", ""","concentric":"down""""))
        assertEquals(false, down.concentricUp)
        val up = SetGeometryPolicy.resolve(opinionated, declared("cable_row", ""","concentric":"up""""))
        assertEquals(true, up.concentricUp)
    }

    @Test
    fun `a declared plane overrides the built-in one in both directions`() {
        val flat = SetGeometryPolicy.resolve(seed("back_squat"), declared("back_squat", ""","plane":"horizontal""""))
        assertEquals(true, flat.horizontal)
        val upright = SetGeometryPolicy.resolve(opinionated, declared("cable_row", ""","plane":"vertical""""))
        assertEquals(false, upright.horizontal)
    }

    @Test
    fun `a declared travel ratio overrides the built-in one`() {
        val out = SetGeometryPolicy.resolve(opinionated, declared("cable_row", ""","travelRatio":3.0"""))
        assertEquals(3.0, out.travelRatio)
    }

    @Test
    fun `a declared kind beats the built-in definition`() {
        assertEquals(ExerciseKind.HOLD, seed("plank").kind)
        val out = SetGeometryPolicy.resolve(seed("plank"), declared("plank", ""","kind":"dynamic""""))
        assertEquals(ExerciseKind.DYNAMIC, out.kind)
    }

    /**
     * The three flags that cannot express omission are assigned
     * unconditionally, so a plan silently clears a built-in true. Pinned as it
     * behaves, not as it ought to: this is latent today because no seed sets
     * any of the three, and the fix is to make them nullable on the plan side,
     * which is its own change. If that lands, this test reds and says so.
     */
    @Test
    fun `the three non-nullable flags are taken from the plan even when it said nothing`() {
        val out = SetGeometryPolicy.resolve(opinionated, declared("cable_row"))
        assertEquals(false, out.sensorInverted, "a built-in true was cleared by an omitted key")
        assertEquals(false, out.sensorOnStack, "a built-in true was cleared by an omitted key")
        assertEquals(false, out.bodyweight, "a built-in true was cleared by an omitted key")
    }

    @Test
    fun `a declared true on those three flags reaches the resolved definition`() {
        val plan =
            declared("back_squat", ""","sensorInverted":true,"sensorOnStack":true,"bodyweight":true""")
        val out = SetGeometryPolicy.resolve(seed("back_squat"), plan)
        assertTrue(out.sensorInverted && out.sensorOnStack && out.bodyweight)
    }

    /** Nothing but geometry moves: the id and display name are the exercise's own. */
    @Test
    fun `resolving changes only the geometry, never which exercise it is`() {
        val base = seed("bench_press")
        val out = SetGeometryPolicy.resolve(base, declared("bench_press", ""","start":"bottom""""))
        assertEquals(base.id, out.id)
        assertEquals(base.displayName, out.displayName)
        assertEquals(base.usesBarbell, out.usesBarbell)
        assertNotEquals(base.startsWith, out.startsWith)
    }

    // ---- describe: the values -----------------------------------------------

    /**
     * Values are copied from the definition that was used, not recomputed.
     *
     * This is the property that matters most. Anything else — re-reading the
     * plan, re-consulting the seed list — could publish a geometry the analysis
     * never ran with, which is worse than publishing nothing.
     */
    @Test
    fun `every described value is the one the definition carries`() {
        val g = SetGeometryPolicy.describe(opinionated, null)
        assertEquals(opinionated.startsWith, g.startsWith)
        assertEquals(opinionated.concentricUp, g.concentricUp)
        assertEquals(opinionated.horizontal, g.horizontal)
        assertEquals(opinionated.sensorOnStack, g.sensorOnStack)
        assertEquals(opinionated.sensorInverted, g.sensorInverted)
        assertEquals(opinionated.travelRatio, g.travelRatio)
        assertEquals(opinionated.kind, g.kind)
        assertEquals(opinionated.bodyweight, g.bodyweight)
    }

    /** What the record flow actually does, end to end, for a declared leg curl. */
    @Test
    fun `a declared leg curl is described as driving downward from the top`() {
        val plan =
            declared("seated_leg_curl", ""","start":"top","concentric":"down","sensorOnStack":true""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("seated_leg_curl", "Seated Leg Curl"), plan)
        val g = SetGeometryPolicy.describe(used, plan)
        // Starting at the top of a lift whose drive goes DOWN means the first
        // phase is the concentric -- which is the whole reason a positional
        // tempo cannot be read without this.
        assertEquals(StartPhase.CONCENTRIC, g.startsWith)
        assertEquals(false, g.concentricUp)
        assertEquals(true, g.sensorOnStack)
        assertEquals(GeometrySource.DECLARED, g.sources.startsWith)
        assertEquals(GeometrySource.DECLARED, g.sources.concentric)
    }

    // ---- describe: provenance -----------------------------------------------

    @Test
    fun `a declared value is reported as declared`() {
        val plan =
            declared(
                "back_squat",
                ""","start":"bottom","concentric":"down","plane":"horizontal","kind":"hold","travelRatio":2.0""",
            )
        val g = SetGeometryPolicy.describe(SetGeometryPolicy.resolve(seed("back_squat"), plan), plan)
        assertEquals(GeometrySource.DECLARED, g.sources.startsWith)
        assertEquals(GeometrySource.DECLARED, g.sources.concentric)
        assertEquals(GeometrySource.DECLARED, g.sources.plane)
        assertEquals(GeometrySource.DECLARED, g.sources.kind)
        assertEquals(GeometrySource.DECLARED, g.sources.travelRatio)
    }

    @Test
    fun `an undeclared value on a built-in lift is reported as seeded`() {
        val g = SetGeometryPolicy.describe(seed("back_squat"), declared("back_squat"))
        assertEquals(GeometrySource.SEEDED, g.sources.startsWith)
        assertEquals(GeometrySource.SEEDED, g.sources.concentric)
        assertEquals(GeometrySource.SEEDED, g.sources.plane)
        assertEquals(GeometrySource.SEEDED, g.sources.kind)
        assertEquals(GeometrySource.SEEDED, g.sources.travelRatio)
    }

    @Test
    fun `an ad-hoc set with no plan at all is still described, as seeded`() {
        val g = SetGeometryPolicy.describe(seed("deadlift"), null)
        assertEquals(StartPhase.CONCENTRIC, g.startsWith)
        assertEquals(GeometrySource.SEEDED, g.sources.startsWith)
        assertEquals(GeometrySource.SEEDED, g.sources.kind)
    }

    /**
     * The split that earns [GeometrySource.DEFAULT] its place. On an id the app
     * does not ship, the start phase and the kind are genuinely guessed from
     * the id string. The drive direction, the plane and the travel ratio are
     * not: no inference for them exists — [ExerciseDef.concentricUp] documents
     * that guessing it is deliberately refused, because it reasons about the
     * lifter while the sensor usually rides the machine. Reporting those three
     * as inferred would claim reasoning the app does not do.
     */
    @Test
    fun `an unknown id reports a guess as inferred and a bare default as default`() {
        val plan = declared("pallof_hold")
        val used = SetGeometryPolicy.resolve(ExerciseDef("pallof_hold", "Pallof Hold"), plan)
        val g = SetGeometryPolicy.describe(used, plan)
        assertEquals(ExerciseKind.HOLD, g.kind, "the kind should have been guessed from the id")
        assertEquals(GeometrySource.INFERRED, g.sources.startsWith)
        assertEquals(GeometrySource.INFERRED, g.sources.kind)
        assertEquals(GeometrySource.DEFAULT, g.sources.concentric)
        assertEquals(GeometrySource.DEFAULT, g.sources.plane)
        assertEquals(GeometrySource.DEFAULT, g.sources.travelRatio)
    }

    /** Declared beats both, one field at a time, with the rest left where they were. */
    @Test
    fun `provenance is decided per value, not for the object as a whole`() {
        val plan = declared("pallof_hold", ""","concentric":"down"""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("pallof_hold", "P"), plan)
        val g = SetGeometryPolicy.describe(used, plan)
        assertEquals(GeometrySource.DECLARED, g.sources.concentric)
        assertEquals(GeometrySource.INFERRED, g.sources.startsWith)
        assertEquals(GeometrySource.DEFAULT, g.sources.plane)
    }

    /**
     * A `start` or `kind` naming something the app does not understand is not a
     * declaration. The resolved value falls back, and the provenance has to
     * fall back with it — reporting "declared" for a value the declaration
     * failed to set would be the worst of both.
     */
    @Test
    fun `an unparseable declaration is not reported as declared`() {
        val plan = declared("back_squat", ""","start":"sideways","kind":"isometric"""")
        val g = SetGeometryPolicy.describe(SetGeometryPolicy.resolve(seed("back_squat"), plan), plan)
        assertEquals(GeometrySource.SEEDED, g.sources.startsWith)
        assertEquals(GeometrySource.SEEDED, g.sources.kind)
    }

    /** The blob is stored in Room, so it has to survive the round trip whole. */
    @Test
    fun `a described geometry round-trips through its stored form`() {
        val plan =
            declared("seated_leg_curl", ""","start":"top","concentric":"down","travelRatio":2.0""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("seated_leg_curl", "S"), plan)
        val g = SetGeometryPolicy.describe(used, plan)
        val text = json.encodeToString(ResolvedGeometry.serializer(), g)
        assertEquals(g, json.decodeFromString(ResolvedGeometry.serializer(), text))
    }

    // ---- the field-37 shape, as it behaves today (#223) ---------------------

    /**
     * The machine ids that ride a weight stack are not in [ExerciseDef.SEED] at
     * all, so an `assisted_pull_up` reaches the record queue as an ad-hoc
     * definition built from the id -- with every geometry value at its Kotlin
     * default, `sensorOnStack` included.
     *
     * Pinned because the field-37 change rests on it: whatever supplies a
     * stack default cannot be a SEED entry without also making
     * [SetGeometryPolicy.describe] report SEEDED for `concentric`, `plane` and
     * `travelRatio` on those ids, which no seed entry would have decided.
     */
    @Test
    fun `no built-in definition names a stack machine`() {
        for (id in listOf("assisted_pull_up", "lat_pulldown", "seated_row", "seated_leg_curl")) {
            assertNull(ExerciseDef.seedById(id), "$id is seeded, so the geometry sources it publishes changed")
        }
    }

    /**
     * Field-37: the plan omitted `sensorOnStack` on an assisted pull-up, which
     * runs on a pin-selected assist stack. Characterized as it stands before
     * the fix -- the set is analysed as though the sensor rode what the lifter
     * holds.
     */
    @Test
    fun `an omitted stack key on an assisted pull-up resolves to bar-mounted`() {
        val plan = declared("assisted_pull_up")
        val used = SetGeometryPolicy.resolve(ExerciseDef("assisted_pull_up", "Assisted Pull-Up"), plan)
        assertEquals(false, used.sensorOnStack)
    }

    /**
     * The provenance object's published shape, key by key, so a key arriving or
     * leaving is a decision somebody has to make rather than a diff nobody
     * reads. `sensorOnStack` is absent here today.
     */
    @Test
    fun `the published provenance keys are exactly these five`() {
        val g = SetGeometryPolicy.describe(opinionated, null)
        assertEquals(
            setOf("startsWith", "concentric", "plane", "kind", "travelRatio"),
            json.parseToJsonElement(json.encodeToString(GeometrySources.serializer(), g.sources))
                .jsonObject.keys,
        )
    }
}
