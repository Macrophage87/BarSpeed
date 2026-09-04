package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two keys schema 1.19 adds for issue #125, pinned against the published
 * document rather than against a copy of it.
 *
 * A separate file from `SchemaContractTest` rather than two more methods in
 * it: that class is already at detekt's `LargeClass` ceiling and adding to it
 * reds the build. The helpers below are its two, duplicated deliberately --
 * they are three lines each and sharing them would mean widening a private
 * surface for a test.
 */
class RefusedDetectionContractTest {
    private fun setProperties() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject

    private fun exportVersionEnum() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /**
     * These two keys file under 1.19 rather than riding under 1.18, and the
     * reason is a fact about a tag rather than a preference: 1.18 SHIPPED in
     * v0.1.50. `git show v0.1.50:core/model/.../SessionExport.kt` declares
     * `"1.18"`, and `git rev-list -n1 v0.1.50` is a7dfa323. A number a
     * consumer has already been handed cannot take a further entry without
     * redefining what that consumer was handed. 1.19 was already open on
     * `main` when this landed, so nothing is minted here.
     *
     * What is asserted is what the mint leaves TRUE, and deliberately NOT an
     * equality against the newest constant: that form belongs to whichever
     * change is newest and reds every time another one lands, which is why
     * `SchemaArmedSilenceContractTest`, `SchemaWorkedSideContractTest` and
     * `SchemaSoleSilenceContractTest` each deleted theirs.
     */
    @Test
    fun `the two keys file under 1_19, and 1_18 is still accepted`() {
        assertTrue("1.19" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.19 is not an accepted version")
        assertTrue("1.19" in exportVersionEnum(), "the published enum does not accept 1.19")
        assertTrue("1.18" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.18 left the accepted set")
        assertTrue("1.18" in exportVersionEnum(), "1.18 left the published enum, so this is not additive")
        val d = setProperties()["refusedDetections"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "schema 1.19, issue #125" in d,
            "the published key does not file itself under the number that minted it",
        )
    }

    /**
     * The vocabulary is asserted against the Kotlin constant for the reason
     * `velocityLossBasis` is: the schema is what an LLM reading this
     * repository is pointed at, and a vocabulary that drifts from the code
     * makes that document wrong in the one place nothing else checks.
     */
    @Test
    fun `the published refusal vocabulary is exactly the one the exporter declares`() {
        val reason = setProperties()["refusedDetectionReason"]!!.jsonObject
        assertEquals(
            SessionExport.VALID_REFUSED_DETECTION_REASONS,
            reason["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "refusedDetectionReason values drifted",
        )
    }

    /**
     * The count's floor is the whole of its type's meaning: a negative
     * refusal count would be nonsense and `"type": "integer"` alone permits
     * one.
     */
    @Test
    fun `the published refusal count is a non-negative integer`() {
        val count = setProperties()["refusedDetections"]!!.jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content, "refusedDetections type")
        assertEquals(0, count["minimum"]!!.jsonPrimitive.content.toInt(), "refusedDetections floor")
    }

    /**
     * The three states the count carries have to be readable FROM THE SCHEMA
     * and not only from the Kotlin. A reader holding this document and no
     * source is the reader the key exists for, and "absent" and "0" mean
     * different things here.
     */
    @Test
    fun `the published refusal count says what its absence means and what a zero does not`() {
        val d = setProperties()["refusedDetections"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("fewer than four detections" in d, "the absence case is named")
        assertTrue("a bound was derived and refused nothing" in d, "the zero case is named")
        assertTrue("NOT A COUNT OF THE SET'S PHANTOMS" in d, "and what a zero does not mean")
        assertTrue("4.5x the median" in d, "the rule's bound is stated")
    }
}
