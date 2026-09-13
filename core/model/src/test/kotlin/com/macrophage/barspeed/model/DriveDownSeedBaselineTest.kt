package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app already knows, and already publishes, about a lift whose drive
 * goes DOWN -- pinned before anything is added for #263, so the fix can be
 * read as a change to what is SAID and not to what is resolved.
 *
 * Field-39 ran four `lat_pulldown` sets from a plan that declared
 * `sensorOnStack` and `sensorInverted` and no `concentric`. Every one resolved
 * drive-up and was cued on the return. These three facts are why:
 *
 * 1. None of the drive-down ids is an [ExerciseDef.SEED] entry, so there is no
 *    built-in value for an omitted key to fall back to -- the Kotlin default
 *    `concentricUp = true` stands.
 * 2. [SetGeometryPolicy.describe] already reports that as
 *    [GeometrySource.DEFAULT], never INFERRED, because the id is deliberately
 *    never read for words.
 * 3. Nothing about it reaches the lifter at import time, which is the defect.
 *
 * Every assertion here stays true after the fix: #263 adds a warning and a
 * manifest key, and changes no resolved value.
 */
class DriveDownSeedBaselineTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun planExercise(id: String, keys: String): PlanExerciseDef {
        val plan =
            """
            {"schemaVersion": "1.3", "planName": "t", "sessions": [{"name": "s",
              "exercises": [{"exercise": "$id"$keys,
                "sets": [{"reps": 8, "load_kg": 40, "tempo": "1120"}]}]}]}
            """.trimIndent()
        return json.decodeFromString(PlanFile.serializer(), plan).sessions[0].exercises[0]
    }

    /**
     * The ids the plan schema's own `concentric` description singles out --
     * "Use 'down' for leg curls, lat pulldowns and pushdowns" -- and which
     * [ExerciseDef.concentricUp]'s KDoc names as the lifts whose drive goes
     * down. Written out here rather than read off a table so that this pin
     * does not agree with the fix by construction.
     */
    private val driveDown = listOf(
        "lat_pulldown",
        "triceps_pushdown",
        "leg_curl",
        "seated_leg_curl",
        "lying_leg_curl",
    )

    @Test
    fun `no drive-down id is a seed entry, so an omitted concentric has nothing to fall back to`() {
        driveDown.forEach { id ->
            assertNull(ExerciseDef.seedById(id), "$id is seeded now; #263's premise has to be re-decided")
        }
    }

    /** The app ships a stack mount for every one of them; it ships no drive. */
    @Test
    fun `every drive-down id already rides a stack by construction`() {
        driveDown.forEach { id ->
            assertTrue(ExerciseDef.ridesStack(id), "$id lost its stack-mount seed")
        }
    }

    /**
     * The resolved value and its published provenance, on field-39's exact
     * declaration: stack and inversion declared, `concentric` omitted.
     */
    @Test
    fun `field-39's pulldown declaration resolves drive-up and publishes it as a default`() {
        val declared = planExercise("lat_pulldown", ""","sensorOnStack": true, "sensorInverted": true""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("lat_pulldown", "Lat Pulldown"), declared)
        assertTrue(used.concentricUp, "the omitted key leaves the Kotlin default standing")

        val described = SetGeometryPolicy.describe(used, declared)
        assertEquals(GeometrySource.DEFAULT, described.sources.concentric)
        assertEquals(GeometrySource.DECLARED, described.sources.sensorOnStack)
    }

    /** A declared "down" is honoured and reported as the plan's own word. */
    @Test
    fun `a declared concentric down resolves drive-down and publishes it as declared`() {
        val declared = planExercise("lat_pulldown", ""","concentric": "down"""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("lat_pulldown", "Lat Pulldown"), declared)
        assertTrue(!used.concentricUp)
        assertEquals(GeometrySource.DECLARED, SetGeometryPolicy.describe(used, declared).sources.concentric)
    }
}
