package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the published export says about the session `heartRate` block of a
 * session that was never finished, issue #62.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour they describe is red in `SessionExportUnclosedHeartRateTest` in
 * `:core:data` until the fix.
 *
 * A separate file rather than cases in [SchemaContractTest], in the shape of
 * the other per-issue schema contracts.
 */
class SchemaSessionHeartRateContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun properties() = schema()["properties"]!!.jsonObject

    private fun exportVersionLog() = properties()["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /** Everything the version log says from this entry's marker onward. */
    private fun entry() = exportVersionLog().substringAfter("1.23 FURTHER ENTRY (#62", missingDelimiterValue = "")

    /**
     * The block's own description says where each figure comes from, so a
     * reader of one key need not find the log: stored by the close, derived
     * where there is no `endedAt`, and the HRV only from a close.
     */
    @Test
    fun `the heartRate block says a session with no endedAt carries a derived summary and no HRV`() {
        val description = properties()["heartRate"]!!.jsonObject["description"]?.jsonPrimitive?.content.orEmpty()

        assertTrue("no `endedAt`" in description, "the block does not say what an unfinished session carries")
        assertTrue("derived by the same rule" in description, "the block does not say the figure is derived")
        assertTrue("truncated to a whole beat" in description, "the block does not state the mean's rounding")
        assertTrue(
            "`hrvRmssd_ms` is published only from a close" in description,
            "the block does not say a derived summary carries no HRV",
        )
    }

    /**
     * The version log files the change as a further 1.23 entry that names the
     * 1.18 sentence it narrows and moves no database.
     */
    @Test
    fun `the version log files the derived session heart rate as a further 1_23 entry`() {
        val entry = entry()
        assertTrue(entry.isNotEmpty(), "the version log has no 1.23 entry for #62")
        assertTrue("no `endedAt`" in entry, "the entry does not say which sessions it reaches")
        assertTrue("1.18" in entry, "the entry does not name the 1.18 sentence it narrows")
        assertTrue("`hrvRmssd_ms` is published only from a close" in entry, "the entry does not keep HRV out")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays")
        assertTrue("1.23" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
    }
}
