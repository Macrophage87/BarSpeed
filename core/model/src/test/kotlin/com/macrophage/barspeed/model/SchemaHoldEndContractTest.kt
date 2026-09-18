package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the published export contract says about which of four things ended a
 * hold. Issues #259 and #249, export 1.21's SECOND entry.
 *
 * ## Why there is a key at all
 *
 * The preference is to derive. At export time a timed set's row publishes
 * `duration_s` and `plannedDuration_s`, and the archive's manifest publishes
 * `workStartedAt_ms` beside the row's end instant, so the measured span IS
 * recoverable and can be compared with what was recorded. What the comparison
 * cannot do is separate a figure the SENSOR shortened from one the LIFTER
 * corrected: both are a stored value that is neither the span nor the target,
 * and #249 is exactly the ask to tell those apart. A correction landing on the
 * target would read as the clock as well.
 *
 * ## What these pins can and cannot check
 *
 * They read the PUBLISHED document, `docs/schemas/session-export.schema.json`,
 * and the example `ci.yml` hands to ajv. The Kotlin twin of the same log, on
 * [SessionExport.SCHEMA_VERSION], is not on this module's test classpath and is
 * kept in step by hand -- the limit every sibling contract test states.
 *
 * They say nothing about what the app WRITES into the key. That a sensor end
 * reaches the column and the column reaches the document is `:core:data`'s
 * `SessionExportHoldEndTest`; that the right instant is chosen at all is
 * `:core:dsp`'s `HoldReleaseFieldTest` and `HoldEndPolicyTest` here.
 */
class SchemaHoldEndContractTest {
    private companion object {
        /** The opening words of this entry, and the anchor every pin below is scoped by. */
        const val MARKER = "1.21 SECOND ENTRY: ADDS an optional per-set durationEndedBy"
    }

    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun export() = schema("session-export.schema.json")

    private val versionLog: String
        get() = export().getValue("properties").jsonObject.getValue("schemaVersion")
            .jsonObject["description"]?.jsonPrimitive?.content.orEmpty()

    /**
     * THIS ENTRY's text, not the whole log. Scoped for the reason
     * [SchemaSkippedSetContractTest] states: `durationEndedBy` is named in the
     * property's own description too, so a bare substring check over the
     * document would pass over a log that never gained the entry.
     */
    private val entry: String
        get() = versionLog.substringAfter(MARKER, "")

    private fun setObject() = export().getValue("\$defs").jsonObject.getValue("set").jsonObject

    private fun setProperty(name: String) = setObject().getValue("properties").jsonObject[name]?.jsonObject

    @Test
    fun `the key is filed under 1_21, which has not shipped`() {
        // A FURTHER entry rather than a mint, and the test is which versions
        // have shipped: v0.1.53 declares "1.20", read at the tag this round, so
        // 1.21 is still open and a key added to it changes nothing any reader
        // has seen. 1.21 itself is asserted in the file that minted it (#300).
        assertTrue("1.21" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.21 is not an accepted version")
        assertTrue("1.20" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.20 left the accepted set")
        assertTrue(entry.isNotEmpty(), "the published log carries no entry opening \"$MARKER\"")
        assertTrue(
            "has NOT shipped" in entry,
            "the entry does not say why this is a further entry rather than a mint",
        )
    }

    @Test
    fun `the entry says what the sensor word does to duration_s, and that it is additive`() {
        // The sentence a reader comparing holds across versions needs: the same
        // set publishes a SHORTER figure than it would have under 1.20, and the
        // published meaning of the key has not changed.
        assertTrue("SHORTER" in entry, "the entry does not say the figure moves down on a sensor-ended hold")
        assertTrue(
            "walk back to the phone" in entry,
            "the entry does not say what the seconds that came off were",
        )
        assertTrue("ADDITIVE" in entry, "the entry does not say whether a 1.20 reader breaks")
        assertTrue("column carrying it is new" in entry, "the entry does not say no archive on disk moves")
        // And why it is a key rather than a derivation, which is the question
        // any reviewer of a new key asks first.
        assertTrue("WHY A KEY" in entry, "the entry does not argue for minting at all")
        assertTrue("#249" in entry, "the entry does not name the ask the word discharges")
    }

    @Test
    fun `the published enum and the four Kotlin words are the same set`() {
        // The pin that stops either side gaining a word alone. The export reads
        // the stored column back through `HoldEndSource.ofPublished`, so a fifth
        // word in Kotlin that the schema rejects would be published and fail
        // ajv, and a fifth word in the schema would be a word nothing writes.
        val declared = setProperty("durationEndedBy")
        assertTrue(declared != null, "the published set object declares no durationEndedBy")
        assertEquals("string", declared!!.getValue("type").jsonPrimitive.content, "the key is not a string")
        assertEquals(
            HoldEndSource.entries.map { it.published }.toSet(),
            declared.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the published enum and HoldEndSource have drifted apart",
        )
        assertNull(declared["default"], "a default word would answer for every hold in the archive")
        assertTrue(
            "durationEndedBy" !in setObject().getValue("required").jsonArray.map { it.jsonPrimitive.content },
            "the key is required, so a set that is not timed cannot be published",
        )
    }

    @Test
    fun `the published example carries a hold whose end is attributed`() {
        // An example carrying none of a new key passes a schema that declares it
        // and proves nothing, which is the failure mode the sibling contract
        // tests found twice.
        val example = schema("examples/session-export.example.json")
        val timedSets = example.getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .map { it.jsonObject }
            .filter { "duration_s" in it }
        val attributed = timedSets.mapNotNull { it["durationEndedBy"]?.jsonPrimitive?.content }
        assertTrue(attributed.isNotEmpty(), "no timed set in the example carries the key, so ajv never validates it")
        assertTrue(
            attributed.all { it in HoldEndSource.entries.map { source -> source.published } },
            "the example publishes a word the enum does not declare: $attributed",
        )
        // The example's attributed hold is the shape #259 is about: a hold the
        // lifter ended, published SHORTER than its target, with the sensor named
        // as what decided the end.
        val sensorEnded = timedSets.single { it["durationEndedBy"]?.jsonPrimitive?.content == "sensor" }
        val duration = sensorEnded.getValue("duration_s").jsonPrimitive.content.toInt()
        val planned = sensorEnded.getValue("plannedDuration_s").jsonPrimitive.content.toInt()
        assertTrue(duration < planned, "the example's sensor-ended hold reached its target, which is the clock's case")
    }
}
