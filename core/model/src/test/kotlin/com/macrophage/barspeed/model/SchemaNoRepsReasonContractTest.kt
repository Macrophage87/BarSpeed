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
 * Schema 1.18: a set's `summary` may say why it resolved no reps. Issue #138.
 *
 * In its own file rather than in `SchemaContractTest`, which its own header
 * records as sitting on detekt's `LargeClass` limit, and for the reason
 * `SchemaAnalysedFallbackContractTest` gives: a version mint has several
 * halves that must move together, and grouping them says which they are.
 *
 * THE KEY DOES NOT MINT A NUMBER. It rides as a FURTHER entry under 1.18,
 * which issue #216 had already minted on `main` for `abandonedInPrep` and
 * then extended for `failedByLifter`, and which no tag carries -- v0.1.49
 * shipped 1.17. A branch written against 1.17 minted its own 1.18 for this
 * key; that was a collision with #216's, and the paragraph claiming this was
 * "the first mint by release rather than by accident" is DELETED rather than
 * reworded. One number now covers all three keys. What is asserted below is
 * what that leaves true: the constant reads 1.18, 1.18 is accepted and
 * published, and 1.17 is still readable.
 */
class SchemaNoRepsReasonContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun summaryProperties() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
        .jsonObject["properties"]!!.jsonObject["summary"]!!
        .jsonObject["properties"]!!.jsonObject

    private fun exportVersionEnum() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!
        .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the key rides under the unreleased 1_18 and 1_17 is still accepted`() {
        // Not a mint. #216 minted 1.18 and no tag carries it, so a further
        // entry under it is free -- the rule every "1.17 is UNRELEASED" entry
        // in SessionExport's log applied while 1.17 was in that state.
        assertEquals("1.18", SessionExport.SCHEMA_VERSION, "the number this key rides under moved")
        assertTrue("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.18" in exportVersionEnum(), "the published enum does not accept the version written")
        assertTrue("1.17" in exportVersionEnum(), "1.17 stopped being readable, so this is not additive")
    }

    @Test
    fun `the published no-reps vocabulary is exactly the one the exporter declares`() {
        // Two hops, the arrangement velocityLossBasis uses. This pins the
        // published schema against the Kotlin constant; `BlankAnalysisReasonTest`
        // in :core:dsp pins the same constant against the NoRepsReason enum that
        // owns the names, from the side that can see both. Neither hop alone
        // catches a rename: one would leave the schema declaring a value
        // nothing emits, the other a value the schema rejects.
        val declared =
            assertNotNull(summaryProperties()["noRepsReason"], "the summary declares no noRepsReason key")
                .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(SessionExport.VALID_NO_REPS_REASONS, declared, "noRepsReason values drifted")
    }

    @Test
    fun `the summary still forbids keys it does not declare`() {
        // The reason the key had to be added to the schema at all, and the
        // reason a future one will too. additionalProperties is false here, so
        // a summary carrying an undeclared key is INVALID rather than merely
        // undocumented -- adding the Kotlin field without the schema key would
        // have made every blank set's export fail ajv four CI steps later.
        val summary = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject["summary"]!!.jsonObject
        assertEquals(
            false,
            summary["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the summary began allowing undeclared keys",
        )
    }

    @Test
    fun `the published example carries both a blank summary and one that says why`() {
        // ajv over this example is the schema half's only automated coverage,
        // and an example carrying none of the new key passes a schema that
        // declares it and one that does not.
        //
        // BOTH cases are required, and the contrast is the point of the key: a
        // set with no sensor at all publishes `summary: {}` and NO reason,
        // because nothing analysed it; a set whose segmentation emptied
        // publishes a reason. Before 1.18 those two were the same document.
        val sets = exampleSets()
        val reasons = sets.mapNotNull { it["summary"]?.jsonObject?.get("noRepsReason")?.jsonPrimitive?.content }
        assertEquals(1, reasons.size, "the example does not carry exactly one set stating a reason")
        assertTrue(
            reasons.single() in SessionExport.VALID_NO_REPS_REASONS,
            "the example states a reason the schema rejects: ${reasons.single()}",
        )
        assertTrue(
            sets.any { it["summary"]?.jsonObject?.isEmpty() == true },
            "the example no longer carries a set whose summary is empty with no reason",
        )
    }

    @Test
    fun `the 1_18 version log names the key it publishes`() {
        // A version whose log does not name its own change tells a consumer
        // deciding whether to re-check a reader nothing. The log is the
        // published copy of SessionExport's; both must carry the entry.
        val log = schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        val entry = log.substringAfterLast("1.18:")
        assertTrue("noRepsReason" in entry, "the 1.18 log entry never names noRepsReason")
        assertTrue(
            "under-resolved" in entry.lowercase() || "1 of 10" in entry,
            "the log does not say the key is silent on an under-resolved set",
        )
    }
}
