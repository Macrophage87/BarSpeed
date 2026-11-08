package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.27, the sensor-counted set's target cue (UX review 2026-11-06,
 * voice F5): `Rep N, last rep` and `Rep N, target` join `voiceCues`.
 *
 * Pins the contract, not the words' production -- that is
 * `VoiceMilestonePolicyTest`'s. Here: the version-log entry is filed once,
 * says no key moved and that neither string is terminal; the voiceCues
 * description dates all three periods of what a sensor-counted set says, and
 * quotes the strings the policy actually produces; and 1.27 is still
 * accepted. The tip literal lived here until 1.28 moved it to
 * `SchemaSquatLockoutContractTest`.
 */
class SchemaSensorTargetCueContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun voiceCues(): String = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("voiceCues").jsonObject
        .getValue("description").jsonPrimitive.content

    private val sensor = CountingPolicy.milestones(RepCounter.SENSOR)

    /** RENAMED from `the exporter writes 1_27, which the schema and the example carry, ...` at 1.28. */
    @Test
    fun `1_27 is accepted by the code and the schema, and 1_26 is still readable`() {
        val enum = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("1.27" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.27, shipped in v0.2.2, left the accepted set")
        assertTrue("1.27" in enum, "the published schema rejects 1.27")
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.26, shipped in v0.2.1, left the accepted set")
    }

    @Test
    fun `the version log files the target cue under 1_27 once, as no key and nothing terminal`() {
        val marker = "1.27 (sensor-counted target cue)"
        assertEquals(1, Regex(Regex.escape(marker)).findAll(versionLog()).count(), "the 1.27 entry is not filed once")
        val entry = versionLog().substringAfter(marker)
        assertTrue(entry.startsWith(" MINTS NO KEY"), entry.take(80))
        assertTrue("NEITHER IS TERMINAL" in entry, "the entry does not say neither string is terminal")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say older tracks keep their words")
        assertTrue("'Rep N, last rep'" in entry && "'Rep N, target'" in entry, "the entry does not name both strings")
    }

    /**
     * The three periods, each by the build that recorded the set, because the
     * cue track is stored with the set and a document's own version does not
     * date it.
     */
    @Test
    fun `voiceCues dates the three periods of a sensor-counted set's words`() {
        val text = voiceCues()
        assertTrue("Recorded by v0.1.52 or earlier" in text, "the first period is not dated")
        assertTrue("may say 'Last rep' and 'Done'" in text, "the first period's words are not stated")
        assertTrue("Recorded by v0.1.53 (#286) through v0.2.1" in text, "the second period is not dated")
        assertTrue("plain 'Rep N' on every rep and never 'Last rep' or 'Done'" in text, "the second period's words")
        assertTrue("Recorded by a build writing 1.27 or later" in text, "the third period is not dated")
        assertTrue("neither new string is terminal" in text, "the third period does not say neither is terminal")
    }

    /** The strings the description quotes are the ones the policy produces, with N in place of the number. */
    @Test
    fun `voiceCues quotes the words the sensor's voice actually says`() {
        val lastRep = checkNotNull(VoiceMilestonePolicy.repMilestone(4, 3, 5, sensor)).replace("4", "N")
        val target = checkNotNull(VoiceMilestonePolicy.repMilestone(5, 4, 5, sensor)).replace("5", "N")
        assertTrue("'$lastRep'" in voiceCues(), "voiceCues does not quote '$lastRep'")
        assertTrue("'$target'" in voiceCues(), "voiceCues does not quote '$target'")
    }
}
