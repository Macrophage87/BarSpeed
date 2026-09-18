package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema 1.21's THIRD entry: each rep may say whether the analysis can bound its
 * displacement, and the two set-level range claims are taken over the reps it
 * holds for. Issue #291.
 *
 * In its own file rather than in `SchemaContractTest`, on the grounds
 * `SchemaArtefactSampleContractTest` states: that class sits on detekt's
 * `LargeClass` limit, and a version entry has several halves that must move
 * together, so grouping them says which they are.
 *
 * THE NUMBER: 1.21, already minted by #300 and extended by #290 -- read this
 * round rather than relayed. `git tag --sort=-creatordate | head -1` is v0.1.53
 * and its own `SessionExport.kt` reads `SCHEMA_VERSION = "1.20"`, so 1.21 is
 * UNRELEASED and a third entry is correct rather than a new mint. What is
 * asserted below cannot go stale whichever branch lands first: that the key is
 * published, that the number is ACCEPTED, and that the versions before it still
 * are, with the exporter's own constant read rather than a literal compared
 * against it.
 *
 * DIFFERENTIALS, not green pins. This file arrives with the commit that narrows
 * the figures; the DSP half's differentials are `:core:dsp`'s
 * `RomWithholdingDifferentialTest` and the export half's are `:core:data`'s
 * `SessionExportRomBoundTest`.
 */
class SchemaRomBoundContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun exportSchema() = schema("session-export.schema.json")

    private fun repDef() = exportSchema()["\$defs"]!!.jsonObject["repMetrics"]!!.jsonObject

    private fun versionEnum() = exportSchema()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun versionLog() = exportSchema()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the per-rep bound is published and is a boolean`() {
        // A boolean rather than a reason word. There are two ways a displacement
        // goes unbounded -- the inter-anchor interval was shared, or one of the
        // intervals the rep crosses was closed by an anchor the starvation
        // escape took, so nothing capped what the correction erased there -- and
        // both are facts about the INTEGRATOR rather than about this rep, so
        // neither is a reason a reader of one row could act on. RomBound states
        // them; the row says only whether a limit exists.
        val flag = assertNotNull(
            repDef()["properties"]!!.jsonObject["romBounded"],
            "a rep declares no romBounded key",
        ).jsonObject
        assertEquals("boolean", flag["type"]!!.jsonPrimitive.content, "romBounded type")
    }

    /**
     * The rep object stays CLOSED, which is what makes the method above
     * load-bearing: adding the Kotlin field without the schema key would make
     * every detailed export of an analysed set fail the ajv step four CI steps
     * after this module's tests pass.
     */
    @Test
    fun `the rep still forbids keys it does not declare`() {
        assertEquals(
            false,
            repDef()["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the rep object stopped being closed",
        )
    }

    /**
     * The key is not required, which is the whole of how absence is expressed: a
     * rep analysed before the question was asked publishes NO key, and that is a
     * different fact from `false`.
     *
     * `rom_m` itself stays required and typed as it was. The rule withholds a rep
     * from the SET's range claims and never deletes the rep's own displacement,
     * and a reader who finds `rom_m` absent on a 1.21 document is reading a bug
     * rather than this rule.
     */
    @Test
    fun `absence is expressible, so the key is not required and rom_m still is`() {
        val required = repDef()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("romBounded" !in required, "a rep analysed before the flag could not be published")
        assertTrue("rom_m" in required, "a rep stopped publishing its own displacement")
        assertEquals(
            "number",
            repDef()["properties"]!!.jsonObject["rom_m"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "the per-rep displacement was retyped",
        )
    }

    /**
     * The published example carries the key on every row of its detailed set, so
     * the ajv step in `ci.yml` validates the block rather than passing a document
     * that never exercises it.
     *
     * And it exercises the EFFECT, not only the shape: the unbounded row is the
     * largest displacement in the set, so a consumer averaging
     * `repMetrics[].rom_m` reads 0.84 m where `summary.meanRom_m` reads 0.6 m,
     * and the naive spread is 57.1 % against a published 1.2 %. A document whose
     * unbounded row sat in the middle of the set would demonstrate nothing.
     */
    @Test
    fun `the example publishes the flag on every detailed row and shows the narrowing`() {
        val detailed = exampleSets().filter { it["repMetrics"] != null }
        assertEquals(1, detailed.size, "the example carries more than one detailed set")
        val rows = detailed.single()["repMetrics"]!!.jsonArray.map { it.jsonObject }
        val flags = rows.map { it["romBounded"]!!.jsonPrimitive.content.toBoolean() }
        assertEquals(listOf(true, true, true, true, false), flags, "romBounded per row in the published example")

        val roms = rows.map { it["rom_m"]!!.jsonPrimitive.content.toDouble() }
        val bounded = roms.filterIndexed { i, _ -> flags[i] }
        assertEquals(
            roms.max(),
            roms.filterIndexed { i, _ -> !flags[i] }.max(),
            "the unbounded row is not the set's largest, so the example shows nothing",
        )

        val summary = detailed.single()["summary"]!!.jsonObject
        assertEquals(
            round3(bounded.average()),
            summary["meanRom_m"]!!.jsonPrimitive.content.toDouble(),
            "summary.meanRom_m is not the mean over the bounded rows",
        )
        assertEquals(
            round1(spreadPct(bounded)),
            summary["romSpread_pct"]!!.jsonPrimitive.content.toDouble(),
            "summary.romSpread_pct is not the deviation over the bounded rows",
        )
        // And the naive figures a consumer would compute, so the gap is on the
        // record rather than merely implied.
        assertEquals(0.84, round3(roms.average()), "the naive mean over every row")
        assertEquals(57.1, round1(spreadPct(roms)), "the naive spread over every row")
    }

    /**
     * The version log explains the key it publishes and says the two summary
     * figures moved population.
     *
     * Narrow, and said so: this cannot check the entry is RIGHT, only that a
     * consumer reading the one place version changes are described is told the
     * key exists and told that two existing keys changed meaning. A key published
     * with no entry is the failure `the 1_13 version log names the rep marks` was
     * written for.
     */
    @Test
    fun `the version log names the key, the two narrowed figures and the absence rule`() {
        val log = versionLog()
        assertTrue("romBounded" in log, "the version log never mentions the key it publishes")
        assertTrue("meanRom_m" in log, "the version log does not say meanRom_m moved population")
        assertTrue("romSpread_pct" in log, "the version log does not say romSpread_pct moved population")
        assertTrue("NOT purely additive" in log, "the version log presents a meaning change as additive")
    }

    /**
     * The number the key rides under is accepted, and the versions before it
     * still are, so this is additive in the version sense rather than a break.
     *
     * `SCHEMA_VERSION` is read rather than compared against a literal, for the
     * reason this class's KDoc gives.
     */
    @Test
    fun `1_21 is accepted and every earlier version still is`() {
        assertTrue("1.21" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.21 is not in the accepted set")
        assertTrue("1.21" in versionEnum(), "1.21 is not in the published enum")
        assertTrue(SessionExport.SCHEMA_VERSION in versionEnum(), "the exporter writes a version ajv rejects")
        listOf("1.18", "1.19", "1.20").forEach {
            assertTrue(it in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "$it left the accepted set")
            assertTrue(it in versionEnum(), "$it left the published enum, so this is a break beyond the key")
        }
    }

    /**
     * `VALID_REFUSED_DETECTION_REASONS` gains NO word, on the same grounds
     * #290's entry records: no detection is refused by this rule. A refused
     * detection is removed from every figure; a rep whose displacement is
     * unbounded is removed from two, and keeps its own row.
     */
    @Test
    fun `the refusal vocabulary did not gain a word for this rule`() {
        assertEquals(setOf("unpairedRangeOutlier"), SessionExport.VALID_REFUSED_DETECTION_REASONS)
    }

    /** The Kotlin twin exists under the serial name the schema declares. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the Kotlin twin publishes under the declared serial name`() {
        assertTrue(
            "romBounded" in RepMetricsExport.serializer().descriptor.elementNames.toList(),
            "RepMetricsExport does not publish romBounded",
        )
    }

    private fun spreadPct(values: List<Double>): Double {
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return kotlin.math.sqrt(variance) / mean * 100.0
    }

    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
}
