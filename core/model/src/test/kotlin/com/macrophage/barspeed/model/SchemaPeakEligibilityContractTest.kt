package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
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
 * Schema 1.22's thirteenth entry, issue #306: which reps a set's peak pair and
 * its velocity loss are taken over, the per-rep guard-band count that says why
 * a row with no artefact in its span was left out, and the basis word a set
 * with no eligible pair publishes.
 *
 * In its own file on the grounds `SchemaArtefactSampleContractTest` states: a
 * version entry has several halves that must move together.
 *
 * GREEN AT THE COMMIT THAT ADDS IT, and said so. The schema, the Kotlin twin
 * and the example move in the same commit as the behavioural reds, which is
 * the one carve-out `.claude/facts/live-state.md` section 9 makes for a
 * deliberate contract change; the reds are `:core:dsp`'s `PeakEligibilityTest`
 * and `:core:data`'s `SessionExportPeakEligibilityTest`. What this file pins is
 * that the published document and the Kotlin side cannot move apart.
 */
class SchemaPeakEligibilityContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun exportSchema() = schema("session-export.schema.json")

    private fun setDef() = exportSchema()["\$defs"]!!.jsonObject["set"]!!.jsonObject

    private fun repDef() = exportSchema()["\$defs"]!!.jsonObject["repMetrics"]!!.jsonObject

    private fun description(obj: JsonObject, key: String) =
        obj["properties"]!!.jsonObject[key]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun versionLog() = exportSchema()["properties"]!!.jsonObject["schemaVersion"]!!
        .jsonObject["description"]!!.jsonPrimitive.content

    private fun exampleSets() = schema("examples/session-export.example.json")["exercises"]!!
        .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        .map { it.jsonObject }

    @Test
    fun `the per-rep band count is published, an integer, floored at zero and not required`() {
        val count = assertNotNull(
            repDef()["properties"]!!.jsonObject["guardArtefactSamples"],
            "a rep declares no guardArtefactSamples key",
        ).jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content, "guardArtefactSamples type")
        assertEquals(0, count["minimum"]!!.jsonPrimitive.content.toInt(), "guardArtefactSamples floor")
        val required = repDef()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("guardArtefactSamples" !in required, "a rep analysed before the band could not be published")
        assertEquals(
            false,
            repDef()["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the rep object stopped being closed",
        )
    }

    @Test
    fun `the basis vocabulary carries noEligiblePair on both sides`() {
        assertTrue("noEligiblePair" in SessionExport.VALID_VELOCITY_LOSS_BASES, "the Kotlin vocabulary")
        val published = setDef()["properties"]!!.jsonObject["velocityLossBasis"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("noEligiblePair" in published, "the published vocabulary")
        assertTrue("noEligiblePair" in description(setDef(), "velocityLossBasis"), "the word is not explained")
    }

    /**
     * The three narrowed keys say what they are now taken over. Narrow, and
     * said so: this checks the descriptions name the inputs and the absence
     * rule, never that the prose is right.
     */
    @Test
    fun `the three narrowed keys say which reps they are taken over`() {
        val summary = setDef()["properties"]!!.jsonObject["summary"]!!.jsonObject
        listOf("peakConVel_mps" to "summary.meanConVel_mps", "peakPower_w" to "summary.meanConPower_w").forEach {
            val text = description(summary, it.first)
            listOf("guardArtefactSamples", "romBounded", "artefactSamples", it.second, "ABSENT").forEach { term ->
                assertTrue(term in text, "summary.${it.first}'s description does not mention $term")
            }
        }
        val loss = description(setDef(), "velocityLoss_pct")
        assertTrue("noEligiblePair" in loss, "velocityLoss_pct does not say when it is withheld")
        assertTrue("last" in loss && "eligible" in loss, "velocityLoss_pct does not name its population")
    }

    /**
     * The example exercises the narrowing rather than only the shape. Its
     * detailed set carries a row that is clean inside its span but has two
     * samples in its band, and an unbounded row, and BOTH peak above the
     * summary's pair -- so a consumer taking the #290 maximum reads a different
     * number from the one published, for each of the two new reasons. Its last
     * row is the unbounded one, so the set publishes no velocity loss and says
     * `noEligiblePair`.
     */
    @Test
    fun `the example carries the band on every detailed row and shows both new reasons`() {
        val detailed = exampleSets().single { it["repMetrics"] != null }
        val rows = detailed["repMetrics"]!!.jsonArray.map { it.jsonObject }
        fun int(row: JsonObject, key: String) = row[key]!!.jsonPrimitive.content.toInt()
        fun num(row: JsonObject, key: String) = row[key]!!.jsonPrimitive.content.toDouble()
        assertEquals(listOf(0, 0, 2, 0, 0), rows.map { int(it, "guardArtefactSamples") }, "band counts per row")
        val summary = detailed["summary"]!!.jsonObject
        val eligible = rows.filter {
            int(it, "artefactSamples") == 0 && int(it, "guardArtefactSamples") == 0 &&
                it["romBounded"]!!.jsonPrimitive.content.toBoolean()
        }
        listOf("peakConVel_mps", "peakPower_w").forEach { key ->
            val published = summary[key]!!.jsonPrimitive.content.toDouble()
            assertEquals(eligible.maxOf { num(it, key) }, published, "summary.$key over the eligible rows")
            val banded = rows.single { int(it, "guardArtefactSamples") > 0 }
            assertTrue(num(banded, key) > published, "the banded row does not out-peak summary.$key")
            val unbounded = rows.single { !it["romBounded"]!!.jsonPrimitive.content.toBoolean() }
            assertTrue(num(unbounded, key) > published, "the unbounded row does not out-peak summary.$key")
            assertTrue(int(banded, "artefactSamples") == 0 && int(unbounded, "artefactSamples") == 0, "#290 alone")
        }
        assertEquals("noEligiblePair", detailed["velocityLossBasis"]!!.jsonPrimitive.content, "the example's basis")
        assertTrue("velocityLoss_pct" !in detailed, "the example publishes a loss with no eligible pair")
    }

    /**
     * `noReference` is decided over the peak-eligible reps from 1.22, because
     * `VelocityLoss.of` takes its best over them, so the word's description and
     * the thirteenth entry must both say so. Round 1 finding 2, and RED at the
     * commit that adds it. Narrow, and said so: this checks one sentence is
     * present, one is gone and the entry names the word, never that the prose
     * is right.
     */
    @Test
    fun `noReference says it is decided over the peak-eligible reps`() {
        val basis = description(setDef(), "velocityLossBasis")
        assertTrue(
            "\"noReference\": no peak-eligible rep carried a positive drive velocity to divide by" in basis,
            "velocityLossBasis does not say noReference is decided over the eligible reps",
        )
        assertTrue("no rep carried a positive drive velocity" !in basis, "the pre-1.22 noReference sentence survives")
        val entry = versionLog().substringAfter("1.22 TAKES A THIRTEENTH ENTRY (#306")
        assertTrue("noReference" in entry, "the thirteenth 1.22 entry does not say noReference narrowed")
    }

    @Test
    fun `the version log carries the thirteenth 1_22 entry once`() {
        val log = versionLog()
        assertEquals(1, Regex("1\\.22 TAKES A THIRTEENTH ENTRY \\(#306").findAll(log).count(), "the entry's marker")
        listOf("guardArtefactSamples", "noEligiblePair", "NOT PURELY ADDITIVE", "0.362 s", "RETROACTIVE").forEach {
            assertTrue(it in log, "the version log's #306 entry does not say $it")
        }
        assertTrue("1.22" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the number the entry is filed under")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the Kotlin twin publishes the band under the declared serial name`() {
        assertTrue(
            "guardArtefactSamples" in RepMetricsExport.serializer().descriptor.elementNames.toList(),
            "RepMetricsExport does not publish guardArtefactSamples",
        )
    }
}
