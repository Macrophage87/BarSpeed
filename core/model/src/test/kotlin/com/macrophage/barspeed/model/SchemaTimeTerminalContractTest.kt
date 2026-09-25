package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the published export says about a timed set's `Time` once it is a
 * terminal word (#295).
 *
 * `voiceCues` told a reader wanting the moment a set was called over to look
 * for `Done` or `Set ended`, and `rest_s` said the rest after a set nothing
 * called over -- "every hold" -- starts at the set's end instant. From #295
 * `Time` calls a timed set over: it bounds the raw archive's roll window and
 * seeds the rest. Both sentences had a case they no longer covered; the
 * second also never said a release-decided hold rests from its release.
 *
 * Narrow, and said so: this checks the document STATES it. The rule is
 * `HoldTerminalCueFieldTest`'s and `FailedSetBoundaryTest`'s in `:core:dsp`;
 * the Kotlin twin and the log on [SessionExport] are kept in step by hand.
 */
class SchemaTimeTerminalContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun setDescription(key: String) = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    @Test
    fun `voiceCues names Time among the words that call a set over`() {
        val cues = setDescription("voiceCues")
        assertTrue("or for `Time` on a timed set (from 1.22, #295)" in cues, "voiceCues does not name Time")
        assertTrue("`rollExcursionBasis` reads `workingWindow`" in cues, "voiceCues does not say Time bounds it")
    }

    @Test
    fun `rest_s no longer says every hold rests from its end instant`() {
        val rest = setDescription("rest_s")
        assertFalse("which is every hold and every set recorded with the voice off" in rest, "rest_s still says so")
        assertTrue(
            "from 1.22 (#295) any other timed set the clock ended rests from its `Time`" in rest,
            "rest_s does not say a clock-ended timed set rests from Time",
        )
        assertTrue("rests from that release" in rest, "rest_s does not say a release-decided hold rests from it")
    }

    @Test
    fun `the 1_22 log files Time once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES AN ELEVENTH ENTRY (#295"
        assertEquals(1, versionLog.split(marker).size - 1, "the #295 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue("RETROACTIVE in the raw archive" in entry, "the entry hides that re-exported holds change word")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database is untouched")
    }
}
