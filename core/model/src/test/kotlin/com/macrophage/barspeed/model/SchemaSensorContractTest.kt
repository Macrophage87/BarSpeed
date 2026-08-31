package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun setSensorsDescription() = setSensors()["description"]!!.jsonPrimitive.content

    private fun sensorProperty(name: String) =
        setSensors()["properties"]!!.jsonObject[name]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /**
     * Everything the version log says from its LAST "1.15:" marker onward.
     *
     * Last rather than first, and that is not a detail: the 1.14 paragraph
     * quotes the number too, in "worth restating because it was asked for as
     * 1.15", so `substringAfter` returns most of the 1.14 entry instead. The
     * real entry is the final one because the log is in version order.
     */
    private fun entry115() = exportVersionLog().substringAfterLast("1.15:")

    /**
     * `GuideScreen.kt` read as text, the same way [GuidePromptContractTest]
     * reads it: `core/model/build.gradle.kts` puts the real source file on this
     * module's test resources, so what is asserted is the copy the COPY PLAN
     * PROMPT button actually puts on the lifter's clipboard rather than a
     * transcription of it.
     */
    private val shippedPrompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

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

    /**
     * DIFFERENTIAL, issue #198 round 1. The published contract says PAIRED
     * wherever it explains a gap, because the app never observes connected.
     *
     * `SensorCapturePolicy.roster` takes `pairedImuAddresses`, `preferredAddress`
     * and `roleByAddress` and no connection state of any kind, and its hardware
     * input is `DeviceRegistry.knownDevices` -- a DataStore-persisted list of
     * REMEMBERED units decoded from preferences, carrying no link state. So a
     * shortfall is written about two units the app has a MEMORY of, one of
     * which may have spent the whole session switched off in a bag.
     *
     * That is the distinction the owner ordered pinned for this issue --
     * "paired is not connected" -- and every description was written the other
     * way round. Asserted as an ABSENCE over several phrasings, because one
     * sentence can be reworded and the false claim would survive a substring
     * check on any single wording of it.
     *
     * The CAPTURE rule is deliberately NOT covered and keeps saying connected,
     * because it is true: what reaches the archive is what streamed. What is
     * covered is arming and the shortfall, which are decided from the paired
     * list alone.
     */
    @Test
    fun `the published sensor contract says paired wherever it explains a gap`() {
        val explanations =
            mapOf(
                "setSensors" to setSensorsDescription(),
                "setSensors.count" to sensorProperty("count"),
                "setSensors.expected" to sensorProperty("expected"),
                "setSensors.shortfall" to sensorProperty("shortfall"),
            )
        listOf("connected unit", "units were connected", "units are connected", "two connected")
            .forEach { phrase ->
                explanations.forEach { (where, text) ->
                    assertFalse(
                        phrase in text.lowercase(),
                        "$where still calls a shortfall a fact about $phrase; the app knows paired",
                    )
                }
            }
        assertFalse(
            "decided by the connected hardware" in setSensorsDescription(),
            "the published block still says ARMING is decided by connected hardware; it reads the paired list",
        )
        assertFalse(
            "units were connected and the app could not tell them apart" in entry115().lowercase(),
            "the 1.15 entry still defines the shortfall over connected units",
        )
        assertTrue(
            "PAIRED IS NOT CONNECTED" in sensorProperty("shortfall"),
            "the published shortfall never warns a reader that paired is not connected",
        )
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. The published shortfall says it
     * describes the DEVICE ROSTER rather than the set, so a reader meeting one
     * on every row of a session reads it correctly.
     *
     * The decision this pins: the shortfall STAYS on every set. With the count
     * gate gone, `roster` consults `DualSensorSetup.step` first and
     * unconditionally, and `step` considers every paired unit -- so a lifter
     * running two labelled sensors with one stale unit still paired writes
     * `rolesUnassigned` on every set of every session. Suppressing it after the
     * first would make a session recorded ENTIRELY under an unusable pair
     * indistinguishable from one recorded with a single sensor, which is the
     * exact distinction this key exists for and the one nothing else in the
     * document can carry since `plannedCount` went. What was wrong was the
     * wording, not the repetition, so the document says which of the two a
     * reader is looking at.
     */
    @Test
    fun `the published shortfall says it describes the roster and repeats on every set`() {
        val shortfall = sensorProperty("shortfall")

        assertTrue(
            "THIS DESCRIBES THE DEVICE ROSTER, NOT THE SET" in shortfall,
            "the published shortfall never says it is a fact about the roster rather than the set",
        )
        assertTrue(
            "every set" in shortfall,
            "the published shortfall never warns that it repeats across a whole session",
        )
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. The published shortfall names the older
     * rows that publish NO reason at all, so its own absence stays readable.
     *
     * Before this branch, a non-dual set under a plan declaring two sensors
     * stored the reason as a `plannedCount` of 2 beside a `count` of 1. This
     * build does not read that key, so such a row now re-exports as `count` 1
     * with an empty `expected` and no shortfall -- which the sentence "absent
     * when nothing was in the way" reads as the opposite of what happened.
     * `SessionExportSensorsTest.a row written under the retired planned count
     * re-exports with no reason` pins the behaviour; this pins that the
     * document admits it.
     */
    @Test
    fun `the published shortfall names the older rows that publish no reason`() {
        val shortfall = sensorProperty("shortfall")

        assertTrue(
            "plannedCount" in shortfall,
            "the published shortfall never mentions the key that used to carry the reason",
        )
        assertTrue(
            "not recoverable from this document" in shortfall,
            "the published shortfall never says a pre-1.15 set's reason is unrecoverable",
        )
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. The 1.15 entry says which older exports
     * carry `plannedCount`, and that this file no longer validates them.
     *
     * Two false statements sat in one sentence. "Exports written before 1.15
     * carry plannedCount" is a false universal in text an LLM is handed: the
     * key entered at 8b88d71 under SCHEMA_VERSION 1.13, so 1.0 through 1.12
     * carry no `sensors` block at all and a reader told otherwise reads its
     * absence as data loss. And 1.15 is the first version to REMOVE a key, so a
     * genuine 1.14 export now FAILS against the schema whose enum still admits
     * "1.14" -- measured with the ajv the CI step runs, `ajv-cli@5` with
     * `--spec=draft2020`: the previously published 1.14 example against this
     * schema gives exactly one error, `additionalProperties` on `plannedCount`
     * at `/exercises/0/sets/0/sensors`. Nothing in the branch named that.
     */
    @Test
    fun `the 1_15 entry says which older exports carry the retired key and that they fail here`() {
        val entry = entry115()

        assertFalse(
            "Exports written before 1.15 carry plannedCount" in entry,
            "the 1.15 entry still says every pre-1.15 export carries the key; 1.0-1.12 have no block at all",
        )
        assertTrue(
            "1.13 or 1.14" in entry,
            "the 1.15 entry never says which versions actually carry plannedCount",
        )
        assertTrue(
            "additionalProperties" in entry,
            "the 1.15 entry never says a genuine 1.14 export fails against this file",
        )
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. `shortfall` is the key this round adds
     * to the published contract, and the ajv step validates only the two
     * example payloads -- so a key absent from both is a key ajv never sees.
     *
     * The repo already pins exactly this elsewhere: `EffortContractTest` fails
     * with "no set in the published plan example is declared a warm-up so ajv
     * never validates the key", and `SchemaContractTest` with the equivalent
     * for the sensors block itself. That block's example is a DUAL set, which
     * exercises neither `shortfall` nor the empty `expected` the contract calls
     * its most informative state; this covers both, on one set.
     */
    @Test
    fun `the published example exercises the shortfall word and the empty expected list`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val blocks = sets.mapNotNull { it.jsonObject["sensors"]?.jsonObject }
        val gap =
            assertNotNull(
                blocks.firstOrNull { it["shortfall"] != null },
                "no set in the published example carries a shortfall, so ajv never validates the key",
            )

        assertTrue(
            gap.getValue("shortfall").jsonPrimitive.content in
                enumOf(setSensors()["properties"]!!.jsonObject["shortfall"]!!.jsonObject),
            "the example's shortfall is not one of the published words",
        )
        assertEquals(1, gap.getValue("count").jsonPrimitive.int, "a set that recorded one stream says so")
        assertTrue(
            gap.getValue("expected").jsonArray.isEmpty(),
            "the example never shows the empty expected list the contract calls its most informative state",
        )
        assertTrue(gap.getValue("present").jsonArray.isEmpty(), "an unroled stream reached present")
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. The copy on the lifter's clipboard
     * carries the same two corrections the published schema does.
     *
     * `PLAN_PROMPT` is the only statement of this contract anything actually
     * sends anywhere, so a false sentence here is one an LLM reads and the
     * schema's correction never reaches. It said the shortfall was a fact about
     * CONNECTED units, and it repeated the false universal about every pre-1.15
     * export carrying `plannedCount`.
     *
     * The capture rule in the same file keeps saying connected, and correctly:
     * what reaches the archive is what streamed.
     */
    @Test
    fun `the copy on the lifter's clipboard says paired and dates the retired key`() {
        assertFalse(
            "two units were connected and the app could not tell them apart" in shippedPrompt,
            "the shipped prompt still calls a shortfall a fact about connected units",
        )
        assertFalse(
            "why two connected units produced one stream" in shippedPrompt,
            "the shipped prompt still explains the shortfall word in terms of connected units",
        )
        assertFalse(
            "exports written before schema 1.15 carry one" in shippedPrompt,
            "the shipped prompt still tells an LLM every pre-1.15 export carries plannedCount",
        )
        assertTrue(
            "exports declaring 1.13 or 1.14 carry one" in shippedPrompt,
            "the shipped prompt never says which versions actually carry plannedCount",
        )
    }
}
