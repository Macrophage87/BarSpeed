package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Export 1.23, minted here (#157, folding #151, #76 and #219): a set may carry
 * the target it RAN AGAINST beside the one the plan prescribed and the one it
 * is recorded as, the plan's own tempo, and the rest two clock instants
 * measured.
 *
 * WHAT THIS FILE PINS is the published half: the five keys, their types, the
 * descriptions of the keys whose meaning 1.23 states, the version log's entry
 * and the example ajv validates. It cannot see what the exporter writes;
 * `SessionExportWorkingTargetsTest` and `RawExporterPrescriptionParityTest` in
 * `:core:data` are the differentials for that, and
 * `SetPrescriptionExportTest` pins the Kotlin keys against this schema.
 *
 * THE OWNER'S TWO RULINGS, 2026-09-25, are what the descriptions are held to.
 * On a lowered target: "It's completed even if the target is lowered, just
 * note the discrepancy." On rest: "I consider rests a minimum. If it takes
 * more time to setup I do." -- and, the same day, "With a gym that a lot of
 * people are using, timing can't easily be predicted in advance." So the
 * published text calls a met lowered target completed, calls `rest_s` a
 * minimum, and says a measured rest shorter than it is a discrepancy and draws
 * no conclusion from a long one.
 *
 * THE TIP LITERAL NO LONGER LIVES HERE. It did while 1.23 was the tip, on the
 * rule `SchemaSkippedSetContractTest` wrote when it minted 1.21: the literal the
 * exporter writes is asserted in the file that mints it. 1.23 shipped in
 * v0.1.56 and #62 minted 1.24, so `SchemaSessionHrvContractTest` holds it now.
 * What this file pins is its own filed version, 1.23, being accepted, which no
 * later mint can make false.
 */
class SchemaWorkingTargetContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private val schema = document("session-export.schema.json")

    private fun setProperty(name: String): JsonObject = assertNotNull(
        schema.getValue("\$defs").jsonObject.getValue("set").jsonObject.getValue("properties").jsonObject[name],
        "the published set declares no $name",
    ).jsonObject

    private fun description(name: String): String = setProperty(name).getValue("description").jsonPrimitive.content

    private fun versionLog(): String = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
        .getValue("description").jsonPrimitive.content

    private val minted = listOf("workingReps", "workingLoad_kg", "workingDuration_s", "plannedTempo", "restMeasured_s")

    @Test
    fun `1_23 is accepted by the code and the schema, and 1_22 is still readable`() {
        val enum = schema.getValue("properties").jsonObject.getValue("schemaVersion").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("1.23" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version filed under is not accepted")
        assertTrue("1.23" in enum, "the published schema rejects the version filed under")
        assertTrue("1.22" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.22, shipped in v0.1.55, left the accepted set")
    }

    /**
     * Each key exists with the type its column has, none is required -- a set
     * recorded before database v20 carries none of them -- and the three
     * counts and durations cannot be negative.
     */
    @Test
    fun `the published set declares the five keys, typed and optional`() {
        val types = minted.associateWith { setProperty(it).getValue("type").jsonPrimitive.content }
        assertEquals(
            mapOf(
                "workingReps" to "integer",
                "workingLoad_kg" to "number",
                "workingDuration_s" to "integer",
                "plannedTempo" to "string",
                "restMeasured_s" to "number",
            ),
            types,
        )
        for (key in listOf("workingReps", "workingDuration_s", "restMeasured_s")) {
            assertEquals("0", setProperty(key)["minimum"]?.jsonPrimitive?.content, "$key has no floor of 0")
        }
        assertFalse("minimum" in setProperty("workingLoad_kg"), "workingLoad_kg has a floor load_kg does not")
        val required = schema.getValue("\$defs").jsonObject.getValue("set").jsonObject
            .getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(minted.none { it in required }, "a 1.23 key is required, which invalidates every older export")
    }

    /** A met lowered target is completed, and the difference is stated as a fact, not a failure. */
    @Test
    fun `a working figure below the plan is described as a completed set, not a failure`() {
        for (key in listOf("workingReps", "workingDuration_s")) {
            val d = description(key)
            assertTrue("lowered" in d, "$key does not say a working figure below the plan is a lowered target")
            assertTrue("COMPLETED" in d, "$key does not say a met lowered target is completed: $d")
            assertTrue("not a failure" in d, "$key does not say the difference is not a failure: $d")
        }
    }

    /**
     * `rest_s` is a MINIMUM, and only a measured rest SHORTER than it is a
     * discrepancy. Neither description may read a long rest as anything: the
     * owner's gym makes timing unpredictable, so a long measured rest says
     * nothing about readiness.
     */
    @Test
    fun `rest_s is a minimum and only a shorter measured rest is a discrepancy`() {
        val rest = description("rest_s")
        val measured = description("restMeasured_s")
        assertTrue("MINIMUM" in rest, "rest_s does not say it is a minimum")
        assertTrue("SHORTER than it is a discrepancy" in rest, "rest_s does not say which rest is a discrepancy")
        assertTrue("SHORTER than rest_s is a discrepancy" in measured, "restMeasured_s does not say which is short")
        assertTrue("START tap on the NEXT set" in measured, "restMeasured_s does not say where the rest ends")
        assertTrue("last set of a session" in measured, "restMeasured_s does not say it is absent on the last set")
        for (reading in listOf("fatigue", "readiness", "recover", "non-compliance", "too long")) {
            assertFalse(reading in measured.lowercase(), "restMeasured_s reads a long rest as $reading: $measured")
        }
    }

    /** The planned keys say they are frozen; `tempoPrescribed` says it is the working tempo. */
    @Test
    fun `the planned keys are frozen and tempoPrescribed is the working tempo`() {
        for (key in listOf("plannedReps", "plannedLoad_kg", "plannedDuration_s", "plannedTempo")) {
            assertTrue("frozen" in description(key), "$key does not say the plan's figure is frozen")
        }
        assertTrue("WORKING tempo" in description("tempoPrescribed"), "tempoPrescribed does not say which tempo")
        assertTrue("historical" in description("tempoPrescribed"), "tempoPrescribed does not say its name misleads")
    }

    /**
     * `workingLoad_kg` is what tells a set this build could describe from one
     * it could not, because every set has a load. A reader must be told both
     * halves: that a v20 set always carries it, and that its absence voids the
     * other working keys' absences.
     */
    @Test
    fun `workingLoad_kg marks a set whose working targets the build could state`() {
        val d = description("workingLoad_kg")
        assertTrue("EVERY SET RECORDED FROM DATABASE v20 CARRIES IT" in d, "workingLoad_kg does not say who carries it")
        assertTrue("must not be assumed to have run" in d, "workingLoad_kg lets an old set read as run to plan")
    }

    /**
     * `load_kg` and `duration_s` are the ACTUAL figures, and the sentences
     * 1.23 made false are gone rather than reworded: a corrected load is no
     * longer indistinguishable on a v20 set, and "WORKING TO" named a target
     * no key published.
     */
    @Test
    fun `load_kg and duration_s are the actual figures and drop what 1_23 made false`() {
        val load = description("load_kg")
        val duration = description("duration_s")
        assertTrue("ACTUAL load" in load, "load_kg does not say it is the actual load")
        assertTrue("workingLoad_kg" in load, "load_kg does not point at the working load")
        assertFalse("-- both are the lifter stating what was on the bar --" in load, "load_kg keeps the old claim")
        assertTrue("ACTUAL hold/carry seconds" in duration, "duration_s does not say it is the actual figure")
        assertTrue("workingDuration_s" in duration, "duration_s does not point at the working target")
        assertFalse("WORKING TO" in duration, "duration_s keeps the stale wording")
    }

    /**
     * The published log files 1.23 once, as a mint, and says what an older
     * reader does in both directions, that nothing is backfilled and that
     * the database does not move with it.
     */
    @Test
    fun `the 1_23 log entry is filed once and says what an older reader does`() {
        val log = versionLog()
        val marker = "1.23: MINTED HERE (#157"
        assertEquals(1, log.split(marker).size - 1, "the 1.23 entry is not filed exactly once")
        val entry = log.substringAfter(marker)
        for (fact in listOf("REJECTS", "ACCEPTS", "NOT RETROACTIVE", "DATABASE_VERSION does NOT move", "(#219)")) {
            assertTrue(fact in entry, "the 1.23 entry does not state: $fact")
        }
        assertTrue(minted.all { it in entry }, "the 1.23 entry does not name every key it mints")
        assertFalse("1.22:" in entry, "a 1.22 marker after the 1.23 one narrows what older pins read")
    }

    /** The example ajv validates carries every minted key, so the schema half is exercised at all. */
    @Test
    fun `the published example carries every key 1_23 mints`() {
        val example = document("examples/session-export.example.json")
        val keys = example.getValue("exercises").jsonArray
            .flatMap { it.jsonObject.getValue("sets").jsonArray }
            .flatMap { it.jsonObject.keys }
            .toSet()
        assertEquals(emptyList(), minted.filter { it !in keys }, "keys the example never carries")
    }
}
