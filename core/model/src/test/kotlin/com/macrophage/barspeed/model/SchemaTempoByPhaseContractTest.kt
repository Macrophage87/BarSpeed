package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.26, `tempoCompliance.byPhase` (#66 (c)): the set's tempo grade
 * taken apart by phase, so a ratio that is mostly drives can be read as one.
 *
 * Pins the contract: the log entry, the declaration and its description, and
 * the example, whose entries must follow its own `scoredPhases` in order and
 * whose counts must be able to compose the set-level ratio beside them. The
 * key set of an entry is pinned to [PhaseTallyExport] in
 * `SchemaExportKeySetContractTest`; what the exporter writes is
 * `SessionExportTempoByPhaseTest`'s, in `:core:data`.
 */
class SchemaTempoByPhaseContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream(name)!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("/session-export.schema.json")

    private val byPhase: JsonObject = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("tempoCompliance").jsonObject
        .getValue("properties").jsonObject.getValue("byPhase").jsonObject

    @Test
    fun `the version log files byPhase under 1_26 once, as an added key`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#66 \(c\)""").findAll(log).count(), "the 1.26 #66 (c) entry is not filed once")
        val entry = log.substringAfter("1.26 (#66 (c)")
        listOf("ADDS ONE KEY", "`tempoCompliance.byPhase`", "RETROACTIVE", "ABSENT when no phase was scored")
            .forEach { assertTrue(it in entry, "the 1.26 #66 (c) entry does not state: $it") }
    }

    @Test
    fun `byPhase is optional, never empty, and says what each count is over`() {
        val tempo = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
            .getValue("properties").jsonObject.getValue("tempoCompliance").jsonObject
        val required = tempo.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("byPhase" !in required, "byPhase is required, but a set that scored nothing has none")
        assertEquals(1, byPhase.getValue("minItems").jsonPrimitive.int, "byPhase may be published empty")
        val description = byPhase.getValue("description").jsonPrimitive.content
        listOf("RESOLVED", "in the same order", "ABSENT when scoredPhases is empty", "repMetrics")
            .forEach { assertTrue(it in description, "byPhase does not state: $it") }
        val item = byPhase.getValue("items").jsonObject.getValue("properties").jsonObject
        assertEquals(1, item.getValue("of").jsonObject.getValue("minimum").jsonPrimitive.int, "a phase entry may be 0")
    }

    @Test
    fun `the example's byPhase follows its scoredPhases and can compose its ratio`() {
        val sets = document("/examples/session-export.example.json").getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .mapNotNull { it.jsonObject["tempoCompliance"]?.jsonObject }
            .filter { "byPhase" in it }
        assertTrue(sets.isNotEmpty(), "no example set publishes byPhase")
        sets.forEach { tc ->
            val scored = tc.getValue("scoredPhases").jsonArray.map { it.jsonPrimitive.content }
            val entries = tc.getValue("byPhase").jsonArray.map { it.jsonObject }
            assertEquals(scored, entries.map { it.getValue("phase").jsonPrimitive.content }, "byPhase out of step")
            val of = tc.getValue("of").jsonPrimitive.int
            val within = tc.getValue("withinTolerance").jsonPrimitive.int
            var misses = 0
            entries.forEach { e ->
                val phaseOf = e.getValue("of").jsonPrimitive.int
                val phaseWithin = e.getValue("withinTolerance").jsonPrimitive.int
                assertTrue(phaseWithin <= phaseOf, "more within than resolved")
                assertTrue(phaseOf <= of, "a phase resolved on more reps than the set graded")
                // The reps that did not resolve this phase may all be within;
                // the ones that resolved it and missed may not.
                assertTrue(within <= phaseWithin + (of - phaseOf), "the set is better than this phase allows")
                misses += phaseOf - phaseWithin
            }
            // Each miss puts at most one rep out of the set's count.
            assertTrue(within >= of - misses, "the set is worse than its phases' misses account for")
        }
    }
}
