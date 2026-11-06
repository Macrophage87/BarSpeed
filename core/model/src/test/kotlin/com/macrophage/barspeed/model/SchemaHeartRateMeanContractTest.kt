package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published export says about how its two heart-rate means round,
 * issue #296, filed under export 1.26.
 *
 * Nothing about the figures moves: each set's `hr.avgBpm` has always been
 * truncated to a whole beat -- over its trusted samples since v0.1.39, over
 * every sample before -- and the session's
 * `heartRate.avgBpm` the unweighted mean of those, truncated again. What was
 * missing is that neither key said so, and "average heart rate" reads as a
 * mean over the session's samples, which it is not. The behaviour is pinned
 * where it is computed -- `HrTrustTest > the set's mean is truncated, never
 * rounded` in `:core:hrm` and `SessionHeartRateTest > the mean is truncated
 * as endSession truncates it, never rounded` here -- and this file pins that
 * the published descriptions state it.
 *
 * WHERE THE FIGURES COME FROM: 99, 99.25, 99.83 and 100.34 are #296's own
 * measurement of field-42. Nothing in this repository recomputes them; this
 * file enforces only that the schema quotes them consistently.
 */
class SchemaHeartRateMeanContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun properties() = schema.getValue("properties").jsonObject

    private fun versionLog() =
        properties().getValue("schemaVersion").jsonObject.getValue("description").jsonPrimitive.content

    private fun JsonObject.description(vararg path: String): String = path.fold(this) { node, key ->
        node.getValue("properties").jsonObject.getValue(key).jsonObject
    }.getValue("description").jsonPrimitive.content

    private fun setHr() = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject

    @Test
    fun `the set's avgBpm says it is truncated and never rounded`() {
        val text = setHr().description("hr", "avgBpm")
        listOf(
            "TRUNCATED toward zero to a whole beat, never rounded",
            "samples of 120 and 121 publish 120",
            "trusted samples",
            "1.26, #296",
        ).forEach { assertTrue(it in text, "the set's hr.avgBpm does not state: $it") }
    }

    @Test
    fun `the session's avgBpm says it is not a mean over samples`() {
        val text = schema.description("heartRate", "avgBpm")
        listOf(
            "NOT A MEAN OVER SAMPLES",
            "truncated again",
            "each set counts once however long it ran",
            "publishes 99",
            "99.25",
            "99.83",
            "100.34",
            "1.26, #296",
        ).forEach { assertTrue(it in text, "the session's heartRate.avgBpm does not state: $it") }
    }

    @Test
    fun `the version log files the rounding once under 1_26 and moves no figure`() {
        val log = versionLog()
        assertEquals(1, Regex("""1\.26 \(#296""").findAll(log).count(), "not filed exactly once")
        val entry = log.substringAfter("1.26 (#296")
        listOf(
            "MINTS NO KEY and changes no figure",
            "TRUNCATED toward zero",
            "NOT A MEAN OVER SAMPLES",
            "9 of the 16 sets",
            "KEPT AND DESCRIBED",
            "moves no database column",
        ).forEach { assertTrue(it in entry, "the #296 entry does not state: $it") }
        assertTrue("1.26" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
    }
}
