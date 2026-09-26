package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
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
 *
 * 1.13 then shipped in v0.1.56, and #327 changed what a WRITTEN
 * `sensorOnStack` true does to the rule, which is plan 1.14 and is pinned in
 * `PlanWrittenStackContractTest`. The 1.13 entry is left as what 1.13 said,
 * and the pins on it below stand.
 */
class PlanStackInversionContractTest {
    private fun plan(): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = plan()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun sensorInverted(): String = plan()["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!
        .jsonObject["sensorInverted"]!!.jsonObject["description"]!!.jsonPrimitive.content

    // `the app writes plan schema 1_13` lived here and is DELETED, not
    // reworded: the app writes 1.14 from the #327 mint, so an assertion that
    // it writes 1.13 is simply false. The version the app currently writes is
    // pinned in PlanWrittenStackContractTest, beside the mint that moved it.

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
     *
     * Moved by #327: the pointer read "is stated once, in the 1.13 entry",
     * and since 1.14 the entry that says what a written stack mount changes
     * is a second one, so "once" is deleted and the pin reads the pointer
     * that names 1.13. The roll clause is qualified to the stack default,
     * where 1.14 leaves it.
     */
    @Test
    fun `sensorInverted points at the 1_13 entry and no longer says its rule moved no version`() {
        val description = sensorInverted()
        assertTrue(
            "is stated in the 1.13 entry of the version log above" in description,
            "sensorInverted does not point at the 1.13 entry for what an absent or null value means",
        )
        assertFalse(
            "did not move the plan format" in description,
            "sensorInverted still says the rule did not move the plan format, which 1.13 makes false",
        )
        assertTrue(
            "where the stack mount is the stack default `sensorOnStack` states for the machines it lists, it " +
                "holds for a set whose analysed unit's own roll says it rode the stack" in description,
            "sensorInverted still says an omitted key resolves true whichever unit the set is analysed from",
        )
    }
}
