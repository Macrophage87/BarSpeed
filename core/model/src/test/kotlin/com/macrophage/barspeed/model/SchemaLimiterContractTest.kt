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
 * What the published session-export schema says about why a set ended (#189).
 *
 * THESE FAILED WHEN THEY WERE WRITTEN, at b5fdb50 (CI run 33313909287,
 * conclusion failure): the published document declared no such key, and
 * `$defs.set` sets `additionalProperties: false` -- so the moment the exporter
 * wrote one, every export carrying it was INVALID against the contract its own
 * reader was pointed at. That is why the schema half was red-gated beside the
 * exporter half rather than treated as documentation.
 *
 * A file of its own rather than more assertions in [SchemaContractTest], for
 * [SchemaAddedSetContractTest]'s reason: that class is at detekt's `LargeClass`
 * threshold and adding to it reds `:core:model:detekt`, which is CI's first
 * step.
 */
class SchemaLimiterContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties(): JsonObject {
        val defs = schema("session-export.schema.json")["\$defs"]!!.jsonObject
        return defs["set"]!!.jsonObject["properties"]!!.jsonObject
    }

    private fun versionLog(): String {
        val properties = schema("session-export.schema.json")["properties"]!!.jsonObject
        return properties["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content
    }

    /**
     * The published export declares the reason, and declares it CLOSED to
     * exactly the answers the app can write.
     *
     * An equality and not a subset, the way [SchemaContractTest] pins every
     * other vocabulary: a schema listing an answer the app cannot produce
     * promises a reader a grouping that will always be empty, and a schema
     * missing one the app does produce rejects a valid export.
     */
    @Test
    fun `the published export declares the reason, closed to the answers the app can write`() {
        val limiter = assertNotNull(
            setProperties()["limiter"],
            "the published export schema does not declare why a set ended",
        ).jsonObject
        assertEquals(
            SetLimiter.entries.map { it.stored }.toSet(),
            limiter["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the published reason vocabulary drifted from SetLimiter",
        )
    }

    /**
     * The reason's description names the two things a reader cannot recover
     * from the value alone.
     *
     * That an absent key is a question skipped or never asked, and NOT a set
     * that ended for an unknown reason -- without it a reader counts silence
     * as a category. And that the outside-reason answer marks a set analysis
     * should discard rather than read as capacity, which is the half that
     * keeps "every unfinished set is a fail" honest.
     */
    @Test
    fun `the published reason says what its absence means and what the outside answer is for`() {
        val description = setProperties()["limiter"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "OMITTED" in description,
            "the published reason never says that an unanswered set carries no key: $description",
        )
        assertTrue(
            "discard" in description,
            "the published reason never says the outside answer marks a set to discard: $description",
        )
        assertTrue(
            "pain" in description.lowercase(),
            "the published reason never singles out the answer a coach most needs to see: $description",
        )
    }

    /**
     * The note is declared beside the reason, as its own key, with the cap the
     * app enforces.
     *
     * `maxLength` is not decoration: a reader that allocates on it, or a
     * consumer that round-trips through a store with its own limit, is being
     * promised something, and the promise has to be the same number the app
     * truncates at.
     */
    @Test
    fun `the published export declares the free-text note beside the reason, capped`() {
        val note = assertNotNull(
            setProperties()["limiterNote"],
            "the published export schema does not declare the free-text note",
        ).jsonObject
        assertEquals("string", note["type"]!!.jsonPrimitive.content, "the note is not published as a string")
        assertEquals(
            SetLimiter.NOTE_MAX_CHARS,
            note["maxLength"]!!.jsonPrimitive.content.toInt(),
            "the published note cap drifted from SetLimiter.NOTE_MAX_CHARS",
        )
        val description = note["description"]!!.jsonPrimitive.content
        assertTrue(
            "other" in description,
            "the published note never says which answer it belongs to: $description",
        )
    }

    /**
     * The version log names the reason under the OPEN 1.14 entry.
     *
     * 1.14 is unreleased -- v0.1.44 shipped 1.13, read at the tag -- so this
     * extends that entry rather than minting 1.15, exactly as the seven
     * changes under 1.13 did while that number was open. Minting a boundary no
     * reader has shipped against publishes a version that never existed.
     */
    @Test
    fun `the 1_14 version log names the reason as an additive change under the open number`() {
        val log = versionLog()
        assertTrue(
            "`limiter`" in log,
            "the version log never mentions the reason, so 1.14 publishes an unexplained key",
        )
        assertTrue(
            "THIRD change, additive" in log,
            "the version log does not say the reason is additive: a 1.13 reader is told to re-check it",
        )
    }

    /**
     * The published example carries a failed set with a reason on it.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage -- an example carrying none of
     * the new key passes a schema that declares it and a schema that does not.
     */
    @Test
    fun `the published export example carries a failed set with a reason`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val withReason = sets.filter { "limiter" in it }
        assertTrue(withReason.isNotEmpty(), "the published example shows no set carrying a reason")
        for (set in withReason) {
            assertNotNull(
                SetLimiter.ofStored(set.getValue("limiter").jsonPrimitive.content),
                "the published example carries a reason the app cannot write",
            )
        }
    }
}
