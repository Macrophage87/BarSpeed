package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `summary.noRepsReason` gains `carryNotSegmented` under export 1.26: a carry
 * prescribed in reps whose stream was recorded and deliberately not segmented
 * (lane L5's follow-up to #63).
 *
 * Every place the vocabulary is stated moves together: the Kotlin constant
 * the schema is pinned against ([SessionExport.VALID_NO_REPS_REASONS], which
 * `SchemaNoRepsReasonContractTest` holds equal to the published enum and
 * `BlankAnalysisReasonTest` in `:core:dsp` holds equal to `NoRepsReason`), the
 * key's own description, the version log, and the example that ajv validates
 * in CI. `WithheldCarryReasonTest` in `:core:dsp` is the differential for the
 * analysis that carries the word; `SessionExportNoRepsReasonTest` in
 * `:core:data` is the export's.
 *
 * DIFFERENTIAL. Every method is red before the fix: the word is in none of
 * those places.
 */
class SchemaCarryNotSegmentedContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun reasonDescription() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject["summary"]!!
        .jsonObject["properties"]!!.jsonObject["noRepsReason"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun versionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the exporter declares the word`() {
        assertTrue(WORD in SessionExport.VALID_NO_REPS_REASONS, "the exporter does not declare $WORD")
    }

    /**
     * The description must say what the word claims and, as for
     * `mountNotDeclared`, that the segmenter never ran -- and it must stop
     * calling `mountNotDeclared` the ONLY value set before the segmenter runs.
     */
    @Test
    fun `the key's description says what the word claims and where it is absent`() {
        val text = reasonDescription()
        val entry = text.substringAfter("$WORD:", missingDelimiterValue = "")
        assertTrue(entry.isNotEmpty(), "the key's description never explains $WORD: $text")
        for (fact in listOf("carry", "NEVER RUN", "MIN_ANALYSABLE_FRAMES")) {
            assertTrue(fact in entry, "the $WORD paragraph does not state: $fact")
        }
        assertTrue(
            "It is the only value set before the segmenter runs at all" !in text,
            "the description still says mountNotDeclared is the only value set before the segmenter runs",
        )
    }

    @Test
    fun `the 1_26 log files the word once`() {
        val log = versionLog()
        assertEquals(1, log.split(MARKER).size - 1, "the 1.26 carry entry is not filed exactly once")
        val entry = log.substringAfter(MARKER).substringBefore(" 1.26 (")
        for (fact in listOf("`$WORD`", "CLOSED enum", "NOT RETROACTIVE", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the 1.26 carry entry does not state: $fact")
        }
    }

    /** ajv validates the example in CI, so a word no example uses is a word no validator has run. */
    @Test
    fun `the example carries a carry counted in reps that says why it has no reps`() {
        val carries = exampleSets().filter { set ->
            set["summary"]?.jsonObject?.get("noRepsReason")?.jsonPrimitive?.content == WORD
        }
        assertEquals(1, carries.size, "the example does not carry exactly one set publishing $WORD")
        val set = carries.single()
        assertEquals(
            "carry",
            set["geometry"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            "the example publishes $WORD on a set that is not a carry",
        )
        assertTrue("plannedReps" in set, "the example's carry is not prescribed in reps")
        assertTrue("duration_s" !in set, "the example's carry is a timed one, which never reaches the word")
        assertTrue("velocityLossRegime" !in set, "a carry has no concentric for a regime to be about")
    }

    private companion object {
        const val WORD = "carryNotSegmented"
        const val MARKER = "1.26 (#63, a carry counted in reps)"
    }
}
