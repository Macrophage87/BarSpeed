package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Premise pins on the PUBLISHED plan contract, not on any code path.
 *
 * `docs/schemas/` is what a plan-writing LLM is pointed at, and it is on this
 * module's test classpath because `core/model/build.gradle.kts` puts that
 * directory on the test resources source set. No Kotlin in the app reads the
 * example; its only other automated consumer is the ajv step at
 * `.github/workflows/ci.yml:42`.
 *
 * What is pinned here is that a set declaring no load at all is contract
 * rather than a malformed input, and that the shipped example runs a loaded
 * carry straight into such a set. These assert nothing about what the record
 * flow then does with it.
 */
class ShippedPlanExampleTest {
    private fun shippedExample(): PlanFile {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/examples/plan.example.json")) {
                "docs/schemas/examples/plan.example.json is not on the test classpath"
            }.bufferedReader().readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(PlanFile.serializer(), text)
    }

    @Test
    fun `shipped example runs a loaded carry straight into a loadless hold`() {
        val plan = shippedExample()
        val lowerA = plan.sessions.first { it.name == "Lower A" }
        val ids = lowerA.exercises.map { it.exercise }

        // Adjacency is the point: whatever the carry leaves behind is what the
        // hold is in a position to inherit.
        assertEquals(ids.indexOf("farmers_walk") + 1, ids.indexOf("plank"))

        val carry = lowerA.exercises.first { it.exercise == "farmers_walk" }
        assertEquals(listOf(48.0, 48.0), carry.sets.map { it.resolvedLoadKg })

        val hold = lowerA.exercises.first { it.exercise == "plank" }
        assertEquals(2, hold.sets.size)
        hold.sets.forEachIndexed { i, set ->
            assertNull(set.resolvedLoadKg, "plank set ${i + 1} resolves to no load")
            assertNull(set.loadKg, "plank set ${i + 1} declares no load_kg")
            assertNull(set.loadLb, "plank set ${i + 1} declares no load_lb")
        }
        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
    }

    /**
     * The shipped example exercises a prep on a hold, and the import gate is
     * quiet about it.
     *
     * Two things at once, and both are the point. A capability nothing
     * exercises is one an LLM writing a plan from this document will never use,
     * and `plank` is the case the widening was asked for. The warning check is
     * the same fact from the gate's side: before this change the example could
     * not have carried it without the app calling it inert.
     */
    @Test
    fun `the shipped example declares a prep on a hold, and the gate is quiet about it`() {
        val plan = shippedExample()
        val hold = plan.sessions.flatMap { it.exercises }.first { it.exercise == "plank" }

        assertEquals(10, hold.prepS, "the shipped example declares no prep on its hold")
        assertTrue(hold.sets.all { it.isTimed }, "the plank's sets are timed")
        assertTrue(hold.sets.all { it.tempo == null }, "a timed set cannot carry a tempo")
        assertTrue(
            plan.warnings().none { "prep_s" in it },
            "the shipped example warns about its own prep: ${plan.warnings()}",
        )
    }

    @Test
    fun `the published schema permits a set with no load and says to omit both`() {
        val schema =
            Json.parseToJsonElement(
                javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
            ).jsonObject
        val set = schema["\$defs"]!!.jsonObject["set"]!!.jsonObject

        // The only `required` lists on a set are the reps/duration_s oneOf
        // branches; a load key appearing in any of them would make a bodyweight
        // set schema-invalid.
        assertFalse(set.containsKey("required"), "a set must not require any top-level key")
        val requiredKeys =
            set["oneOf"]!!.jsonArray
                .flatMap { it.jsonObject["required"]?.jsonArray.orEmpty() }
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("reps", "duration_s"), requiredKeys)

        val props = set["properties"]!!.jsonObject
        listOf("load_kg", "load_lb").forEach { key ->
            val description = props[key]!!.jsonObject["description"]!!.jsonPrimitive.content
            assertTrue(
                description.contains("omit both for bodyweight"),
                "$key description no longer tells the plan writer to omit both: $description",
            )
        }
    }
}
