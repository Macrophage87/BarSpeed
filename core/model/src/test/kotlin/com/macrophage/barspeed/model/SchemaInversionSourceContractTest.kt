package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export 1.26, minted here (#289): `geometry.source` may carry
 * `sensorInverted`, the value that decides which measured phase is called the
 * concentric and the last geometry value given a provenance.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour is red in `SetGeometryPolicyTest` and, in `:core:data`,
 * `SessionExportInversionSourceTest`, until the fix.
 *
 * THE TIP LITERAL LIVES HERE, on the rule `SchemaSkippedSetContractTest` wrote
 * when it minted 1.21. `SchemaFallRejectionContractTest`, which held it for
 * 1.25, now asserts only that 1.25 is still accepted.
 */
class SchemaInversionSourceContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun versionLog() = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun source(): JsonObject = schema.getValue("\$defs").jsonObject.getValue("geometrySource").jsonObject

    @Test
    fun `1_26, shipped in v0_2_1, is still accepted, and so is 1_25`() {
        val enum = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        // The tip literal moved to SchemaSensorTargetCueContractTest when 1.27 was minted.
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.26" in enum, "the published schema rejects the version the exporter writes")
        assertTrue("1.25" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.25, shipped in v0.1.58, left the accepted set")
    }

    @Test
    fun `the version log files the inversion source under 1_26 once and says what an older set carries`() {
        val log = versionLog()
        assertEquals(1, Regex("""1\.26 \(#289""").findAll(log).count(), "the 1.26 #289 entry is not filed exactly once")
        val entry = log.substringAfter("1.26 (#289").substringBefore(" 1.26 (#")
        listOf(
            "MINTS A KEY",
            "`declared` = the plan wrote the key, true or false",
            "`inferred` = nothing wrote it and the app's stack rule set it true",
            "`inferred` beside a false is that case",
            "`seeded`",
            "`default` = nothing wrote it and no rule set it",
            "OPTIONAL",
            "ABSENT on every set recorded before this version",
            "A MINT and not a further 1.25 entry",
            "NOT RETROACTIVE",
            "REJECTS a 1.26 document",
            "DATABASE_VERSION does NOT move",
        ).forEach { assertTrue(it in entry, "the 1.26 #289 entry does not state: $it") }
    }

    /**
     * Declared, optional, in the shared vocabulary, and described -- with the
     * absence on an older set stated as "not recorded", never as `default`.
     */
    @Test
    fun `the source block declares the inversion's source as optional and says what its absence means`() {
        val block = source()
        val key = block.getValue("properties").jsonObject.getValue("sensorInverted").jsonObject
        assertEquals("#/\$defs/sourceValue", key.getValue("\$ref").jsonPrimitive.content)
        val required = block.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("sensorInverted" in required, "the inversion's source is required, so every older set fails")
        val description = key.getValue("description").jsonPrimitive.content
        listOf("1.26, #289", "'declared'", "'inferred'", "'seeded'", "'default'", "ABSENT", "never 'default'")
            .forEach { assertTrue(it in description, "the key's description does not state: $it") }
        assertFalse(
            "NOTE the ONE value missing here" in block.getValue("description").jsonPrimitive.content,
            "the block's description still says sensorInverted carries no source",
        )
        val geometry = schema.getValue("\$defs").jsonObject.getValue("geometry").jsonObject
        assertEquals(
            geometry.getValue("properties").jsonObject.keys - "source",
            block.getValue("properties").jsonObject.keys,
            "a geometry value carries no provenance, or a provenance describes no value",
        )
    }

    /** The example carries the key on every set that publishes a geometry, and uses three of the four words. */
    @Test
    fun `the published example names a source for every published inversion`() {
        val example = document("examples/session-export.example.json")
        // At or after 1.26, which published the key; the tip literal is SchemaSensorTargetCueContractTest's.
        assertTrue(example.getValue("schemaVersion").jsonPrimitive.content in setOf("1.26", "1.27", "1.28"))
        val sources = example.getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .mapNotNull { it.jsonObject["geometry"]?.jsonObject }
            .map { it.getValue("source").jsonObject["sensorInverted"]?.jsonPrimitive?.content }
        assertTrue(sources.isNotEmpty(), "the example publishes no geometry")
        assertFalse(null in sources, "a published geometry in the example has no inversion source")
        assertEquals(setOf("default", "inferred", "declared"), sources.toSet())
    }
}
