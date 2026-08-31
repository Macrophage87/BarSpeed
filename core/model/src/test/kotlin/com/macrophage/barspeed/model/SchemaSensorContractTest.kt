package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What issue #198 moved in the two published contracts, pinned against the
 * REAL documents in `docs/schemas/` exactly as [SchemaContractTest] is.
 *
 * A second file rather than more cases in that one. RETRACTION, round 1: this
 * KDoc said detekt's LargeClass rule REDS [SchemaContractTest] at its current
 * size, and this branch's own green run falsifies it -- CI 33346525695 step 5,
 * ktlint + detekt, is success at 68c7db55159336509c518bad189410769233150f with
 * that file at 1079 lines. What is true, and all that was ever measured, is
 * that LargeClass appears nowhere in `config/detekt/detekt.yml` and every
 * module sets `buildUponDefaultConfig = true`, so the rule runs at detekt's
 * default and [SchemaContractTest] is already 1079 lines; the cases went in
 * their own file rather than push a file of that size further. Why the rule
 * does not fire on a 1079-line test class was NOT established -- detekt's
 * default excludes for test source paths are the likely mechanism and no
 * detekt run here confirmed it.
 *
 * The sensor contract is therefore pinned in two places; everything about the
 * BLOCK -- its keys, its closure, its role vocabulary, its example -- stays
 * there, and what is here is only what #198 changed.
 */
class SchemaSensorContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun enumOf(obj: JsonObject) = obj["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /**
     * DIFFERENTIAL, issue #198. The published shortfall vocabulary is the two
     * gaps the app can still name, spelled as the export spells them.
     *
     * Membership is asserted against [DualShortfall.entries] rather than
     * counted by hand, so a third gap added to the enum reddens here until it
     * is published; the spellings are written out because they are the WIRE
     * form and a reader of the document has nothing else to check them
     * against. lowerCamel, as `velocityLossBasis` values already are.
     */
    @Test
    fun `the published shortfall vocabulary is the gaps the app can name`() {
        val sensors = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!
            .jsonObject["properties"]!!.jsonObject
        val shortfall =
            assertNotNull(sensors["shortfall"], "the published sensors block declares no shortfall").jsonObject

        assertEquals(
            setOf("rolesUnassigned", "rolesCollide"),
            enumOf(shortfall),
            "the published shortfall words and the ones the exporter writes disagree",
        )
        assertEquals(
            DualShortfall.entries.size,
            enumOf(shortfall).size,
            "a gap the app can name is missing from the published vocabulary, or the reverse",
        )
    }

    /**
     * DIFFERENTIAL, issue #198. The plan's 1.10 entry records that `sensors`
     * stopped deciding anything.
     *
     * A new version rather than an edit to 1.9, and the reason is the one this
     * log gives every time: 1.9 SHIPPED, in v0.1.45, read at the tag. The key
     * itself is kept and accepted so that a plan a coach wrote by hand last
     * month still imports; what changes is that it decides nothing, which is
     * a change in MEANING and therefore not additive.
     */
    @Test
    fun `the plan's 1_10 entry records that the sensors key stopped deciding capture`() {
        val description =
            schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content

        assertTrue("1.10:" in description, "the plan version log has no 1.10 entry")
        assertTrue(
            "ignored" in description,
            "the 1.10 entry never says the sensors key is ignored, which is the whole change",
        )
        assertTrue(
            "v0.1.45" in description,
            "the 1.10 entry does not say which release closed 1.9, so nobody can check it at a tag",
        )
    }

    /**
     * DIFFERENTIAL, issue #198. The export's 1.15 entry records the retirement
     * of `plannedCount`, and it is a new number rather than a fifth change
     * under 1.14.
     *
     * 1.14's own entry claims "1.14 is UNRELEASED" and that sentence is FALSE
     * at the SHA this branch starts from: v0.1.45 shipped SessionExport
     * SCHEMA_VERSION 1.14, read at the tag. The four changes that rode under
     * 1.14 did so on the strength of that claim, correctly at the time; the
     * window closed when the tag was cut and nobody went back to say so. So
     * removing a required key mints 1.15, and the false sentence is corrected
     * in the same edit rather than reworded around.
     */
    @Test
    fun `the export's 1_15 entry records the retired planned count and closes 1_14`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content

        assertTrue("1.15:" in description, "the export version log has no 1.15 entry")
        assertTrue(
            "plannedCount" in description,
            "the 1.15 entry never names the key it removed",
        )
        assertTrue(
            "1.14 is UNRELEASED" !in description,
            "the log still calls 1.14 unreleased; v0.1.45 shipped it",
        )
    }
}
