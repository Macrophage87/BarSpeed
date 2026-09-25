package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * What the published export says about whether a set's LIVE velocity
 * integrator held its zero, issue #302.
 *
 * DIFFERENTIALS. `the Kotlin twin carries the same key` and `the plan prompt
 * tells the coach how to read the flag` both FAIL at the commit that writes
 * this file: the published schema declares `countTrusted` and `SetExport` does
 * not, and `PLAN_PROMPT` does not mention it. The tests that read only the
 * published document pass here, and they are in this commit rather than the
 * next so the description cannot later be reworded without a test naming what
 * it said. The commit after this one adds the Kotlin field, the exporter's
 * mapping and the prompt's line.
 *
 * WHAT THE KEY IS AND IS NOT. `StreamingSetTracker` latches its `countTrusted`
 * false the first time one movement run carries further than any real phase
 * of the lift can: the live integral has lost its zero. Since #301 the count
 * comes from a detector that reads no velocity -- `DriveImpulseCounter` in
 * v0.1.54, `CycleRepCounter` from #305 -- so the flag describes the velocity,
 * ROM and power path and not the count; on field-43's three deadlifts it was
 * false on every stream while the impulse counter called 13 of 15 reps. A reader who took it for "the count is wrong"
 * would be told to count by hand a set the sensor counted correctly, so the
 * description has to forbid that reading in words and this file pins that it
 * does.
 *
 * A separate file rather than cases in `SchemaContractTest`, which sits on
 * detekt's `LargeClass` limit, with its own private `serialKeysOf` as the other
 * per-key contract files keep theirs.
 */
class SchemaCountTrustedContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setDef(): JsonObject = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!.jsonObject

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) { "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts" }
            .readBytes().decodeToString()

    /** The published key exists, is a boolean, and is not required. */
    @Test
    fun `the published set declares countTrusted as an optional boolean`() {
        val key = assertNotNull(setDef()["properties"]!!.jsonObject["countTrusted"], "no countTrusted in the schema")
            .jsonObject

        assertEquals("boolean", key["type"]!!.jsonPrimitive.content)
        val required = setDef()["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue("countTrusted" !in required, "a key no earlier set can carry was made required")
    }

    /**
     * THE DIFFERENTIAL. The Kotlin type the exporter serialises carries the key
     * the document declares.
     */
    @Test
    fun `the Kotlin twin carries the same key`() {
        assertTrue(
            "countTrusted" in serialKeysOf(SetExport.serializer()),
            "SetExport does not publish the countTrusted the schema declares",
        )
    }

    /**
     * The description says what a false means for the figures, that it is NOT
     * about the count, and what absence means.
     *
     * Pinned in its own words rather than by shape, because the defect this key
     * invites is a reading and not a type: "count this set by hand" is the
     * sentence the design round (#301) said must never be read off it.
     */
    @Test
    fun `the published description says it is the velocity's trust and not the count's`() {
        val d = setDef()["properties"]!!.jsonObject["countTrusted"]!!.jsonObject["description"]!!
            .jsonPrimitive.content
        listOf("velocityLoss_pct", "rom_m", "power", "not derived from a trusted velocity").forEach {
            assertTrue(it in d, "the description does not say a false qualifies: $it")
        }
        assertTrue("NOT ABOUT THE COUNT" in d, "the description does not say the flag is not about the count")
        assertTrue("count this set by hand" in d, "the description does not name the reading it forbids")
        assertTrue("TRUE CERTIFIES NOTHING" in d, "the description lets true read as a certificate")
        assertTrue("ABSENT" in d, "the description does not say what absence means")
        assertTrue("neither false nor true" in d, "the description lets absence read as an answer")
    }

    /**
     * The version log files the key under the unreleased 1.22, once, and says
     * the database does not move with it.
     *
     * The marker is matched without its ordinal, because another lane may file
     * a further 1.22 entry first and the landing step renumbers the ordinals;
     * the issue tag and the key name are what identify this entry. No `1.22:`
     * marker is used -- `SchemaUnitIdentityContractTest` reads everything after
     * the LAST one, and a new one would silently narrow what it inspects.
     */
    @Test
    fun `the version log files the key once under 1_22 and moves no database version`() {
        val log = exportVersionLog()
        val marker = Regex("1\\.22 TAKES A \\w+ ENTRY \\(#302, countTrusted\\)")

        assertEquals(1, marker.findAll(log).count(), "the entry is not filed exactly once")
        val entry = log.substring(marker.find(log)!!.range.first)
        assertTrue("`countTrusted`" in entry, "the entry names no key")
        assertTrue("NOT RETROACTIVE" in entry, "the entry does not say old sets stay absent")
        assertTrue("DATABASE_VERSION does NOT move" in entry, "the entry does not say the database stays put")
        // Its filed number being ACCEPTED, not being the tip: 1.22 shipped in
        // v0.1.55 and #157 minted 1.23, so an equality with the tip went false
        // at that mint and is not re-pointed at it.
        assertTrue("1.22" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
    }

    /**
     * The published example carries the key, both ways, so `ci.yml`'s ajv step
     * validates a document that has one and a reader of the example sees that
     * false and true are both real answers.
     */
    @Test
    fun `the published example carries the flag both ways`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val flags = sets.mapNotNull { it.jsonObject["countTrusted"]?.jsonPrimitive?.booleanOrNull }

        assertEquals(setOf(false, true), flags.toSet(), "the example does not publish both answers")
    }

    /**
     * THE COPY THE COACH RECEIVES says how to read the flag, and says it is
     * not about the count. `PLAN_PROMPT` is the canonical statement of how to
     * read an export; a key it never mentions is a key the coach never weighs.
     */
    @Test
    fun `the plan prompt tells the coach how to read the flag`() {
        assertTrue("\"countTrusted\"" in prompt, "the prompt the coach receives does not mention countTrusted")
        assertTrue(
            "not derived from a trusted velocity" in prompt.lowercase(),
            "the prompt does not say what a false means for the figures",
        )
        assertTrue("not about the count" in prompt.lowercase(), "the prompt does not say the flag is not the count's")
    }
}
