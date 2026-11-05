package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.26, a further entry (#279): `mountNotDeclared` is written on a
 * mount-free declaration too, where the unit the analysis fell back to read
 * as riding a load.
 *
 * No key moves, so this pins the published TEXT a reader is pointed at: the
 * version log files the entry once, and the word's own description names the
 * second shape and its cost. A geometry block naming no mount beside
 * `mountNotDeclared` would otherwise read as a contradiction.
 */
class SchemaMountFreeFallbackContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    @Test
    fun `the version log files the 279 entry under 1_26 once, adding no key`() {
        val log = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("description").jsonPrimitive.content
        assertEquals(1, Regex("""1\.26 \(#279""").findAll(log).count(), "the 1.26 #279 entry is not filed exactly once")
        val entry = log.substringAfter("1.26 (#279").substringBefore(" 1.26 (#")
        listOf("ADDS NO KEY", "`mountNotDeclared`", "NOT RETROACTIVE", "DATABASE_VERSION does not move")
            .forEach { assertTrue(it in entry, "the 1.26 #279 entry does not state: $it") }
    }

    @Test
    fun `the word's description names the mount-free shape and what it refuses with it`() {
        val reason = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject.getValue("properties").jsonObject
            .getValue("summary").jsonObject.getValue("properties").jsonObject.getValue("noRepsReason").jsonObject
            .getValue("description").jsonPrimitive.content
        val word = reason.substringAfter("mountNotDeclared:")
        listOf("From 1.26 (#279)", "declares NO mount", "sled", "analysed as before")
            .forEach { assertTrue(it in word, "mountNotDeclared's description does not state: $it") }
    }
}
