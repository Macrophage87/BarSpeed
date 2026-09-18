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
 * Schema 1.21's artefact-count keys: a set may say how many of its samples the
 * sensor cannot have measured, and each rep may say how many of them landed
 * inside itself. Issues #290 and #255.
 *
 * In its own file rather than in `SchemaContractTest`, on the grounds
 * `SchemaWorkStartBoundContractTest` states: that class sits on detekt's
 * `LargeClass` limit, and a version entry has several halves that must move
 * together, so grouping them says which they are.
 *
 * THE NUMBER: 1.21, minted because 1.20 has SHIPPED -- `git tag
 * --sort=-creatordate | head -1` is v0.1.53 and its own `SessionExport.kt`
 * reads `SCHEMA_VERSION = "1.20"`, both read this round. This is written as the
 * SECOND 1.21 entry: #300 mints 1.21 for `skippedSets` on its own branch and
 * lands before this one, so whichever lands first owns the mint. What is
 * asserted below cannot go stale either way -- that the keys are published, that
 * the number is ACCEPTED, and that the versions before it still are -- and the
 * exporter's own constant is read rather than a literal compared against it.
 * `SchemaRepsSourceContractTest`'s corrected comment carries why: an
 * `assertEquals("<the tip>", SCHEMA_VERSION)` asserts the filing version by
 * reading the version the exporter writes, and goes false at the next mint.
 *
 * DIFFERENTIALS, not green pins. This file was pushed before either key existed
 * in the published schema, and every method below failed. The DSP half's
 * differentials are `:core:dsp`'s `ArtefactPeakWithholdingTest` and the export
 * half's are `:core:data`'s `SessionExportArtefactSampleTest`.
 */
class SchemaArtefactSampleContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun exportSchema() = schema("session-export.schema.json")

    private fun setDef() = exportSchema()["\$defs"]!!.jsonObject["set"]!!.jsonObject

    private fun repDef() = exportSchema()["\$defs"]!!.jsonObject["repMetrics"]!!.jsonObject

    private fun versionEnum() = exportSchema()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun versionLog() = exportSchema()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the set count is published, an integer, and floored at zero`() {
        val count = assertNotNull(
            setDef()["properties"]!!.jsonObject["artefactSamples"],
            "the set declares no artefactSamples key",
        ).jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content, "artefactSamples type")
        assertEquals(0, count["minimum"]!!.jsonPrimitive.content.toInt(), "artefactSamples floor")
    }

    @Test
    fun `the per-rep count is published, an integer, and floored at zero`() {
        val count = assertNotNull(
            repDef()["properties"]!!.jsonObject["artefactSamples"],
            "a rep declares no artefactSamples key",
        ).jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content, "rep artefactSamples type")
        assertEquals(0, count["minimum"]!!.jsonPrimitive.content.toInt(), "rep artefactSamples floor")
    }

    /**
     * Both objects stay CLOSED, which is what makes the two methods above
     * load-bearing: adding the Kotlin field without the schema key would make
     * every export of an analysed set fail the ajv step four CI steps after this
     * module's tests pass.
     */
    @Test
    fun `the set and the rep still forbid keys they do not declare`() {
        assertEquals(
            false,
            setDef()["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the set object stopped being closed",
        )
        assertEquals(
            false,
            repDef()["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the rep object stopped being closed",
        )
    }

    /**
     * Neither key is required, which is the whole of how absence is expressed: a
     * set analysed before the count existed publishes NO key, and that is a
     * different fact from a 0.
     *
     * Asserted against `required` rather than inferred from the example, which
     * could carry the key on every row and still leave it optional.
     */
    @Test
    fun `absence is expressible on both, so neither key is required`() {
        val setRequired = setDef()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("artefactSamples" !in setRequired, "a set analysed before the count could not be published")
        val repRequired = repDef()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("artefactSamples" !in repRequired, "a rep analysed before the count could not be published")
        // The peak pair the rule narrows is UNCHANGED in shape: the per-rep
        // peakConVel_mps stays required and typed as it was, because the rule
        // withholds a rep from the SET's peak and never deletes the rep's own.
        assertTrue("peakConVel_mps" in repRequired, "a rep stopped publishing its own peak velocity")
        assertEquals(
            "number",
            repDef()["properties"]!!.jsonObject["peakConVel_mps"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "the per-rep peak velocity was retyped",
        )
    }

    /**
     * The published example carries both keys, including a set-level count on a
     * set whose reps carry one, so the ajv step in `ci.yml` actually validates
     * the blocks rather than passing a document that never exercises them.
     */
    @Test
    fun `the example publishes both counts, including a zero`() {
        val setCounts = exampleSets().mapNotNull { it["artefactSamples"]?.jsonPrimitive?.content?.toInt() }
        assertEquals(listOf(0, 3), setCounts.sorted(), "set-level artefactSamples in the published example")
        val repCounts = exampleSets()
            .flatMap { it["repMetrics"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["artefactSamples"]?.jsonPrimitive?.content?.toInt() }
        assertEquals(listOf(0, 0, 0, 0, 1), repCounts.sorted(), "per-rep artefactSamples in the published example")
    }

    /**
     * The version log explains the keys it publishes, and the bound is in it.
     *
     * Narrow, and said so: this cannot check the entry is RIGHT, only that a
     * consumer reading the one place version changes are described is told the
     * keys exist and told what "4 g" means. A key published with no entry is the
     * failure `the 1_13 version log names the rep marks` was written for.
     */
    @Test
    fun `the version log names the keys, the bound and what is not repaired`() {
        val log = versionLog()
        assertTrue("artefactSamples" in log, "the version log never mentions the keys it publishes")
        assertTrue("4 g" in log, "the version log does not say what the bound is")
        assertTrue("REP COUNT" in log, "the version log does not say why no sample is repaired")
    }

    /**
     * The number the keys ride under is accepted, and the versions before it
     * still are, so this is additive rather than a break.
     *
     * `SCHEMA_VERSION` is read rather than compared against a literal, for the
     * reason this class's KDoc gives.
     */
    @Test
    fun `1_21 is accepted and every earlier version still is`() {
        assertTrue("1.21" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.21 is not in the accepted set")
        assertTrue("1.21" in versionEnum(), "1.21 is not in the published enum")
        assertTrue(SessionExport.SCHEMA_VERSION in versionEnum(), "the exporter writes a version ajv rejects")
        assertTrue(
            SessionExport.SCHEMA_VERSION in SessionExport.SUPPORTED_SCHEMA_VERSIONS,
            "the exporter writes a version it would reject on read",
        )
        listOf("1.18", "1.19", "1.20").forEach {
            assertTrue(it in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "$it left the accepted set")
            assertTrue(it in versionEnum(), "$it left the published enum, so this is a break beyond the keys")
        }
    }

    /**
     * `VALID_REFUSED_DETECTION_REASONS` gains NO word, and that is a design
     * decision worth a pin rather than a sentence.
     *
     * The constant's own KDoc used to say the sample-level half of #125 "would
     * need a second word if it ever is" handled. It is handled now and it needed
     * none: no detection is refused by this rule. A later change that folded
     * this rule into #125's vocabulary would red here, and it should -- a
     * refused detection is removed from every figure, and an artefact-carrying
     * rep is removed from two.
     */
    @Test
    fun `the refusal vocabulary did not gain a word for this rule`() {
        assertEquals(setOf("unpairedRangeOutlier"), SessionExport.VALID_REFUSED_DETECTION_REASONS)
        val published = setDef()["properties"]!!.jsonObject["refusedDetectionReason"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(SessionExport.VALID_REFUSED_DETECTION_REASONS, published, "the published refusal words moved")
    }

    /**
     * The Kotlin twins exist under the serial names the schema declares. A
     * rename on one side alone would leave the other green for a commit.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `both Kotlin twins publish under the declared serial names`() {
        assertTrue(
            "artefactSamples" in SetExport.serializer().descriptor.elementNames.toList(),
            "SetExport does not publish artefactSamples",
        )
        assertTrue(
            "artefactSamples" in RepMetricsExport.serializer().descriptor.elementNames.toList(),
            "RepMetricsExport does not publish artefactSamples",
        )
    }
}
