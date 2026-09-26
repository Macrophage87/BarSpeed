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
 * Export 1.24, minted here (#62 half (b)): a session with no `endedAt`
 * publishes `heartRate.hrvRmssd_ms` computed from its stored heart-rate
 * streams.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour they describe is red in `SessionExportUnclosedHrvTest` in
 * `:core:data` until the fix.
 *
 * THE TIP LITERAL LIVES HERE, on the rule `SchemaSkippedSetContractTest` wrote
 * when it minted 1.21: the literal the exporter writes is asserted in the file
 * that mints it and nowhere else. `SchemaWorkingTargetContractTest`, which
 * held it for 1.23, now asserts only that 1.23 is still accepted.
 */
class SchemaSessionHrvContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun properties() = schema.getValue("properties").jsonObject

    private fun versionLog() =
        properties().getValue("schemaVersion").jsonObject.getValue("description").jsonPrimitive.content

    private fun heartRate() = properties().getValue("heartRate").jsonObject

    /** Everything the version log says from this entry's marker onward. */
    private fun entry() = versionLog().substringAfter("1.24 (#62", missingDelimiterValue = "")

    @Test
    fun `the exporter writes 1_24, which the schema accepts, and 1_23 is still readable`() {
        val enum = properties().getValue("schemaVersion").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("1.24", SessionExport.SCHEMA_VERSION, "the version the exporter writes")
        assertTrue("1.24" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.24" in enum, "the published schema rejects the version the exporter writes")
        assertTrue("1.23" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.23, shipped in v0.1.56, left the accepted set")
    }

    /**
     * The block no longer says a derived block carries no HRV, and says
     * where an unfinished session's HRV comes from.
     */
    @Test
    fun `the heartRate block says an unfinished session's HRV is computed from its stored streams`() {
        val description = heartRate().getValue("description").jsonPrimitive.content

        assertFalse(
            "`hrvRmssd_ms` is published only from a close" in description,
            "the block still says a derived block carries no HRV",
        )
        assertTrue(
            "computed from the session's stored heart-rate streams" in description,
            "the block does not say where an unfinished session's HRV comes from",
        )
        assertTrue("(1.24, #62)" in description, "the block does not name the version that moved it")
    }

    /**
     * The key's own description says plainly that a derived HRV is computed
     * from stored streams and how closely that matched the close's figure,
     * so a coaching tool reading one key is not told more than was measured.
     */
    @Test
    fun `the hrvRmssd_ms key says a derived figure is computed from stored streams and how closely it matched`() {
        val description = heartRate().getValue("properties").jsonObject.getValue("hrvRmssd_ms").jsonObject
            .getValue("description").jsonPrimitive.content

        listOf(
            "On a session with no `endedAt`",
            "`rest_before_hrm`",
            "A derived HRV is computed from stored streams, not from the intervals the close received",
            "within 0.8 percent on six closed sessions",
            "Omitted, never 0",
        ).forEach { assertTrue(it in description, "the hrvRmssd_ms description does not state: $it") }
    }

    /** The version log mints 1.24 and retracts the 1.23 sentence by quoting it. */
    @Test
    fun `the version log mints 1_24 and retracts the 1_23 sentence`() {
        val entry = entry()

        assertTrue(entry.isNotEmpty(), "the version log has no 1.24 entry for #62")
        listOf(
            "A MINT and not a further 1.23 entry",
            "RETRACTS",
            "a derived block never carries it",
            "COMPUTED FROM STORED STREAMS",
            "MEASURED, NOT GUARANTEED",
            "within 0.8 percent",
            "REJECTS a 1.24 document",
            "DATABASE_VERSION does NOT move",
        ).forEach { assertTrue(it in entry, "the 1.24 entry does not state: $it") }
    }

    @Test
    fun `the published example declares 1_24`() {
        val example = document("examples/session-export.example.json")
        assertEquals("1.24", example.getValue("schemaVersion").jsonPrimitive.content)
    }
}
