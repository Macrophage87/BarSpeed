package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published export says about a timed CARRY the clock ended (#314).
 *
 * The 1.22 entry for #311 moved `durationEndedBy` and `duration_s` to say a
 * set the clock ended can publish the span to a release. From #314 that is a
 * HOLD only: a carry the clock ended is not offered the release, so it
 * publishes `clock` and its target whatever its stream shows.
 *
 * Narrow, and said so: this checks the document STATES it. The rule is
 * `HoldEndCarryGuardTest`'s; the Kotlin twin of both descriptions and of the
 * log is kept in step by hand.
 */
class SchemaCarryClockContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun setDescription(key: String) = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    private val carryRule = "a timed CARRY the clock ended is never offered the release (#314)"

    @Test
    fun `durationEndedBy says a carry the clock ended reads clock whatever its stream shows`() {
        val endedBy = setDescription("durationEndedBy")
        assertTrue("from 1.22 (#311), on a HOLD, only where" in endedBy, "clock is not limited to a hold")
        assertTrue(carryRule in endedBy, "durationEndedBy does not state the carry case")
    }

    @Test
    fun `duration_s says a carry the clock ended carries its target`() {
        val duration = setDescription("duration_s")
        assertTrue("a CARRY the clock ended carries the target" in duration, "duration_s does not state the carry case")
        assertTrue("no walking-carry stream" in duration, "duration_s does not say why")
    }

    @Test
    fun `the 1_22 log files the carry guard once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES A TENTH ENTRY (#314"
        assertEquals(1, versionLog.split(marker).size - 1, "the #314 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say stored carries keep their word")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database is untouched")
    }
}
