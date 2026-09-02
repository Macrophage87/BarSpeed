package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan schema 1.11 mint: the `progression` key, in every one of the six
 * places a plan contract is stated (#214).
 *
 * RED AT ITS OWN SHA, and that is what this file is for. At the commit before
 * this one the app publishes 1.10, has no `progression` key, warns about it as
 * an unknown key at the import gate, and its prompt names 1.10 -- so every
 * assertion here fails for a different one of those reasons.
 *
 * A version rather than an extension of 1.10, and that is not a taste: 1.10
 * SHIPPED in v0.1.48, so the window in which a key could extend it is closed
 * and redefining a released version is not a resolution available here.
 *
 * The reason all six move together is #136's trap. `PLAN_PROMPT` is the only
 * statement of this contract that anything actually sends anywhere -- it is
 * what the COPY PLAN PROMPT button puts on the clipboard -- so a prompt that
 * advertises a version or a key the installed app refuses produces plans that
 * fail the app's own gate. That has shipped here once already.
 */
class SchemaProgressionContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun planSchema() = schema("plan.schema.json")

    private fun exerciseProps() =
        planSchema()["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!.jsonObject

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    // ---- 1. the version ----

    @Test
    fun `the app writes plan schema 1_11`() {
        assertEquals("1.11", PlanFile.SCHEMA_VERSION)
    }

    @Test
    fun `the import gate accepts both 1_10 and the freshly minted 1_11`() {
        assertContains(PlanFile.SUPPORTED_SCHEMA_VERSIONS, "1.10")
        assertContains(PlanFile.SUPPORTED_SCHEMA_VERSIONS, "1.11")
    }

    @Test
    fun `the published version enum carries 1_11`() {
        val versions = planSchema()["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(versions, "1.11")
    }

    /**
     * The version log entry, in the schema's own `schemaVersion` description,
     * which is the only place a plan author reads why a number moved. Pinned
     * for the three things a reader cannot reconstruct: that the entry exists,
     * that it names the key, and that it names the release which closed 1.10
     * so the claim can be checked at a tag rather than taken on trust.
     */
    @Test
    fun `the version log has a 1_11 entry naming the key and the release that closed 1_10`() {
        val description = planSchema()["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.11:" in description, "the plan version log has no 1.11 entry")
        assertTrue("progression" in description, "the 1.11 entry never names the key it adds")
        assertTrue(
            "v0.1.48" in description,
            "the 1.11 entry does not say which release closed 1.10, so nobody can check it at a tag",
        )
    }

    // ---- 2. the key, in the schema and in its Kotlin twin ----

    @Test
    fun `the published progression values are exactly the kinds the app can act on`() {
        val declared = exerciseProps()["progression"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertEquals(ProgressionKind.entries.map { it.name.lowercase() }.toSet(), declared)
    }

    @Test
    fun `the published progression key is described, so a plan author is told what it does`() {
        val described = exerciseProps()["progression"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("weight" in described, "the description never names the default")
        assertTrue("none" in described, "the description never names the hold value")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the exercise decoder recognises the progression key`() {
        assertContains(PlanExerciseDef.serializer().descriptor.elementNames.toList(), "progression")
    }

    // ---- 3. the import gate ----

    private fun plan(progression: String, timed: Boolean = false, version: String = "1.11"): String {
        val set = if (timed) """{"duration_s": 30}""" else """{"reps": 8}"""
        return """
            {
              "schemaVersion": "$version",
              "planName": "p",
              "sessions": [
                {
                  "name": "s",
                  "exercises": [
                    {"exercise": "bench_press", "start": "top", "progression": "$progression",
                     "sets": [$set]}
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun `a plan declaring 1_11 and a progression imports clean`() {
        val result = PlanImport.parse(plan("reps"))
        assertEquals(emptyList(), result.errors, "1.11 with a progression key must not be refused")
        assertTrue(
            result.warnings.none { "progression" in it && "unknown key" in it },
            "progression is a real key now and must not be reported as unknown: ${result.warnings}",
        )
    }

    @Test
    fun `a plan still declaring 1_10 is accepted unchanged`() {
        val doc = plan("weight", version = "1.10")
        assertEquals(emptyList(), PlanImport.parse(doc).errors)
    }

    @Test
    fun `a progression value outside the vocabulary is refused with the path named`() {
        val errors = PlanImport.parse(plan("volume")).errors
        assertEquals(1, errors.size, "expected exactly one error, got $errors")
        assertTrue("sessions[0].exercises[0].progression" in errors.first(), errors.first())
    }

    /**
     * The two mismatch warnings the owner asked for, named ONCE per exercise
     * the way the inert `sensors` declaration is: a block of six sets would
     * otherwise produce six identical lines, which is how a gate becomes
     * something the eye skips.
     */
    @Test
    fun `reps on a timed exercise warns once, naming the exercise`() {
        val warnings = PlanImport.parse(plan("reps", timed = true)).warnings
        val matching = warnings.filter { "progression" in it }
        assertEquals(1, matching.size, "expected one progression warning, got $warnings")
        assertTrue("bench_press" in matching.first(), matching.first())
    }

    @Test
    fun `time on a rep-prescribed exercise warns once, naming the exercise`() {
        val warnings = PlanImport.parse(plan("time", timed = false)).warnings
        val matching = warnings.filter { "progression" in it }
        assertEquals(1, matching.size, "expected one progression warning, got $warnings")
        assertTrue("bench_press" in matching.first(), matching.first())
    }

    @Test
    fun `a progression that matches the sets warns about nothing`() {
        assertTrue(
            PlanImport.parse(plan("weight")).warnings.none { "progression" in it },
            "a weight progression on a rep block is the ordinary case",
        )
        assertTrue(
            PlanImport.parse(plan("time", timed = true)).warnings.none { "progression" in it },
            "a time progression on a timed block is the ordinary case",
        )
        assertTrue(
            PlanImport.parse(plan("none")).warnings.none { "progression" in it },
            "none says hold, and holds on either shape of set",
        )
    }

    // ---- 4. the shipped example ----

    @Test
    fun `the shipped example exercises the key it now publishes`() {
        val text = javaClass.getResourceAsStream("/examples/plan.example.json")!!
            .readBytes().decodeToString()
        assertTrue("\"progression\"" in text, "the published example never uses the key 1.11 adds")
        val parsed = PlanImport.parse(text)
        assertEquals(emptyList(), parsed.errors, "the shipped example must validate clean")
    }

    // ---- 5. the prompt, which is the copy that actually reaches a model ----

    @Test
    fun `the plan prompt names the progression key and all four of its values`() {
        assertTrue("\"progression\"" in prompt, "the prompt never mentions the key, so no plan will use it")
        ProgressionKind.entries.forEach {
            val value = it.name.lowercase()
            assertTrue("\"$value\"" in prompt, "the prompt never offers the value \"$value\"")
        }
    }

    @Test
    fun `the plan prompt states that an omitted progression means weight`() {
        val sentence = prompt.lineSequence().first { "\"progression\"" in it }
        assertTrue(
            "weight" in sentence && ("default" in sentence.lowercase() || "omit" in sentence.lowercase()),
            "the prompt does not say what an omitted progression means: $sentence",
        )
    }

    /**
     * #222's paragraph and this key have to agree, and the owner said so when
     * he filed them: the prompt already tells the model to open near the
     * productive floor and step the sets up, so the sentence that introduces
     * `progression` must say which dimension steps up on an exercise that is
     * not stepping load.
     */
    @Test
    fun `the prompt's progression sentence agrees with the productive-floor paragraph`() {
        assertTrue(
            "productive floor" in prompt,
            "the productive-floor paragraph (#222) is gone; the progression sentence has nothing to agree with",
        )
        val sentence = prompt.lineSequence().first { "\"progression\"" in it }
        assertTrue(
            "step" in sentence.lowercase() || "push" in sentence.lowercase() || "raise" in sentence.lowercase(),
            "the progression sentence does not connect to the step-up rule: $sentence",
        )
    }
}
