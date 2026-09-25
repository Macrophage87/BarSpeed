package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published export says about a timed set the lifter ended before
 * its clock did (#288).
 *
 * `voiceCues` said `Set ended` means a GUIDED set ended without `Done`. From
 * #288 a timed set the lifter stops before its target says and writes the
 * same word, where the timed voice is on, so a reader meeting `Set ended` on a
 * hold's track must not read it as a guided set; and `rest_s` gains the case.
 *
 * Narrow, and said so: this checks the document STATES it. The rule is
 * `TimedStopCallTest`'s in `:core:dsp`; the Kotlin twin and the log on
 * [SessionExport] are kept in step by hand.
 */
class SchemaTimedStopContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun setDescription(key: String) = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    @Test
    fun `voiceCues says Set ended also ends a timed set the lifter stopped`() {
        val cues = setDescription("voiceCues")
        assertTrue(
            "From 1.22 (#288) `Set ended` also ends a timed set the lifter stopped before its clock did" in cues,
            "voiceCues does not state the timed case",
        )
    }

    @Test
    fun `rest_s says such a set rests from its Set ended`() {
        val rest = setDescription("rest_s")
        assertTrue(
            "from 1.22 (#288) a timed set the lifter stopped before its clock rests from its `Set ended`" in rest,
            "rest_s does not state the timed case",
        )
    }

    @Test
    fun `the 1_22 log files the timed stop once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES A TWELFTH ENTRY (#288"
        assertEquals(1, versionLog.split(marker).size - 1, "the #288 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say stored tracks keep what they said")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database is untouched")
    }
}
