package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published export says about the heart-rate strap's sensor-contact
 * report, issue #82, filed under export 1.26.
 *
 * The contract lands in the same commit as the red, as a deliberate contract
 * change must, so these assertions pass at the commit that writes them. The
 * behaviour they describe is red in `HrTrustTest` in `:core:hrm` and in
 * `HrCsvTest` and `SetJournalTest` in `:core:data` until the fix.
 *
 * The column itself is described where `meta.json` is: `raw-meta.schema.json`'s
 * `csvHeaderHrm`, which until this change called its value the header of
 * EVERY heart-rate file in the zip. Stored streams are exported verbatim, so
 * an archive spanning both builds holds three- and four-column files under
 * one manifest key; the description now says to read each file's own header.
 * `HrCsv.HEADER` is in `:core:data`, which this module cannot see, so the
 * literal below is checked against the published example and the published
 * words only; `RawExporterTest` pins the manifest to `HrCsv.HEADER`.
 */
class SchemaSensorContactContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun versionLog() = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun hrBlock() = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("hr").jsonObject
        .getValue("description").jsonPrimitive.content

    @Test
    fun `the raw manifest says the header is this build's and each file carries its own`() {
        val text = document("raw-meta.schema.json").getValue("properties").jsonObject
            .getValue("csvHeaderHrm").jsonObject.getValue("description").jsonPrimitive.content
        listOf(
            "THIS build writes",
            "`timestamp_ms,hr_bpm,rr_ms,contact` since export 1.26 (#82)",
            "copied into the zip verbatim",
            "still has three columns",
            "read each file's own header for its width, never this key",
            "EMPTY where it said nothing",
            "never either reported value",
        ).forEach { assertTrue(it in text, "csvHeaderHrm does not state: $it") }
        val example = document("examples/raw-meta.example.json")
        assertEquals("timestamp_ms,hr_bpm,rr_ms,contact", example.getValue("csvHeaderHrm").jsonPrimitive.content)
    }

    @Test
    fun `the set's hr block says which samples the contact report removes`() {
        val text = hrBlock()
        listOf(
            "Since 1.26 (#82)",
            "no R-R interval whose strap REPORTED no skin contact is not one of them",
            "no R-R and no contact report still is",
            "never overruled by the contact report",
        ).forEach { assertTrue(it in text, "the hr block does not state: $it") }
    }

    @Test
    fun `the version log files the contact column once under 1_26 with all three values`() {
        val log = versionLog()
        assertEquals(1, Regex("""1\.26 \(#82""").findAll(log).count(), "not filed exactly once")
        val entry = log.substringAfter("1.26 (#82")
        listOf(
            "`timestamp_ms,hr_bpm,rr_ms,contact`",
            "`1` is",
            "`0` is",
            "EMPTY is Support clear",
            "THREE STATES ON PURPOSE",
            "never either reported value",
            "both widths",
            "withholding on MISSING evidence",
            "UNMEASURED",
            "NOT RETROACTIVE",
            "moves no database column",
        ).forEach { assertTrue(it in entry, "the #82 entry does not state: $it") }
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
    }
}
