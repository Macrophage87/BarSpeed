package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export 1.26, a set's `recovered` (#100): true on a set written back into
 * History from an interrupted set's capture rather than by the set ending.
 *
 * Pins the contract: the log entry, the declaration in both published
 * schemas -- session.json's set and the raw archive's `meta.json` set, two
 * writers of one fact -- and that neither makes it required, since it is
 * omitted when false. The set's key list is pinned to [SetExport] in
 * `SchemaExportKeySetContractTest`; what the exporters write is
 * `SessionExportRecoveredTest`'s, in `:core:data`.
 */
class SchemaRecoveredContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val raw = document("raw-meta.schema.json")

    private fun setDef(document: JsonObject): JsonObject =
        document.getValue("\$defs").jsonObject.getValue("set").jsonObject

    private fun declaration(document: JsonObject): JsonObject =
        setDef(document).getValue("properties").jsonObject.getValue(KEY).jsonObject

    @Test
    fun `the version log files the key under 1_26 once, as an added key`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#100, recovered set\)""").findAll(log).count())
        val entry = log.substringAfter("1.26 (#100, recovered set)")
        assertTrue(entry.startsWith(": ADDS ONE KEY."), entry.take(80))
        assertTrue("OMITTED WHEN FALSE" in entry, "the entry does not say the key is omitted when false")
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the entry files the key under no version")
    }

    /**
     * session.json declares a boolean; meta.json declares `const: true`, the
     * shape its other omitted-when-false flags (`added`, `voided`) take.
     * Neither requires it, and both say what a recovered set lacks.
     */
    @Test
    fun `session json and meta json both declare it as an optional flag`() {
        assertEquals("boolean", declaration(schema).getValue("type").jsonPrimitive.content)
        assertEquals("true", declaration(raw).getValue("const").jsonPrimitive.content)
        for ((name, document) in listOf("session-export" to schema, "raw-meta" to raw)) {
            val required = setDef(document)["required"]?.toString().orEmpty()
            assertFalse("\"$KEY\"" in required, "$name requires a key that is omitted when false")
            val description = declaration(document).getValue("description").jsonPrimitive.content
            assertTrue("database v21" in description, "$name does not say why older sets lack it")
            assertTrue("rating" in description, "$name does not say what a recovered set lacks")
        }
    }

    private companion object {
        const val KEY = "recovered"
    }
}
