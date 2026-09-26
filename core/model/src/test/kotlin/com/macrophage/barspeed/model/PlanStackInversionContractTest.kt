package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan schema 1.13: what the PUBLISHED plan schema says an omitted
 * `sensorInverted` means since the stack inversion rule (#317).
 *
 * A class of its own, as [PlanGeometryNullabilityContractTest] is for 1.12
 * and for the reason its KDoc gives: [SchemaContractTest] sits on detekt's
 * `LargeClass` limit. That
 * class's relational pins -- the published enum equals
 * `PlanFile.SUPPORTED_SCHEMA_VERSIONS`, the published example declares
 * `PlanFile.SCHEMA_VERSION` -- move with the constants and need no edit.
 *
 * 1.12 shipped saying an omitted `sensorInverted` resolves to false with
 * nothing to infer, and the rule changes that on stack sets driving down, so
 * the rule is a new version rather than an edit to a released one. #323
 * narrowed the rule to the analysed unit while 1.13 was still unreleased, so
 * the 1.13 entry was corrected in place rather than minting 1.14.
 */
class PlanStackInversionContractTest {
    private fun plan(): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = plan()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun sensorInverted(): String = plan()["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!
        .jsonObject["sensorInverted"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /**
     * The version the app writes, pinned beside the mint that moved it.
     *
     * Moved here from `PlanGeometryNullabilityContractTest`, whose copy naming
     * 1.12 is deleted rather than carried forward. It lives with whichever
     * mint is current so that the next one has one place to move it from,
     * and so that a bump left half-done -- the schema enum widened,
     * `PlanFile.SCHEMA_VERSION` left behind -- reds here rather than shipping
     * a prompt asking for a version the app does not write.
     */
    @Test
    fun `the app writes plan schema 1_13`() {
        assertEquals("1.13", PlanFile.SCHEMA_VERSION)
    }

    /**
     * The 1.13 entry states the rule, and which unit it reaches.
     *
     * Since #323 the rule is applied to the ANALYSED unit, and only where that
     * unit's own roll says it rode the stack; a unit on the handle or the rope
     * of a stack machine whose plan leaves `sensorOnStack` to the stack
     * default is read uninverted. The entry said the opposite -- "is read
     * inverted by it, with drive and return swapped" -- which was true of the
     * #317 rule and is deleted, not reworded, with its pin moved here. The
     * entry is where a plan writer learns which unit the rule reads, so its
     * absence is a contract gap, not a style one.
     */
    @Test
    fun `the plan's 1_13 entry states the stack inversion rule`() {
        val entry = versionLog().substringAfter("1.13:", missingDelimiterValue = "")
        assertTrue(entry.isNotEmpty(), "the plan version log has no 1.13 entry")
        val rule = "with `concentric` 'down' in the vertical plane resolves an absent or null `sensorInverted` to TRUE"
        assertTrue(
            rule in entry,
            "the 1.13 entry never states what an omitted sensorInverted resolves to on a stack set driving down",
        )
        assertTrue(
            "to TRUE for a set whose analysed unit's own roll says it rode the stack" in entry,
            "the 1.13 entry never says the rule reaches only an analysed unit whose roll says stack",
        )
        assertTrue(
            "is read uninverted, and so is a unit whose window held too few samples to measure" in entry,
            "the 1.13 entry never says a unit whose roll moved, or was not measured, is read uninverted",
        )
        assertFalse(
            "is read inverted by it, with drive and return swapped" in entry,
            "the 1.13 entry still says a handle-mounted unit under a seeded stack mount is read inverted",
        )
    }

    /**
     * The property points at the 1.13 entry for what an omission means, and
     * no longer claims the rule left the plan format where it was.
     */
    @Test
    fun `sensorInverted points at the 1_13 entry and no longer says its rule moved no version`() {
        val description = sensorInverted()
        assertTrue(
            "is stated once, in the 1.13 entry of the version log above" in description,
            "sensorInverted does not point at the 1.13 entry for what an absent or null value means",
        )
        assertFalse(
            "did not move the plan format" in description,
            "sensorInverted still says the rule did not move the plan format, which 1.13 makes false",
        )
        assertTrue(
            "resolves to TRUE for a set whose analysed unit's own roll says it rode the stack" in description,
            "sensorInverted still says an omitted key resolves true whichever unit the set is analysed from",
        )
    }
}
