package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who speaks a bare digit, as the published `voiceCues` description says
 * (#231, a further 1.23 entry).
 *
 * The description said a bare digit is "the guide counting a stroke out second
 * by second, or a timed set counting down". That names two producers. The
 * sensor-driven counter is a third. It counts the seconds of a phase it
 * detected. It still speaks today, on a set prescribed a tempo with nothing to
 * play it. Before #217 it also ran on timed sets, so a hold recorded by
 * v0.1.49 or earlier may carry its digits beside the clock's. A reader who took
 * the description at its word would read those digits as a countdown.
 *
 * Its own file, so no existing class grows.
 */
class SchemaBareDigitContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val voiceCues: String = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.getValue("voiceCues").jsonObject.getValue("description").jsonPrimitive
        .content

    private val versionLog: String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    @Test
    fun `the description names all three producers of a bare digit`() {
        assertTrue("THREE THINGS SPEAK A BARE DIGIT" in voiceCues, "voiceCues does not say how many producers")
        assertTrue("counting a stroke out second by second" in voiceCues, "the guide's tempo count is not named")
        assertTrue("a timed set's clock, counting down" in voiceCues, "the timed countdown is not named")
        assertTrue("the sensor-driven counter" in voiceCues, "the sensor-driven counter is not named")
        assertFalse(
            "-- the guide counting a stroke out second by second, or a timed set counting down --" in voiceCues,
            "the two-producer sentence is still published",
        )
    }

    @Test
    fun `the description says a timed set recorded before the fix may carry the sensor's digits`() {
        assertTrue("recorded by v0.1.49 or earlier" in voiceCues, "no build bound for the timed-set digits")
        assertTrue("(#217)" in voiceCues, "the fix that ended the timed-set digits is not named")
        assertTrue("never a rep number" in voiceCues, "the rep-number rule was lost")
    }

    @Test
    fun `the 1_23 entry records the description change`() {
        val entry = versionLog.substringAfter("1.23: MINTED HERE")
        assertTrue("(#231)" in entry, "the 1.23 entry does not record the voiceCues description change")
    }
}
