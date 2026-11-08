package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FURTHER 1.27 ENTRY (tempo notation): `tempoCompliance.prescribed` is
 * written by `Tempo.notation()`, which now writes a fractional or two-digit
 * tempo in the dash form so it parses back (core-model review 2026-11-06, F5).
 *
 * Pins the contract, not the notation itself -- that is `TempoTest`'s. Here:
 * the entry is filed once under 1.27, says it is retroactive and moves no key,
 * and quotes the forms `Tempo.notation()` actually writes; the prescribed
 * key's own description says the same.
 */
class SchemaTempoNotationContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "$name is not on the test classpath" }
            .readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private fun prescribed(): JsonObject = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("tempoCompliance").jsonObject
        .getValue("properties").jsonObject.getValue("prescribed").jsonObject

    private val marker = "FURTHER 1.27 ENTRY (tempo notation)"

    @Test
    fun `the version log files the tempo notation once, under 1_27, as retroactive and no key`() {
        assertEquals(1, Regex(Regex.escape(marker)).findAll(versionLog()).count(), "the entry is not filed once")
        val entry = versionLog().substringAfter(marker).substringBefore(" 1.28 (squat lockout count)")
        assertTrue("RETROACTIVE" in entry && "NOT RETROACTIVE" !in entry, "the entry does not say it is retroactive")
        assertTrue("No key is added, removed, renamed or retyped" in entry, entry.take(200))
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays put")
        assertTrue(
            versionLog().indexOf(marker) > versionLog().indexOf("1.27 (sensor-counted target cue)"),
            "the further entry is filed before the mint it extends",
        )
    }

    /** The forms the entry quotes are the ones [Tempo.notation] writes, old and new. */
    @Test
    fun `the entry quotes what notation writes`() {
        val entry = versionLog().substringAfter(marker)
        val dash = listOf(Tempo.parse("3-0-1.5-0").notation(), Tempo.parse("10-0-1-0").notation())
        val compact = listOf(Tempo.parse("3010").notation(), Tempo.parse("30X1").notation())
        (dash + compact).forEach { assertTrue("'$it'" in entry, "the entry does not quote '$it'") }
        dash.forEach { assertTrue("'$it'" in prescribed().getValue("description").jsonPrimitive.content, it) }
    }

    @Test
    fun `prescribed stays a string`() {
        assertEquals("string", prescribed().getValue("type").jsonPrimitive.content)
    }
}
