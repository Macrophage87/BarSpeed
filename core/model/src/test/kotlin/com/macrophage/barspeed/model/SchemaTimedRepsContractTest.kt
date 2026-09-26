package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export 1.23, a further entry (#71): the published contract lets a TIMED set
 * carry no `reps`, and still requires `reps` on a set counted in reps.
 *
 * WHY THE CONTRACT AND NOT ONLY THE EXPORTER. `$defs.set.required` listed
 * `reps`, so an exporter that wanted to be honest about a hold could not be:
 * the schema obliged it to publish a number nothing counted. The exporter's
 * half is `TimedSetRepsPublishedTest` in `:core:data`; this file pins the
 * published half and the Kotlin twin.
 *
 * WHAT MARKS A TIMED SET IN THE DOCUMENT. `duration_s` on a set that ran, and
 * `abandonedInPrep: true` on one that ended before its work phase -- where
 * `duration_s` is withheld. The schema cannot tell a timed set abandoned in
 * its prep from a REP set abandoned in its prep, so on that one shape it lets
 * `reps` be absent; the exporter still writes it on every rep set. `geometry`
 * was not used: it is absent on older and ungeometried sets, so a rule keyed
 * on it would fall through to "require reps" on exactly the oldest data.
 *
 * RED WHEN WRITTEN, except the two guards named in their own KDoc.
 */
class SchemaTimedRepsContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private fun setDef(): JsonObject = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject

    private fun description(name: String): String = setDef().getValue("properties").jsonObject.getValue(name)
        .jsonObject.getValue("description").jsonPrimitive.content

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "1.23 FURTHER ENTRY (#71"

    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun `the published set no longer requires reps on every set`() {
        val required = setDef().getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("reps" in required, "every set must still carry reps, so a hold must publish a 0: $required")
        assertTrue("load_kg" in required && "summary" in required, "the other required keys moved: $required")
    }

    /**
     * A set carrying neither `duration_s` nor `abandonedInPrep: true` must
     * still carry `reps`: one `anyOf` whose three branches each require one
     * of those keys, the last pinned to `true` so an `abandonedInPrep: false`
     * cannot stand in for a count.
     */
    @Test
    fun `a set that is not timed must still carry reps`() {
        val branches = assertNotNull(setDef()["anyOf"], "the set declares no anyOf, so reps is required nowhere")
            .jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(listOf("reps"), listOf("duration_s"), listOf("abandonedInPrep")),
            branches.map { b -> b.getValue("required").jsonArray.map { it.jsonPrimitive.content } },
            "the set's anyOf does not say which keys excuse a missing reps",
        )
        assertEquals(
            "true",
            branches[2]["properties"]?.jsonObject?.get("abandonedInPrep")?.jsonObject?.get("const")
                ?.jsonPrimitive?.content,
            "abandonedInPrep false would excuse a missing reps",
        )
    }

    /** The Kotlin twin reads a hold with no `reps` as a hold, not as a malformed set. */
    @Test
    fun `the Kotlin twin reads a timed set that carries no reps`() {
        val set = lenient.decodeFromString(
            ExerciseExport.serializer(),
            """{"exercise": "plank", "sets": [{"load_kg": 0.0, "duration_s": 45, "summary": {}}]}""",
        ).sets.single()
        assertNull(set.reps, "a hold decoded with a rep count it never carried")
        assertEquals(45, set.durationS, "the hold lost its seconds")
    }

    /**
     * GUARD, green before the fix and after it. A set with no `reps`, no
     * `duration_s` and no `abandonedInPrep` is a rep set that lost its count,
     * and the twin refuses it rather than reading it as a hold.
     */
    @Test
    fun `the Kotlin twin refuses a set that is not timed and carries no reps`() {
        assertFails("a rep set with no count decoded") {
            lenient.decodeFromString(
                ExerciseExport.serializer(),
                """{"exercise": "back_squat", "sets": [{"load_kg": 100.0, "summary": {}}]}""",
            )
        }
    }

    /**
     * `reps` says it is absent on a timed set, and the sentence that said a
     * timed set's integer is "whatever the segmenter made of one long
     * movement" is DELETED -- no such integer is published any more.
     * `abandonedInPrep` loses its unconditional "`reps` is 0" for the same
     * reason: on a timed set abandoned in its prep there is no `reps`.
     */
    @Test
    fun `reps and abandonedInPrep describe the absence and drop what it made false`() {
        val reps = description("reps")
        assertTrue("ABSENT on a timed set" in reps, "reps does not say it is absent on a timed set: $reps")
        assertFalse("whatever the segmenter made of one long movement" in reps, "reps keeps the deleted sentence")
        val abandoned = description("abandonedInPrep")
        assertFalse(
            "`reps` is 0 because nothing was counted rather than because nothing was lifted" in abandoned,
            "abandonedInPrep still says every such set publishes reps 0",
        )
        assertTrue("absent on a timed set" in abandoned, "abandonedInPrep does not say a timed one carries no reps")
    }

    /**
     * The log files the change once under the unreleased 1.23, says it is not
     * purely additive, and DELETES the first entry's claim that no existing
     * key is removed -- `reps` now is, on every timed set.
     */
    @Test
    fun `the 1_23 log files the reps change once and deletes the purely additive claim`() {
        val log = versionLog()
        assertEquals(1, log.split(marker).size - 1, "the #71 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore("1.23 FURTHER ENTRY (")
        for (fact in listOf("NOT PURELY ADDITIVE", "RETROACTIVE", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the #71 entry does not state: $fact")
        }
        assertFalse(
            "No existing key is removed, renamed or retyped and none changes its value" in log,
            "the log still says 1.23 removes no key",
        )
    }

    /**
     * The example ajv validates shows both branches: its timed sets carry no
     * `reps`, and every other set does.
     */
    @Test
    fun `the published example drops reps on its timed sets and keeps it on the rest`() {
        val sets = document("examples/session-export.example.json").getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }.map { it.jsonObject }
        val timed = sets.filter { "duration_s" in it }
        assertTrue(timed.isNotEmpty(), "the example carries no timed set, so ajv never sees the absence")
        assertEquals(emptyList(), timed.filter { "reps" in it }, "a timed set in the example publishes reps")
        val counted = sets.filter { "duration_s" !in it && it["abandonedInPrep"]?.jsonPrimitive?.content != "true" }
        assertEquals(emptyList(), counted.filter { "reps" !in it }, "a rep set in the example lost its reps")
    }

    /** The copy the coach receives says so, and says what an older export's 0 on a hold is. */
    @Test
    fun `the plan prompt says reps is absent on a timed set`() {
        assertTrue("\"reps\" itself is ABSENT on a timed set" in prompt, "the plan prompt does not say reps is absent")
        assertTrue("is not a count" in prompt, "the plan prompt does not say an older export's 0 on a hold is no count")
    }
}
