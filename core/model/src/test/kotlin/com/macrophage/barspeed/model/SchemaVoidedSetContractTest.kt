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
 * What the published session-export schema says about a set the lifter marked
 * as one they did not perform (#60).
 *
 * DIFFERENTIALS. Every test here fails at the commit that introduces it:
 * `docs/schemas/session-export.schema.json` declares neither key, its version
 * log does not mention them, and the published example carries no voided set.
 *
 * A file of its own rather than more assertions in [SchemaContractTest],
 * following [SchemaAddedSetContractTest] and not by preference: that class sits
 * on detekt's `LargeClass` threshold and adding to it reds `:core:model:detekt`,
 * which is CI's first step.
 *
 * THE DECLARATION IS NOT COSMETIC. `$defs.set` sets
 * `additionalProperties: false`, so a key the exporter writes and the schema
 * does not declare makes every export carrying it INVALID against the contract
 * its own reader was pointed at.
 *
 * WHICH VERSION THIS IS FILED UNDER. 1.17 shipped in v0.1.49 and cannot take
 * further entries. 1.18 was minted by the export-truth lane (#216), is LANDED
 * on `main` and is unreleased, which is the state that takes further entries,
 * so this is a FURTHER CHANGE UNDER 1.18 rather than a number of its own.
 * Nothing here touches `SessionExport.SCHEMA_VERSION`,
 * `SUPPORTED_SCHEMA_VERSIONS` or the published `schemaVersion` enum -- the
 * mint already moved all three.
 */
class SchemaVoidedSetContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties() = schema("session-export.schema.json")["\$defs"]!!
        .jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject

    /**
     * The published export declares the mark, described.
     *
     * The description is asserted to carry the three things a reader cannot
     * recover from the boolean: that the lifter is saying they did not perform
     * the set, that the set's own figures are still published and must not be
     * read as work that happened, and what an ABSENT key means -- not marked,
     * or recorded before the column existed. Without the last, a reader counts
     * every set in every pre-v16 session as performed and believes it.
     */
    @Test
    fun `the published export declares the void mark, described`() {
        val voided = assertNotNull(
            setProperties()["voided"],
            "the published export schema does not declare a set's void mark",
        ).jsonObject
        assertEquals("boolean", voided["type"]!!.jsonPrimitive.content, "voided is not published as a boolean")
        val description = voided["description"]!!.jsonPrimitive.content
        assertTrue(
            "did not perform" in description,
            "the published mark never says what the lifter is asserting: $description",
        )
        assertTrue(
            "not be read as" in description,
            "the published mark never warns that the set's own figures are still there: $description",
        )
        assertTrue(
            "database v16" in description,
            "the published mark never says what its absence means on an older session: $description",
        )
    }

    /**
     * The published export declares the reason beside the mark.
     *
     * Beside and never inside: a reader grouping unperformed sets needs a
     * boolean to filter on, and free text in that position would destroy
     * exactly that grouping -- the same separation `limiter` and `limiterNote`
     * already carry.
     *
     * THE CAP IS PINNED TO THE CONSTANT THE APP TRUNCATES AT, mirroring
     * `SchemaLimiterContractTest`. The code already shares
     * [SetLimiter.NOTE_MAX_CHARS] -- `VoidSetPolicy.reasonAsTyped` delegates
     * to the same normaliser -- so the exposure is the DOCUMENT: a published
     * `maxLength` a reader allocates on, or round-trips through a store with
     * its own limit, is a promise, and nothing else checks it is the number
     * the app enforces.
     */
    @Test
    fun `the published export declares the void reason, described as optional`() {
        val reason = assertNotNull(
            setProperties()["voidReason"],
            "the published export schema does not declare a voided set's reason",
        ).jsonObject
        assertEquals("string", reason["type"]!!.jsonPrimitive.content, "voidReason is not published as a string")
        assertEquals(
            SetLimiter.NOTE_MAX_CHARS,
            reason["maxLength"]!!.jsonPrimitive.content.toInt(),
            "the published void-reason cap drifted from SetLimiter.NOTE_MAX_CHARS",
        )
        val description = reason["description"]!!.jsonPrimitive.content
        assertTrue(
            "only on a voided set" in description,
            "the published reason never says it appears only beside the mark: $description",
        )
    }

    /**
     * The version log names the mark, as a further change under 1.18.
     *
     * The log is what a reader consults to find out whether a key they do not
     * recognise is new or corrupt. It has to say the number this rides under,
     * that the change is additive, and -- the half that is easiest to omit --
     * that it does NOT apply retroactively: a set recorded before database v16
     * publishes no mark whatever its `schemaVersion` says.
     */
    @Test
    fun `the version log names the void mark as a further change under 1_18`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "`voided`" in description,
            "the version log never mentions voided, so the export publishes a key it does not explain",
        )
        assertTrue(
            "1.18 carries a THIRD change" in description,
            "the version log does not file the mark under 1.18, so a reader cannot tell when it appeared",
        )
        assertTrue(
            "database v16" in description,
            "the version log does not say the mark is not retroactive: $description",
        )
    }

    /**
     * The version log says a void does NOT re-derive the session's heart rate.
     *
     * `heartRate.avgBpm` and `maxBpm` are written once, by `endSession`, over
     * every set row of the session, and voiding a set does not touch them.
     * A reader who drops voided sets and recomputes the pair will not get the
     * published figures back. Without this sentence that difference reads as a
     * corrupt document rather than as the contract, and the substrings below
     * are the ones that carry the meaning: `heartRate`, `avgBpm`, `maxBpm`,
     * `frozen` and `re-derived` were ALL already somewhere in this 49 kB log
     * before it was written, so asserting on any of them alone would have
     * passed against a log that never mentions voiding at all.
     */
    @Test
    fun `the version log says a void does not re-derive the session heart rate`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "NOT re-derived by a void" in description,
            "the version log does not say a void leaves the session heart rate alone: $description",
        )
        assertTrue(
            "recomputing them over the sets this mark tells a reader to count -- " +
                "that is, with the voided ones dropped -- will not reproduce them" in description,
            "the version log does not warn that the published heart rate cannot be rebuilt from the sets",
        )
    }

    /**
     * The published example carries a voided set, and it still carries its own
     * figures.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage -- an example carrying none of
     * the new keys passes a schema that declares them and a schema that does
     * not.
     *
     * The second half is the one worth having: a voided set that had been
     * stripped of its figures would document the opposite of what the mark
     * means. Nothing is withheld from a voided set; the mark is what tells a
     * reader not to count the figures as work.
     */
    @Test
    fun `the published export example carries a voided set that keeps its figures`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val voided = sets.filter { it["voided"]?.jsonPrimitive?.content == "true" }
        assertEquals(1, voided.size, "the published example carries no voided set, so ajv never validates one")
        val set = voided.single()
        assertTrue("voidReason" in set, "the example's voided set carries no reason, so the pair is never validated")
        assertTrue("reps" in set || "duration_s" in set, "the example's voided set was stripped of its own figures")
        assertTrue(
            sets.any { it["voided"] == null },
            "every set in the published example is voided, so the mark demonstrates no contrast",
        )
    }
}
