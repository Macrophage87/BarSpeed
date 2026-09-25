package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the published session export says about a hold the CLOCK ended whose
 * armed unit saw the implement let go before the target (#311).
 *
 * Its own class rather than a case in [SchemaHoldEndContractTest], which pins
 * export 1.21's entry that ADDED the key: this is one further 1.22 entry and
 * two descriptions that entry had to move, all by one change.
 *
 * WHAT MOVED. Under 1.21 `durationEndedBy` said `clock` means `duration_s` IS
 * the target and `sensor` means the release came before a TAP. From #311 a
 * hold the clock ended records the release instead of the target where the
 * armed unit saw one 1 to 20 whole seconds before it, so both of those
 * sentences had a case they no longer covered, and `duration_s` gained one.
 *
 * Narrow, and said so: this checks the document STATES it. What the app
 * decides is `HoldEndPolicyDifferentialTest` and `HoldEndPolicyTest` here, and
 * `HoldReleaseFieldTest` in `:core:dsp`; the Kotlin twin of the log, on
 * [SessionExport.SCHEMA_VERSION], is kept in step by hand.
 */
class SchemaClockReleaseContractTest {
    private val schema = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private val versionLog = schema["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun setDescription(key: String) = schema["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!
        .jsonPrimitive.content

    @Test
    fun `durationEndedBy says a clock-ended hold can read sensor, and when clock still stands`() {
        val endedBy = setDescription("durationEndedBy")
        assertTrue(
            "rather than to the target on a hold the clock ended" in endedBy,
            "durationEndedBy still says a sensor end always runs to before a tap",
        )
        assertTrue(
            "a sensor hold may never have been tapped at all" in endedBy,
            "durationEndedBy does not say a sensor hold may carry no tap",
        )
        val clockStands = "only where no armed unit's stream showed the implement being let go 1 to 20 whole seconds"
        assertTrue(clockStands in endedBy, "durationEndedBy still says every clock end records the target")
    }

    @Test
    fun `duration_s says a clock-ended set can be shorter than its target, and never by a late release`() {
        val duration = setDescription("duration_s")
        val clockCase = "From 1.22 (#311) a set the CLOCK ended carries the same span to a release instead of"
        assertTrue(clockCase in duration, "duration_s does not state the clock-ended release case")
        assertTrue(
            "a release at or after the target, or none, leaves the target" in duration,
            "duration_s does not say a completed hold keeps its target",
        )
    }

    @Test
    fun `the 1_22 log files the clock-ended release once, as a further entry that changes no key`() {
        val marker = "1.22 TAKES A SEVENTH ENTRY (#311"
        assertEquals(1, versionLog.split(marker).size - 1, "the #311 entry is filed exactly once")
        val entry = versionLog.substringAfter(marker)
        assertTrue("CHANGES NO KEY" in entry, "the entry does not say no key moved")
        assertTrue(
            "a hold that ran to its target is never shortened" in entry,
            "the entry does not say a completed hold keeps its target",
        )
        assertTrue("failedByLifter never moves" in entry, "the entry does not say the lifter's verdict is untouched")
        assertTrue("can now derive short" in entry, "the entry hides that the derived failure can move")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say archived sets keep their word")
        assertTrue(
            "DATABASE_VERSION does NOT move" in entry,
            "the entry does not say the database is untouched",
        )
    }
}
