package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.27's further entry, the sensor-loss cue (UX review 2026-11-06,
 * BLE F1): `Sensor lost` and `Sensor back` join `voiceCues` on
 * sensor-counted sets.
 *
 * Pins the contract, not the words' production -- that is
 * `SensorLossPolicyTest`'s, and their not being terminal is
 * `SetEndWindowTest`'s in `:core:dsp`. Here: the entry is filed once under
 * 1.27, says no key moved and neither string is terminal, names `No count`
 * and the unrecorded `Set started`/`Set ended`; and the voiceCues
 * description quotes the strings the code actually writes.
 */
class SchemaSensorLossCueContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/session-export.schema.json")) {
            "session-export.schema.json is not on the test classpath"
        }.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun voiceCues(): String = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("voiceCues").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "1.27 FURTHER ENTRY (sensor-loss cue)"

    @Test
    fun `the version log files the sensor-loss cue under 1_27 once, as no key and nothing terminal`() {
        assertEquals(1, Regex(Regex.escape(marker)).findAll(versionLog()).count(), "the entry is not filed once")
        assertTrue(
            versionLog().indexOf(marker) > versionLog().indexOf("1.27 (sensor-counted target cue)"),
            "the further entry is filed before the 1.27 mint it extends",
        )
        val entry = versionLog().substringAfter(marker)
        assertTrue(entry.startsWith(" ADDS NO KEY"), entry.take(80))
        assertTrue("on sensor-counted sets only" in entry, "the entry does not say which sets carry the words")
        assertTrue("at most once per set" in entry, "the entry does not say each is said once")
        assertTrue("3 seconds" in entry, "the entry does not state the silence window")
        assertTrue("NEITHER IS TERMINAL" in entry, "the entry does not say neither string is terminal")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays")
        assertTrue("'Set started' and 'Set ended'" in entry && "NOT recorded" in entry, "unrecorded words")
    }

    /** The silence window the entry states is the one the policy reads. */
    @Test
    fun `the stated window is the armed-silence window`() {
        assertEquals(3_000L, ArmedSilencePolicy.SILENT_AFTER_MS)
    }

    /** Every string the entry and the description quote is the one the code writes. */
    @Test
    fun `both texts quote the words the app actually records`() {
        val words = listOf(SensorLossPolicy.LOST_CUE, SensorLossPolicy.BACK_CUE, LiveFallbackPolicy.WITHHELD_CUE)
        val entry = versionLog().substringAfter(marker)
        for (word in words) {
            assertTrue("'$word'" in entry, "the 1.27 entry does not quote '$word'")
            assertTrue("'$word'" in voiceCues(), "voiceCues does not quote '$word'")
        }
        assertTrue("'${UnguidedSetCall.STARTED}'" in entry, "the entry does not quote the start word")
    }

    /**
     * The sentence that called the unguided counter's vocabulary whole no
     * longer stands unqualified: a sensor-counted set's track can carry the
     * status words beside its calls.
     */
    @Test
    fun `voiceCues no longer calls the counter's vocabulary whole without the status words`() {
        val text = voiceCues()
        assertTrue("plus, on a sensor-counted set, the sensor-status words below" in text, "unqualified")
        assertTrue("none terminal" in text, "voiceCues does not say the status words are not terminal")
    }
}
