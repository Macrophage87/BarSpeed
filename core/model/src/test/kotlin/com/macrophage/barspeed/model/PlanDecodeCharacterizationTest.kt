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
 * Characterization of how a plan document was turned into a [PlanFile] BEFORE
 * this branch. Written to record the behaviour being replaced, and kept
 * afterwards because a record of what changed is worth more than a record of
 * what is.
 *
 * As it stood, `PlanRepository` took a `Json { ignoreUnknownKeys = false }`
 * constructor parameter and decoded with it at two call sites: `importPlan`,
 * which reported the failure, and `decode`, which swallowed it to null. That
 * parameter no longer exists — both call sites now go through
 * [PlanImport.parse] — so the strict decoder here is this file's own, built
 * locally because `:core:data` has no test source directory.
 *
 * It was always its own: nothing mechanically tied it to `PlanRepository`, and
 * it would have kept passing if that configuration had changed underneath it.
 * The guarantee that the two call sites cannot diverge was never this file
 * either. It is that they share one decoder with no seam between them, which
 * is a compile-time property of `PlanRepository`, not a test — and it is
 * stronger now than when this was written, because there is no longer a
 * parameter to pass a different decoder through.
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
        // back_squat is in [ExerciseDef.SEED], declared DYNAMIC there.
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

        // Only the first line of the exception was ever shown to the lifter, so
        // only the first line is pinned.
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
        // Written before the app accepted unknown keys at all, to pin the
        // hazard that accepting them would create. The app now takes this
        // document, so the hazard is live and a warning is what stands against
        // it: "loadKg" is not the wire name -- [PlanSetDef] renames it to
        // `load_kg` -- so a lenient decode drops it and the set becomes
        // indistinguishable from an intended bodyweight set. Neither
        // validate() nor warnings() has anything to say about it; only the
        // near-miss warning in [PlanImport] does.
        val plan = lenient.decodeFromString(PlanFile.serializer(), documentWithUnknownKeys)
        val set = plan.sessions[0].exercises[0].sets[0]

        assertNull(set.loadKg, "loadKg is not load_kg")
        assertNull(set.resolvedLoadKg, "the set resolves to no load at all")
        assertEquals(emptyList(), plan.validate(), "validate() imposes no load requirement")
        // back_squat is seeded, so an omitted "start" is a real value taken
        // from ExerciseDef.SEED, not a guess -- nothing here is worth flagging.
        assertEquals(emptyList(), plan.warnings(), "warnings() has nothing to say about a seeded exercise")
    }

    @Test
    fun `an unsupported schemaVersion is unreachable behind the unknown-key failure`() {
        // 9.9 rather than "one version up", which is what this held until the
        // implement-count change: a fixture pinned one bump ahead has to be
        // shuffled on every bump, and 1.5 became a real version in the commit
        // after this one. 9.9 is the shape PlanValidationTest already uses and
        // it survives every bump this repo will ever make.
        val v99 = documentWithUnknownKeys.replace("\"1.3\"", "\"9.9\"")
        val failure = assertFailsWith<Exception> { strict.decodeFromString(PlanFile.serializer(), v99) }

        // Same message as the 1.3 document: the version is never looked at.
        assertContains(failure.message.orEmpty(), "Encountered an unknown key 'tempoBias'")

        // Decoded leniently, the version check does run - and rejects 9.9. The
        // supported list is spelled out rather than read from the constant:
        // built from SUPPORTED_SCHEMA_VERSIONS it would be an assertion that
        // cannot fail, and this is the only place in the test suite where the
        // whole published list is written down.
        val errors = lenient.decodeFromString(PlanFile.serializer(), v99).validate()
        assertEquals(
            listOf(
                "Unsupported schemaVersion '9.9' " +
                    "(expected one of 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12)",
            ),
            errors,
        )

        // The version the app's own prompt asks for validates clean. Read from
        // the constant, so this half cannot drift a version behind it; what
        // the constant IS is pinned by SchemaContractTest against both the
        // published schema and the prompt.
        val current = documentWithUnknownKeys.replace("\"1.3\"", "\"${PlanFile.SCHEMA_VERSION}\"")
        assertEquals(emptyList(), lenient.decodeFromString(PlanFile.serializer(), current).validate())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the wire key set of every plan level`() {
        // The keys an unknown-key check would have to know about, taken from the
        // serializers rather than retyped, so a @SerialName rename moves them.
        assertEquals(
            setOf("schemaVersion", "planName", "notes", "bodyweight_kg", "bodyweight_lb", "sessions"),
            PlanFile.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            setOf("name", "notes", "exercises"),
            PlanSessionDef.serializer().descriptor.elementNames.toSet(),
        )
        // This literal is the third independent statement of the exercise key
        // set, after SchemaContractTest's and the published schema's. It moves
        // in the SAME commit as the property, and cannot be red-before-green
        // for a structural reason worth naming: the descriptor it is compared
        // against is derived from the data class, so the key does not exist to
        // be asserted until the commit that adds it.
        assertEquals(
            setOf(
                "exercise", "notes", "description", "additional_notes", "start", "concentric",
                "sensorInverted", "sensorOnStack", "travelRatio", "plane", "bodyweight",
                "implementCount", "optional", "progression", "kind", "prep_s", "sensors", "sets",
            ),
            PlanExerciseDef.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            setOf(
                "reps", "duration_s", "load_kg", "load_lb", "tempo", "side", "note",
                "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s", "sensors", "warmup",
            ),
            PlanSetDef.serializer().descriptor.elementNames.toSet(),
        )
    }

    @Test
    fun `a set's shape and an exercise's kind are two different questions`() {
        // Premise pin only. [PlanSetDef.isTimed] asks what the set prescribes;
        // [ExerciseDef.isTimed] asks what the movement is. A plan may
        // prescribe reps of a built-in hold, and then the two disagree. This
        // asserts NOTHING about PlannedSlot.isTimed, which is the place that
        // conflated them and which lives in :app, where no test in this repo
        // can reach it.
        val repSet = PlanSetDef(reps = 12)
        val heldSet = PlanSetDef(durationS = 60)
        // [PlanSetDef.validate] rejects this one, but it is constructible and
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
