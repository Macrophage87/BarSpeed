package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export 1.26, a further entry (#297): a hold or a carry publishes no
 * `geometry.startsWith`, no `geometry.concentric` and no source for either,
 * because neither kind has a stroke for them to describe.
 *
 * The schema REQUIRES the two words off a hold or a carry and does not forbid
 * them on one, because a 1.25 document's holds carry both and this schema
 * still accepts 1.25. The omission is the exporter's, red in `:core:data`.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour is red in `:core:data` -- `SessionExportHoldStrokeTest` and
 * `RawExporterTest` -- until the fix.
 *
 * The CONDITION is what this file pins, not only the required set: a schema
 * that merely stopped requiring the two words would accept a dynamic lift
 * without them, which is the dropped-key defect `GeometryExport`'s KDoc
 * exists to prevent. So the `else` branch must still require both, on the
 * geometry and on its source.
 */
class SchemaHoldStrokeContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val stroke = setOf("startsWith", "concentric")

    private fun defs(name: String): JsonObject = schema.getValue("\$defs").jsonObject.getValue(name).jsonObject

    private fun JsonElement.strings(): Set<String> = jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun condition(): JsonObject = defs("geometry").getValue("allOf").jsonArray.single().jsonObject

    @Test
    fun `the version log files the hold's missing stroke under 1_26 once`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#297""").findAll(log).count(), "the 1.26 #297 entry is not filed exactly once")
        val entry = log.substringAfter("1.26 (#297").substringBefore(" 1.26 (#")
        listOf(
            "REMOVES TWO KEYS FROM ONE KIND",
            "`hold` or `carry`",
            "every other kind carries all four as before",
            "field-42",
            "`velocityLossRegime`",
            "does not forbid them on one",
            "`concentricSource`",
            "Filed under 1.26, which is unreleased",
            "RETROACTIVE",
            "NOT ADDITIVE",
            "DATABASE_VERSION does NOT move",
        ).forEach { assertTrue(it in entry, "the 1.26 #297 entry does not state: $it") }
    }

    /**
     * Off a hold or a carry the two words and their sources are required. On
     * one they are not FORBIDDEN, because a 1.25 hold carries both and this
     * schema still accepts a 1.25 document: there is no `then` branch.
     */
    @Test
    fun `the geometry requires the stroke words exactly where the kind has a stroke`() {
        val ifKind = condition().getValue("if").jsonObject.getValue("properties").jsonObject
            .getValue("kind").jsonObject.getValue("enum").strings()
        assertEquals(
            ExerciseKind.entries.filterNot(GeometryExport::publishesStroke).map { it.name.lowercase() }.toSet(),
            ifKind,
            "the schema's stroke-less kinds and publishesStroke disagree",
        )
        val ifRequired = condition().getValue("if").jsonObject.getValue("required").strings()
        assertEquals(setOf("kind"), ifRequired, "the condition no longer keys on a stated kind")
        assertFalse("then" in condition(), "the schema forbids a stroke word on a hold, so a 1.25 hold fails")
        val orElse = condition().getValue("else").jsonObject
        assertEquals(stroke, orElse.getValue("required").strings(), "a dynamic lift may drop a stroke word")
        val elseSource = orElse.getValue("properties").jsonObject.getValue("source").jsonObject
        assertEquals(
            stroke,
            elseSource.getValue("required").strings(),
            "a dynamic lift may drop a stroke word's source",
        )
    }

    /** Outside the condition, the source block's optional keys are the two stroke words and sensorInverted (#289). */
    @Test
    fun `the source block's optional keys are the stroke words and the inversion`() {
        val source = defs("geometrySource")
        assertEquals(
            stroke + "sensorInverted",
            source.getValue("properties").jsonObject.keys - source.getValue("required").strings(),
            "the set of optional geometry sources changed",
        )
        assertTrue(stroke.none { it in defs("geometry").getValue("required").strings() })
    }

    @Test
    fun `both stroke words say they are absent on a hold or a carry`() {
        val properties = defs("geometry").getValue("properties").jsonObject
        stroke.forEach { key ->
            val description = properties.getValue(key).jsonObject.getValue("description").jsonPrimitive.content
            assertTrue("ABSENT on a hold or a carry (1.26, #297)" in description, "$key does not say so: $description")
        }
        assertFalse(
            "Present means all nine keys are present" in defs("geometry").getValue("description").jsonPrimitive.content,
            "the geometry still promises every key on every set",
        )
    }

    /** The example carries a hold's geometry, and it has no stroke and no stroke source. */
    @Test
    fun `the published example carries a hold with no stroke`() {
        val holds = document("examples/session-export.example.json").getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .mapNotNull { it.jsonObject["geometry"]?.jsonObject }
            .filter { it.getValue("kind").jsonPrimitive.content == "hold" }
        assertTrue(holds.isNotEmpty(), "the example publishes no hold's geometry")
        holds.forEach { g ->
            assertTrue(stroke.none { it in g }, "a hold in the example carries a stroke word: $g")
            assertTrue(stroke.none { it in g.getValue("source").jsonObject }, "a hold carries a stroke source: $g")
        }
    }
}
