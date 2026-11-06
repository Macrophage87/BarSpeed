package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export 1.27's further entry for the exercise-inference changes (core-model
 * review 2026-11-06, F1 and F4; whole-release review): a plan exercise the app
 * ships no entry for, with no declared `start` or `kind`, publishes a
 * different `geometry.startsWith` or `geometry.kind` from this release.
 *
 * Pins the contract text: the entry is filed once, after the 1.27 mint and
 * BEFORE the tempo-notation entry (whose test reads from its own marker to the
 * end of the log and requires "NOT RETROACTIVE" to be absent there); it says
 * no key changes, not retroactive, and that the database stays; the KDoc in
 * `SessionExport.kt` and the published schema say the same; and every id the
 * entry names as moving gets the answer the entry claims. The answers
 * themselves are `ExerciseIdInferenceCharacterizationTest`'s.
 *
 * Deliberately does NOT assert the tip literal `SCHEMA_VERSION`: that lives in
 * one test only.
 */
class SchemaExerciseInferenceContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/session-export.schema.json")) {
            "session-export.schema.json is not on the test classpath"
        }.readBytes().decodeToString(),
    ).jsonObject

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val marker = "FURTHER 1.27 ENTRY (exercise inference)"
    private val tempoMarker = "FURTHER 1.27 ENTRY (tempo notation)"
    private val mint = "1.27 (sensor-counted target cue)"

    private val labelMarker = "FURTHER 1.27 ENTRY (sensor labels)"
    private val nextEntry = "FURTHER 1.27 ENTRY ("

    private fun jsonEntry(): String = versionLog().substringAfter(marker).substringBefore(nextEntry)

    /** `SessionExport.kt`'s source, with the comment's line prefixes folded into single spaces. */
    private fun kdoc(): String {
        val source = File("src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt")
        check(source.isFile) { "SessionExport.kt not found from ${File(".").absolutePath}" }
        return source.readText().replace(Regex("\\s*\\n\\s*\\*\\s?"), " ").replace(Regex("\\s+"), " ")
    }

    /** The inference entry in the KDoc above `SCHEMA_VERSION`. */
    private fun kdocEntry(): String {
        val folded = kdoc()
        assertEquals(1, Regex(Regex.escape(marker)).findAll(folded).count(), "the KDoc entry is not filed once")
        return folded.substringAfter(marker).substringBefore(nextEntry)
    }

    @Test
    fun `the version log files the inference entry once, after the mint and before tempo notation`() {
        val log = versionLog()
        assertEquals(1, Regex(Regex.escape(marker)).findAll(log).count(), "the entry is not filed once")
        assertTrue(log.indexOf(marker) > log.indexOf(mint), "the further entry is filed before the mint it extends")
        assertTrue(log.indexOf(marker) < log.indexOf(tempoMarker), "filed after the tempo-notation entry")
        val tempoTail = log.substringAfter(tempoMarker)
        assertTrue("NOT RETROACTIVE" !in tempoTail, "tempo notation's tail gained a NOT RETROACTIVE")
    }

    @Test
    fun `both statements say no key, not retroactive and no database move`() {
        listOf("schema" to jsonEntry(), "KDoc" to kdocEntry()).forEach { (where, entry) ->
            val opener = " NO KEY CHANGES, but the figures of a population move"
            assertTrue(entry.startsWith(opener), "$where: ${entry.take(80)}")
            assertTrue("NOT RETROACTIVE for a recorded set" in entry, "$where does not say it is not retroactive")
            assertTrue("DATABASE_VERSION`? does NOT move".toRegex() in entry, "$where does not say the database stays")
            assertTrue("plan schema is untouched" in entry, "$where does not say the plan schema stays")
            assertTrue("inferred" in entry && "source" in entry, "$where does not say how to find affected sets")
            assertTrue("should declare `?start".toRegex() in entry, "$where does not tell plans to declare start")
        }
    }

    @Test
    fun `every id the entry names as moving gets the answer it claims, in both statements`() {
        val json = jsonEntry()
        val kdoc = kdocEntry()
        val claims =
            listOf(
                "leg_extension", "step_up", "glute_bridge", "sit_up", "hip_abduction", "pec_deck",
                "tricep_kickback", "triceps_kickback", "kickback", "glute_kickback",
                "seated_dumbbell_press", "arnold_press", "dumbbell_step_up", "seated_leg_extension", "cable_kickback",
            ).associateWith { StartPhase.CONCENTRIC } +
                listOf(
                    "overhead_squat", "overhead_tricep_extension", "overhead_triceps_extension", "nordic_curl",
                    "jefferson_curl", "dumbbell_romanian_deadlift", "snatch_grip_rdl", "stiff_leg_deadlift",
                ).associateWith { StartPhase.ECCENTRIC }
        claims.forEach { (id, start) ->
            assertTrue(id in json && id in kdoc, "$id is not named in both statements")
            assertEquals(start, ExerciseDef.inferStartPhase(id), id)
            assertEquals(null, ExerciseDef.seedById(id), "$id is seeded, so it cannot move")
        }
        listOf("plank_row", "side_plank_hip_dip", "leg_press_sled", "hack_squat_sled").forEach { id ->
            assertTrue(id in json && id in kdoc, "$id is not named in both statements")
            assertEquals(ExerciseKind.DYNAMIC, ExerciseDef.inferKind(id), id)
        }
    }

    /**
     * The sensor-label entry (whole-release review): auto-labelling and the
     * collision fix move `unitAddresses` for a relabelled unit, so the log
     * says so, once in each statement and before tempo notation, and the
     * key's description no longer claims pairing never moves a label.
     */
    @Test
    fun `the sensor-label entry is filed before tempo notation and unitAddresses says labels can move`() {
        listOf("schema" to versionLog(), "KDoc" to kdoc()).forEach { (where, text) ->
            assertEquals(1, Regex(Regex.escape(labelMarker)).findAll(text).count(), "$where: not filed once")
            assertTrue(text.indexOf(labelMarker) < text.indexOf(tempoMarker), "$where: filed after tempo notation")
            val entry = text.substringAfter(labelMarker).substringBefore(nextEntry)
            assertTrue(entry.startsWith(" NO KEY CHANGES"), "$where: ${entry.take(80)}")
            assertTrue("unitAddresses" in entry, "$where does not name the key whose values move")
            assertTrue("does not clear its label" in entry, "$where does not say forgetting keeps the label")
            assertTrue("DATABASE_VERSION`? does NOT move".toRegex() in entry, "$where does not say the database stays")
        }
        val units = schema.getValue("\$defs").jsonObject.getValue("setSensors").jsonObject
            .getValue("properties").jsonObject.getValue("unitAddresses").jsonObject
            .getValue("description").jsonPrimitive.content
        assertTrue("not by pairing" !in units, "unitAddresses still says pairing never moves a label")
        assertTrue("Forgetting a device does not clear its label" in units, units)
    }
}
