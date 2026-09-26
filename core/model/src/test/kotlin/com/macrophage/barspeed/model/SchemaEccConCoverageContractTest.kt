package com.macrophage.barspeed.model

import kotlinx.serialization.descriptors.elementNames
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
 * Export 1.23, a further entry (#88): `tempoCompliance` may carry
 * `actualEccConRatioReps`, how many reps `actualEccConRatio` was taken over.
 *
 * WHAT WAS UNSAYABLE. The ratio is taken over the reps that resolved an
 * eccentric (#46), which on the committed captures is not always every rep;
 * `of` beside it counts reps that resolved ANY scored phase. So the thinnest
 * ratio in a document read exactly like the best-supported one, and the plan
 * prompt tells the coach to prefer it.
 *
 * WHY STORED AND NOT DERIVED AT EXPORT. The count is taken in the pass that
 * takes the ratio and frozen with it. A count re-derived at export from the
 * stored reps would describe today's pairing rule, and a set analysed before
 * #46 carries a ratio taken by another one. A set analysed before this key
 * carries none, and absent means "not recorded", never 0.
 *
 * RED WHEN WRITTEN. `EccConRatioCoverageTest` in `:core:dsp` and
 * `SessionExportEccConCoverageTest` in `:core:data` are the differentials.
 */
class SchemaEccConCoverageContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private fun tempoProperties(): JsonObject = schema.getValue("\$defs").jsonObject.getValue("set")
        .jsonObject.getValue("properties").jsonObject.getValue("tempoCompliance")
        .jsonObject.getValue("properties").jsonObject

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "1.23 FURTHER ENTRY (#88"

    /** The key exists, is a whole number of at least 1, and is optional. */
    @Test
    fun `tempoCompliance declares the ratio's rep count, typed and optional`() {
        val key = assertNotNull(tempoProperties()["actualEccConRatioReps"], "no actualEccConRatioReps").jsonObject
        assertEquals("integer", key.getValue("type").jsonPrimitive.content)
        assertEquals("1", key["minimum"]?.jsonPrimitive?.content, "a count beside a ratio cannot be 0")
        val required = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject.getValue("properties")
            .jsonObject.getValue("tempoCompliance").jsonObject.getValue("required").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("actualEccConRatioReps" !in required, "required, which invalidates every older export")
    }

    /** The description says which reps, how it differs from `of`, and what its absence means. */
    @Test
    fun `the count is described against of and its absence`() {
        val d = tempoProperties().getValue("actualEccConRatioReps").jsonObject.getValue("description")
            .jsonPrimitive.content
        assertTrue("resolved an eccentric" in d, "the count does not say which reps: $d")
        assertTrue("NOT `of`" in d, "the count does not say how it differs from `of`")
        assertTrue("ABSENT on a set analysed before" in d, "the count does not say what its absence means")
        val ratio = tempoProperties().getValue("actualEccConRatio").jsonObject.getValue("description")
            .jsonPrimitive.content
        assertTrue("`actualEccConRatioReps`" in ratio, "the ratio does not point at its count")
    }

    /** The Kotlin twin publishes the key under the name the schema declares. */
    @Test
    fun `the Kotlin tempo block carries the key`() {
        assertTrue(
            "actualEccConRatioReps" in TempoComplianceExport.serializer().descriptor.elementNames.toList(),
            "TempoComplianceExport cannot publish the count",
        )
    }

    /** The example ajv validates carries the count beside a ratio, so the declaration is exercised. */
    @Test
    fun `the published example carries the count beside a ratio`() {
        val blocks = document("examples/session-export.example.json").getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .mapNotNull { it.jsonObject["tempoCompliance"]?.jsonObject }
        val withCount = blocks.filter { "actualEccConRatioReps" in it }
        assertTrue(withCount.isNotEmpty(), "no example set carries actualEccConRatioReps")
        assertTrue(withCount.all { "actualEccConRatio" in it }, "an example count stands beside no ratio")
    }

    /** The log files the key once and says it is not retroactive and the database does not move. */
    @Test
    fun `the 1_23 log files the count once`() {
        val log = versionLog()
        assertEquals(1, log.split(marker).size - 1, "the #88 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore("1.23 FURTHER ENTRY (")
        for (fact in listOf("actualEccConRatioReps", "NOT RETROACTIVE", "DATABASE_VERSION does NOT move")) {
            assertTrue(fact in entry, "the #88 entry does not state: $fact")
        }
    }

    /** The prompt points the coach at the ratio; it now says to weigh it by its count. */
    @Test
    fun `the plan prompt tells the coach to weigh the ratio by its count`() {
        assertTrue("\"actualEccConRatioReps\"" in prompt, "the plan prompt does not name the ratio's count")
    }
}
