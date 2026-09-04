package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema 1.19's TENTH entry: a set may say how many detections finished before
 * its work began. Issue #245.
 *
 * In its own file rather than in `SchemaContractTest`, on the grounds
 * `SchemaNoRepsReasonContractTest` and `SchemaAnalysedFallbackContractTest`
 * state: that class sits on detekt's `LargeClass` limit, and a version entry
 * has several halves that must move together, so grouping them says which
 * they are.
 *
 * THE ENTRY MINTS NO NUMBER. It rides under 1.19, which was already open on
 * `main` -- v0.1.50 declares 1.18, so 1.19 is unreleased and takes further
 * entries. That is asserted below off the published enum rather than argued.
 *
 * GREEN PINS on keys the commit before this one introduced, not differentials:
 * neither the schema key nor the eighth `noRepsReason` word exists before it,
 * so nothing here could have been shown failing first. What WAS shown failing
 * is the figures the rule moves, in `:core:dsp`'s `PrepDetectionFieldTest`.
 */
class SchemaWorkStartBoundContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject

    private fun exportVersionEnum() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!
        .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the count is published and floored at zero`() {
        val count = assertNotNull(
            setProperties()["detectionsBeforeWorkStart"],
            "the set declares no detectionsBeforeWorkStart key",
        ).jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content, "detectionsBeforeWorkStart type")
        assertEquals(0, count["minimum"]!!.jsonPrimitive.content.toInt(), "detectionsBeforeWorkStart floor")
    }

    /**
     * The key is optional, which is the whole of how absence is expressed: a
     * set with no work-start instant publishes NO key, and that is a different
     * fact from a 0.
     *
     * Asserted against `required` rather than inferred from the example, which
     * could carry the key on every set and still leave it optional.
     */
    @Test
    fun `absence is expressible, so the key is not required`() {
        val required = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("detectionsBeforeWorkStart" !in required, "a set with no prep instant could not be published")
        assertTrue("refusedDetections" !in required, "the neighbouring count is optional for the same reason")
    }

    /**
     * The set schema forbids keys it does not declare, so adding the Kotlin
     * field without the schema key would have made every export of a prepped
     * set fail ajv four CI steps after this module's tests pass.
     */
    @Test
    fun `the set still forbids keys it does not declare`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!.jsonObject
        assertEquals(
            false,
            set["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the set object stopped being closed, so the key above proves nothing",
        )
    }

    /**
     * The published example carries the key on two sets, one of them 0.
     *
     * The zero is the case worth publishing: it is the one a reader is most
     * likely to misread as "no bound ran", and the schema's own description is
     * what says otherwise.
     *
     * The example's third prepped set carries no count on purpose: it is a row
     * recorded before database v15.
     */
    @Test
    fun `the example publishes the key, including a zero`() {
        val values = exampleSets().mapNotNull { it["detectionsBeforeWorkStart"]?.jsonPrimitive?.content?.toInt() }
        assertEquals(listOf(0, 2), values.sorted(), "detectionsBeforeWorkStart values in the published example")
    }

    /**
     * The eighth `noRepsReason` word, and the number the entry is filed under.
     *
     * The vocabulary equality itself is `SchemaNoRepsReasonContractTest`'s;
     * this asserts only that the new word reached both sides, which is the
     * half a rename of just one of them would leave green there for one
     * commit.
     */
    @Test
    fun `the eighth no-reps word is published, under a number that has not shipped`() {
        val declared = setProperties()["summary"]!!.jsonObject["properties"]!!.jsonObject["noRepsReason"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("beforeWorkStart" in declared, "the published schema does not accept the word")
        assertTrue("beforeWorkStart" in SessionExport.VALID_NO_REPS_REASONS, "the exporter does not declare the word")
        assertEquals("1.19", SessionExport.SCHEMA_VERSION, "the entry rides under the version the exporter writes")
        assertTrue("1.19" in exportVersionEnum(), "the published enum does not carry the number")
        assertTrue("1.18" in exportVersionEnum(), "1.18 stopped being readable, so this is not additive")
    }

    /**
     * `VALID_REFUSED_DETECTION_REASONS` is UNCHANGED, which is the design
     * decision this issue argued and is worth a pin rather than a sentence.
     *
     * A later change that folded the head bound into #125's reason word would
     * red here, and it should: the two counts have different absences and one
     * key cannot carry both.
     */
    @Test
    fun `the refusal vocabulary did not gain a word for this rule`() {
        assertEquals(setOf("unpairedRangeOutlier"), SessionExport.VALID_REFUSED_DETECTION_REASONS)
        val reason = setProperties()["refusedDetectionReason"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(SessionExport.VALID_REFUSED_DETECTION_REASONS, reason, "the published refusal words moved")
    }
}
