package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The number `geometry.source.bodyweight` is published under, and what the
 * published schema does with a document written by the previous release.
 * Issue #220.
 *
 * Its own file rather than a method on [SchemaContractTest], whose header
 * records it sitting on detekt's `LargeClass` limit -- the reason
 * [RestWindowExportContractTest] and [RollExcursionExportContractTest] are
 * separate too.
 *
 * `geometry.source` is `additionalProperties: false` with every key required,
 * and this version adds `bodyweight` to it. Four runs of the command `ci.yml`
 * runs, `npx -p ajv-cli@5 -p ajv-formats@2 ajv validate -c ajv-formats
 * --spec=draft2020`, measured while this test was written:
 *
 *  - this branch's schema against v0.1.50's published example, a 1.18
 *    document: invalid, `must have required property 'bodyweight'` at
 *    `/exercises/0/sets/0/geometry/source`.
 *  - v0.1.50's schema against this branch's example carrying the new key and
 *    still declaring 1.18: invalid, `must NOT have additional properties`,
 *    `additionalProperty: 'bodyweight'`, at the same path.
 *  - v0.1.50's schema against v0.1.48's published example, a 1.16 document:
 *    invalid, `must have required property 'sensorOnStack'` at the same
 *    path.
 *  - v0.1.49's schema against v0.1.50's published example: invalid at
 *    `/schemaVersion` on the enum. ajv stops at the first error, so an OLDER
 *    schema reports the version number before it reaches the key -- which is
 *    what v0.1.50's schema reports against this version's example now that
 *    the example declares 1.19, and why the second run above was taken with
 *    the example still at 1.18.
 *
 * The third run is the one that says this break is not new: 1.17 added
 * `sensorOnStack` to the same closed object and shipped in v0.1.49. The
 * published schema validates the CURRENT version's shape, and its
 * `schemaVersion` enum listing every number back to 1.0 is not a claim that
 * a document carrying one of them validates.
 *
 * `bodyweight` is NOT made optional to close the gap. [SchemaContractTest]'s
 * `every geometry key is required, so a dropped false cannot read as
 * unstated` pins the whole source object required, and the exporter writes
 * with `encodeDefaults = false`: an optional key with a Kotlin default
 * vanishes from the wire and reads as not stated.
 *
 * These pins cannot run ajv -- there is no JSON Schema validator on this
 * module's test classpath. They pin the two schema facts the three results
 * follow from, and the sentences the published log states them in.
 */
class SchemaBodyweightSourceContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val source
        get() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["geometrySource"]!!.jsonObject

    private val versionLog
        get() = schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content

    private fun versionEnum() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["enum"]!!.jsonArray
        .map { it.jsonPrimitive.content }.toSet()

    /**
     * The six keys v0.1.50 published under `geometrySource.required`, read by
     * `git show v0.1.50:docs/schemas/session-export.schema.json` rather than
     * remembered.
     */
    private val releasedIn118 =
        setOf("startsWith", "concentric", "plane", "kind", "travelRatio", "sensorOnStack")

    @Test
    fun `the geometry source is closed and requires exactly one key more than v0_1_50 published`() {
        assertFalse(
            source["additionalProperties"]!!.jsonPrimitive.boolean,
            "geometry.source stopped being closed, so the 1.18-rejects-1.19 direction is no longer true",
        )
        val required = source["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("bodyweight"),
            required - releasedIn118,
            "the keys this version requires and v0.1.50 did not are no longer just bodyweight",
        )
        assertEquals(
            emptySet(),
            releasedIn118 - required,
            "a key v0.1.50 required stopped being required, which the version log does not describe",
        )
    }

    /**
     * The mint, and the four halves of it that must move together: the
     * constant the exporter writes, the set [SessionExport.validate] accepts,
     * the published enum, and the example `ci.yml` hands to ajv. The example
     * half is pinned by `SchemaContractTest`'s `the published example
     * declares the version the exporter writes`.
     */
    @Test
    fun `the exporter writes 1_19 and 1_18 is still readable`() {
        assertEquals(
            "1.19",
            SessionExport.SCHEMA_VERSION,
            "the exporter no longer writes 1.19, which is the number these four changes were minted under",
        )
        assertTrue("1.19" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.19" in versionEnum(), "the published enum does not accept the version written")
        assertTrue("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.18 left the accepted set")
        assertTrue("1.18" in versionEnum(), "1.18 left the published enum, so this is a break beyond the key")
    }

    /** The mint is filed as its own number and names the release that forced it. */
    @Test
    fun `the version log mints 1_19 and names the tag that shipped 1_18`() {
        assertTrue("1.19:" in versionLog, "the version log has no 1.19 entry at all")
        assertTrue(
            "1.18 SHIPPED in v0.1.50" in versionLog,
            "the log does not say why these changes could not ride under 1.18",
        )
    }

    @Test
    fun `the version log states the rejection in both directions`() {
        assertTrue(
            "the 1.19 schema rejects a 1.18 document" in versionLog,
            "the log does not say what this schema does with the released version's document",
        )
        assertTrue(
            "the 1.18 schema rejects a 1.19 document" in versionLog,
            "the log does not say what the released schema does with this version's document",
        )
    }

    private fun setProperty(name: String) = schema("session-export.schema.json")["\$defs"]!!
        .jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject[name]!!.jsonObject

    /**
     * RED. Each key minted under 1.19 names 1.19 in the description PUBLISHED
     * beside it and names 1.18 nowhere in it, so a mint cannot move the
     * constant and leave the prose on the released number.
     *
     * The literal 1.19 rather than [SessionExport.SCHEMA_VERSION], which is
     * what round 1's finding 12 asked for. Measured at this SHA: the
     * SCHEMA_VERSION form applied to `failedByLifter` -- minted under 1.18 and
     * correctly saying so -- fails, its description reading `(1.18, #216,
     * #169)` against a constant of 1.19. A key names the number it was minted
     * under and keeps naming it.
     *
     * THE 1.18 HALF IS HERE BECAUSE A MUTATION SURVIVED WITHOUT IT. Reverting
     * `rest_s`'s `From 1.19 the instant it is counted FROM` to 1.18 and
     * leaving its second mention alone left this test GREEN: `1.19 in
     * description` is satisfied by any one mention, and the description
     * carries two. Measured, then fixed forward rather than reworded.
     */
    @Test
    fun `each key minted under 1_19 names 1_19 in its published description`() {
        val published = mapOf(
            "bodyWeight_kg" to setProperty("bodyWeight_kg"),
            "rest_s" to setProperty("rest_s"),
            "geometrySource" to source,
        ).mapValues { (_, node) -> node["description"]!!.jsonPrimitive.content }
        assertEquals(
            emptySet(),
            published.filterValues { "1.19" !in it }.keys,
            "published descriptions minted under 1.19 that do not name it",
        )
        assertEquals(
            emptySet(),
            published.filterValues { "1.18" in it }.keys,
            "published descriptions minted under 1.19 that still file the key under 1.18",
        )
    }

    /**
     * DELETED, not reworded. The sentence named a reader "validating against
     * 1.17 or earlier" as the one that rejects the document; the rejecting
     * reader is the released 1.18, which is the schema v0.1.50 published.
     */
    @Test
    fun `the version log no longer blames a 1_17-or-earlier reader`() {
        assertFalse(
            "validating against 1.17 or earlier" in versionLog,
            "the log still names the wrong rejecting reader for geometry.source.bodyweight",
        )
    }
}
