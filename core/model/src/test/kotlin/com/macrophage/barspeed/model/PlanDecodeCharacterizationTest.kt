package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization of how a plan document is turned into a [PlanFile] today.
 *
 * `PlanRepository` decodes with `Json { ignoreUnknownKeys = false }`
 * (`PlanRepository.kt:28`) at two call sites — `importPlan:41`, which reports
 * the failure, and `decode:72`, which swallows it to null. This file
 * REPRODUCES that configuration rather than importing it, because
 * `:core:data` has no test source directory. Nothing mechanically ties this
 * reproduction to `PlanRepository.kt:28`: it would keep passing if that line
 * changed underneath it.
 *
 * The guarantee that the two call sites cannot diverge is therefore not this
 * file. It is that they share one decoder with no seam between them, which is
 * a compile-time property of `PlanRepository`, not a test.
 */
class PlanDecodeCharacterizationTest {
    private val strict = Json { ignoreUnknownKeys = false }
    private val lenient = Json { ignoreUnknownKeys = true }

    /** Three unknown keys, one per level, plus a near miss on a renamed one. */
    private val documentWithUnknownKeys =
        """
        {"schemaVersion":"1.3","planName":"P","sessions":[
          {"name":"S","exercises":[
            {"exercise":"back_squat","tempoBias":1,"rpeTarget":8,
             "sets":[{"reps":5,"loadKg":100}]}
          ]}
        ]}
        """.trimIndent()

    @Test
    fun `a declared kind is a known key now, where it used to be a hard rejection`() {
        // The behaviour change this commit introduces, stated as a test rather
        // than left to be noticed: every plan carrying "kind" was rejected
        // outright until the key existed on the model.
        val doc =
            """
            {"schemaVersion":"1.4","planName":"P","sessions":[
              {"name":"S","exercises":[
                {"exercise":"back_squat","kind":"explosive","sets":[{"reps":5,"load_kg":100}]}
              ]}
            ]}
            """.trimIndent()
        val plan = strict.decodeFromString(PlanFile.serializer(), doc)
        val exercise = plan.sessions[0].exercises[0]

        assertEquals(emptyList(), plan.validate())
        assertEquals(ExerciseKind.EXPLOSIVE, exercise.kindOverride)
        // back_squat is a seed exercise declared DYNAMIC (ExerciseDef.kt:83).
        // The declaration beats it, and overriding something the app ships
        // with is the case that most needs saying out loud - the plan is
        // telling the app to track a squat on peak velocity with no tempo.
        assertEquals(ExerciseKind.DYNAMIC, checkNotNull(ExerciseDef.seedById("back_squat")).kind)
        assertEquals(ExerciseKind.EXPLOSIVE, exercise.effectiveKind)
        assertEquals(
            listOf(
                "sessions[0].exercises[0]: back_squat is built in as a dynamic lift, but this plan " +
                    "declares \"kind\": \"explosive\" - the app will follow the plan.",
            ),
            plan.warnings(),
        )
    }

    @Test
    fun `an unknown key is a hard rejection, and only the first one is named`() {
        val failure = assertFailsWith<Exception> {
            strict.decodeFromString(PlanFile.serializer(), documentWithUnknownKeys)
        }
        val firstLine = failure.message?.lineSequence()?.first().orEmpty()

        // PlanRepository.kt:44 keeps exactly this line and shows it to the lifter.
        assertContains(firstLine, "Encountered an unknown key 'tempoBias'")
        // The path IS named — but it names the PRECEDING SIBLING key, not the
        // offending one, so pasting it back sends the plan's author to `exercise`.
        assertContains(firstLine, "at path: $.sessions[0].exercises[0].exercise")
        // Two further unknown keys are present and neither is mentioned: the
        // decode aborts at the first, so one round trip fixes one key.
        assertTrue("rpeTarget" !in firstLine, "second unknown key should not be named: $firstLine")
        assertTrue("loadKg" !in firstLine, "third unknown key should not be named: $firstLine")
    }

    @Test
    fun `a near miss on a renamed key silently empties the load when decoded leniently`() {
        // Not what the app does today - the app rejects this document outright.
        // Pinned because it is the hazard that accepting unknown keys creates:
        // "loadKg" is not the wire name, `load_kg` is (Plan.kt:195), so a
        // lenient decode drops it and the set becomes indistinguishable from an
        // intended bodyweight set. Validation has nothing to say about it.
        val plan = lenient.decodeFromString(PlanFile.serializer(), documentWithUnknownKeys)
        val set = plan.sessions[0].exercises[0].sets[0]

        assertNull(set.loadKg, "loadKg is not load_kg")
        assertNull(set.resolvedLoadKg, "the set resolves to no load at all")
        assertEquals(emptyList(), plan.validate(), "validate() imposes no load requirement")
        assertEquals(emptyList(), plan.warnings(), "warnings() only speaks about a declared start")
    }

    @Test
    fun `an unsupported schemaVersion is unreachable behind the unknown-key failure`() {
        val v15 = documentWithUnknownKeys.replace("\"1.3\"", "\"1.5\"")
        val failure = assertFailsWith<Exception> { strict.decodeFromString(PlanFile.serializer(), v15) }

        // Same message as the 1.3 document: the version is never looked at.
        assertContains(failure.message.orEmpty(), "Encountered an unknown key 'tempoBias'")

        // Decoded leniently, the version check does run - and rejects 1.5.
        val errors = lenient.decodeFromString(PlanFile.serializer(), v15).validate()
        assertEquals(
            listOf("Unsupported schemaVersion '1.5' (expected one of 1.0, 1.1, 1.2, 1.3, 1.4)"),
            errors,
        )

        // 1.4 is the version this change introduces, and it validates clean.
        // This assertion was written against 1.4 as the REJECTED version one
        // commit ago; the flip is the behaviour change, not an oversight.
        val v14 = documentWithUnknownKeys.replace("\"1.3\"", "\"1.4\"")
        assertEquals(emptyList(), lenient.decodeFromString(PlanFile.serializer(), v14).validate())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the wire key set of every plan level`() {
        // The keys an unknown-key check would have to know about, taken from the
        // serializers rather than retyped, so a @SerialName rename moves them.
        assertEquals(
            setOf("schemaVersion", "planName", "notes", "sessions"),
            PlanFile.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            setOf("name", "notes", "exercises"),
            PlanSessionDef.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            setOf(
                "exercise", "notes", "start", "concentric", "sensorInverted", "sensorOnStack",
                "travelRatio", "plane", "bodyweight", "optional", "kind", "sets",
            ),
            PlanExerciseDef.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            setOf(
                "reps", "duration_s", "load_kg", "load_lb", "tempo", "side", "note",
                "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s",
            ),
            PlanSetDef.serializer().descriptor.elementNames.toSet(),
        )
    }

    @Test
    fun `a set's shape and an exercise's kind are two different questions`() {
        // Premise pin only. `PlanSetDef.isTimed` (Plan.kt:211) asks what the set
        // prescribes; `ExerciseDef.isTimed` (:68) asks what the movement is.
        // A plan may prescribe reps of a built-in hold, and then the two
        // disagree. This asserts NOTHING about `PlannedSlot.isTimed`
        // (RecordViewModel.kt:67), which is the place that conflates them and
        // which lives in `:app`, where no test in this repo can reach it.
        val repSet = PlanSetDef(reps = 12)
        val heldSet = PlanSetDef(durationS = 60)
        // validate() rejects this one (Plan.kt:215), but it is constructible and
        // it is the case that separates "duration_s is present" from "reps is
        // absent" - two readings that agree on every valid set.
        val shapelessSet = PlanSetDef()

        assertEquals(false, repSet.isTimed, "a set prescribing reps is not timed")
        assertEquals(true, heldSet.isTimed, "a set prescribing duration_s is timed")
        assertEquals(false, shapelessSet.isTimed, "timed means duration_s is present, not reps absent")

        val plank = checkNotNull(ExerciseDef.seedById("plank"))
        assertEquals(ExerciseKind.HOLD, plank.kind)
        assertEquals(true, plank.isTimed, "the movement is a hold whatever a set prescribes")
    }
}
