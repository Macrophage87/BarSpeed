package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.26, a documentation entry (#275): the version log says when the
 * three set-level means were published, which no entry ever did.
 *
 * No key moves, so this pins the entry and the declarations it names, not a
 * behaviour. The history it states was read at the tags (v0.1.0, v0.1.18,
 * v0.1.19) when it was written; it is not re-derivable from this tree, so it
 * is pinned as text.
 */
class SchemaSummaryMeansContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val means = listOf("meanConVel_mps", "meanRom_m", "meanConPower_w")

    @Test
    fun `the version log files the three means under 1_26 once, as documentation`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#275""").findAll(log).count(), "the 1.26 #275 entry is not filed exactly once")
        val entry = log.substringAfter("1.26 (#275").substringBefore(" 1.26 (#")
        (means.map { "`summary.$it`" } + listOf("ADDS NO KEY", "v0.1.0", "v0.1.19", "REJECTS", "never as a mint"))
            .forEach { assertTrue(it in entry, "the 1.26 #275 entry does not state: $it") }
    }

    @Test
    fun `every mean the entry names is declared and described on the summary`() {
        val summary = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject.getValue("properties").jsonObject
            .getValue("summary").jsonObject.getValue("properties").jsonObject
        means.forEach { key ->
            val declared = summary.getValue(key).jsonObject
            assertEquals("number", declared.getValue("type").jsonPrimitive.content, "$key is not a number")
            assertTrue("description" in declared, "$key has no description saying what it is taken over")
        }
        assertEquals(
            means.toSet(),
            SetSummaryExport.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
                .filter { it in means }.toSet(),
            "a mean the entry names is not written by SetSummaryExport",
        )
    }
}
