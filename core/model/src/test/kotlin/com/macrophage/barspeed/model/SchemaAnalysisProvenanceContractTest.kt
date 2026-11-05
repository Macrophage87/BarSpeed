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
 * Export 1.26, a set's `analysisSchemaVersion` (#38): which contract the
 * build that RECORDED the set analysed it under, beside the document's own
 * `schemaVersion`, which names the build that exported it.
 *
 * Pins the contract: the log entry, the declaration in both published
 * schemas -- session.json's set and the raw archive's `meta.json` set, two
 * writers of one fact -- and that neither makes it required, since every set
 * recorded before database v21 publishes it absent. The set's key list is
 * pinned to [SetExport] in `SchemaExportKeySetContractTest`; what the
 * exporters write is `SessionExportAnalysisProvenanceTest`'s, in `:core:data`.
 */
class SchemaAnalysisProvenanceContractTest {
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
        assertEquals(1, Regex("""1\.26 \(#38, analysis provenance\)""").findAll(log).count())
        val entry = log.substringAfter("1.26 (#38, analysis provenance)")
        assertTrue(entry.startsWith(": ADDS ONE KEY."), entry.take(80))
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say older sets publish it absent")
        assertEquals("1.26", SessionExport.SCHEMA_VERSION)
    }

    @Test
    fun `session json and meta json both declare it as an optional version string`() {
        for ((name, document) in listOf("session-export" to schema, "raw-meta" to raw)) {
            val key = declaration(document)
            assertEquals("string", key.getValue("type").jsonPrimitive.content, name)
            val pattern = Regex(key.getValue("pattern").jsonPrimitive.content)
            for (version in SessionExport.SUPPORTED_SCHEMA_VERSIONS) {
                assertTrue(pattern.matches(version), "$name refuses $version")
            }
            assertFalse(pattern.matches("1.1.0"), "$name accepts a three-part version")
            val required = setDef(document)["required"]?.toString().orEmpty()
            assertFalse("\"$KEY\"" in required, "$name requires a key every pre-v21 set lacks")
            assertTrue(
                "database v21" in key.getValue("description").jsonPrimitive.content,
                "$name does not say which sets publish it absent",
            )
        }
    }

    private companion object {
        const val KEY = "analysisSchemaVersion"
    }
}
