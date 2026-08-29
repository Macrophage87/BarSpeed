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
 * What the published session-export schema says about a set the LIFTER
 * appended to the exercise mid-session (#177).
 *
 * A file of its own rather than three more assertions in [SchemaContractTest],
 * and not by preference: that class is already at detekt's `LargeClass`
 * threshold and adding these to it reds `:core:model:detekt`, which is CI's
 * first step. [SchemaCueTrackContractTest] is the same split for the same
 * reason, one change earlier.
 *
 * The instrument is that file's: read the published documents as JSON and
 * assert what a consumer pointed at them would be told. The 1.13 count clause
 * stays where it lives, in `the 1_13 version log names the session rating and
 * still flags which changes are not additive`, because a count asserted in two
 * files is a count that can disagree with itself.
 */
class SchemaAddedSetContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    /**
     * The published export declares a set's `added` flag, described (#177).
     *
     * The declaration is not optional cosmetics: `$defs.set` sets
     * `additionalProperties: false`, so a key the exporter writes and the
     * schema does not declare makes every export carrying it INVALID against
     * the contract its own reader was pointed at -- the same reasoning the
     * `repMarks` declaration pin carries, one key over.
     *
     * The description is asserted to name the two things a reader cannot
     * recover from the boolean alone: that an appended set has no prescription
     * at all, so its missing `plannedReps` is a statement rather than a gap;
     * and that on a session recorded before database v12 the absent flag means
     * "prescribed, or recorded before the app could tell". Without the second,
     * a reader counts every pre-v12 improvised set as prescribed and believes
     * the adherence figure it derives.
     */
    @Test
    fun `the published export declares the appended flag, described`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        val added = assertNotNull(set["added"], "the published export schema does not declare a set's added flag")
            .jsonObject
        assertEquals("boolean", added["type"]!!.jsonPrimitive.content, "added is not published as a boolean")
        val description = added["description"]!!.jsonPrimitive.content
        assertTrue(
            "plannedReps" in description,
            "the published added flag never says an appended set publishes no prescription: $description",
        )
        assertTrue(
            "recorded before the app could tell" in description,
            "the published added flag never says what its absence means on a pre-v12 session: $description",
        )
    }

    /**
     * The 1.13 version-log entry names the appended flag as its seventh
     * change, and still says which of the seven are not additive.
     *
     * The same instrument as the rep-mark, sensor and session-rating entries,
     * further changes on, and the count clause is the half that keeps going
     * stale: it has been corrected from "one of the four" to "three of the
     * six" to "three of the seven", and each correction was made because the
     * previous sentence had become false rather than because the wording was
     * unclear. `added` is additive, so it moves the denominator and not the
     * numerator -- which is exactly the kind of edit that gets skipped.
     */
    @Test
    fun `the 1_13 version log names the appended flag as its seventh change`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "`added`" in description,
            "the version log never mentions added, so 1.13 publishes a key it does not explain",
        )
        assertTrue(
            "SEVENTH change, additive" in description,
            "the version log no longer says the appended flag is additive: a 1.12 reader is told to re-check it",
        )
        assertTrue(
            "REMOVING a set is not part of this change" in description,
            "the version log does not say removal is out of scope, so an absent set has no stated meaning",
        )
    }

    /**
     * The published example carries an appended set, and that set carries no
     * prescription.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage -- an example carrying none of
     * the new key passes a schema that declares it and a schema that does not.
     *
     * The second half is the one worth having. `additionalProperties: false`
     * means ajv would reject an appended set carrying `plannedReps` only if the
     * schema forbade it, which it does not and should not: a *prescribed* set
     * carries one. So nothing but this assertion pins the example to the shape
     * the flag's own description promises, and an example that showed an
     * appended set with a prescription would document the exact confusion the
     * flag exists to prevent.
     */
    @Test
    fun `the published export example carries an appended set carrying no prescription`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        val appended = sets.filter { it["added"]?.jsonPrimitive?.content == "true" }
        assertEquals(1, appended.size, "the published example carries no appended set, so ajv never validates one")
        for (key in listOf("plannedReps", "plannedLoad_kg", "plannedDuration_s", "tempoPrescribed")) {
            assertTrue(
                key !in appended.single(),
                "the published example's appended set claims a $key, which nothing prescribed",
            )
        }
        assertTrue(
            sets.any { it["added"] == null },
            "every set in the published example is appended, so the flag demonstrates no contrast",
        )
    }
}
