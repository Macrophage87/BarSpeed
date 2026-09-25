package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SetPrescriptionExport] is a Kotlin grouping and never a published one:
 * [SetExportWireSerializer] lifts its keys into the set object, so the
 * published set stays flat and every existing reader reads it unchanged
 * (#157, #219).
 *
 * WHY THE GROUPING EXISTS. The session document and the raw archive's
 * manifest are written by two functions, and a key each listed for itself
 * drifted: `plannedLoad_kg` reached `session.json` and never `meta.json`
 * (#219). One object both writers publish is what stops the next key doing
 * the same. `RawExporterPrescriptionParityTest` in `:core:data` asserts the
 * two documents agree; this file asserts the grouping is invisible on the
 * wire and that the set it publishes is exactly the set the schema declares.
 *
 * Built by DECODING a published set rather than by calling the constructor,
 * so a key added to the grouping does not have to be threaded through this
 * file to keep it compiling.
 */
class SetPrescriptionExportTest {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    private val published =
        """
        {"exercise": "back_squat", "sets": [{"load_kg": 102.5, "plannedLoad_kg": 100.0, "reps": 5,
        "plannedReps": 6, "rest_s": 180, "tempoPrescribed": "3010", "summary": {}}]}
        """.trimIndent()

    private fun schemaSetKeys(): Set<String> = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject.getValue("\$defs").jsonObject.getValue("set").jsonObject
        .getValue("properties").jsonObject.keys

    /** A published set reads back with its prescription keys inside the grouping. */
    @Test
    fun `a published set's prescription keys decode into the grouping`() {
        val set = json.decodeFromString(ExerciseExport.serializer(), published).sets.single()
        assertEquals(6, set.prescription.plannedReps, "plannedReps did not reach the grouping")
        assertEquals(100.0, set.prescription.plannedLoadKg, "plannedLoad_kg did not reach the grouping")
        assertEquals(180, set.prescription.restS, "rest_s did not reach the grouping")
        assertEquals("3010", set.prescription.tempoPrescribed, "tempoPrescribed did not reach the grouping")
        assertEquals(5, set.reps, "a key of the set's own was taken into the grouping")
    }

    /**
     * The grouping never reaches the wire. A `prescription` object in a
     * published set would be a key the schema rejects -- `$defs.set` is
     * `additionalProperties: false` -- and a reader of the flat keys would
     * find none of them.
     */
    @Test
    fun `a set's prescription is published flat, with no grouping key`() {
        val set = json.decodeFromString(ExerciseExport.serializer(), published)
        val wire = Json.parseToJsonElement(json.encodeToString(ExerciseExport.serializer(), set))
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertFalse("prescription" in wire, "the grouping reached the wire: $wire")
        assertEquals("6", wire["plannedReps"]?.jsonPrimitive?.content, "plannedReps is not on the set: $wire")
        assertEquals("100.0", wire["plannedLoad_kg"]?.jsonPrimitive?.content, "plannedLoad_kg is not on the set")
        assertEquals("180", wire["rest_s"]?.jsonPrimitive?.content, "rest_s is not on the set: $wire")
        assertEquals("3010", wire["tempoPrescribed"]?.jsonPrimitive?.content, "tempoPrescribed is not on the set")
        assertEquals("5", wire["reps"]?.jsonPrimitive?.content, "the set's own key was lost: $wire")
    }

    /**
     * No key is both the set's own and the grouping's. A collision would be
     * written twice into one JSON object by the flattening, and which of the
     * two survived would depend on map order.
     */
    @Test
    fun `no prescription key is also a key of the set's own`() {
        val own = SetExport.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        val collisions = SetExportWireSerializer.prescriptionKeys.intersect(own.toSet())
        assertTrue(collisions.isEmpty(), "keys published by both the set and its grouping: $collisions")
    }

    /**
     * The set the export can publish is exactly the set the schema declares,
     * in both directions. A Kotlin key with no schema property is a document
     * the schema rejects; a schema property with no Kotlin key is a promise no
     * build keeps. Read through [SetExportWireSerializer.wireKeys], because the
     * grouping itself is not a published key and its contents are.
     */
    @Test
    fun `the published set carries exactly the keys the schema declares`() {
        assertEquals(schemaSetKeys(), SetExportWireSerializer.wireKeys, "the Kotlin set and the schema disagree")
    }

    /**
     * Which keys the grouping holds, as a literal. Kept literal rather than
     * derived, the house rule for key pins: a descriptor-derived list follows a
     * `@SerialName` rename silently, and a renamed key is one every reader
     * loses.
     *
     * RED WHEN WRITTEN, at export 1.23 (#157): the five keys 1.23 mints --
     * `workingReps`, `workingLoad_kg`, `workingDuration_s`, `plannedTempo` and
     * `restMeasured_s` -- are planned, working and rest keys, so they belong in
     * the one grouping both writers publish, and the grouping did not hold
     * them when this was written. Renamed from "the grouping holds the plan's targets, the rest
     * and the working tempo", which named the five it held before.
     */
    @Test
    fun `the grouping holds every planned, working and rest key`() {
        assertEquals(
            setOf(
                "plannedLoad_kg", "plannedReps", "plannedDuration_s", "rest_s", "tempoPrescribed",
                "workingLoad_kg", "workingReps", "workingDuration_s", "plannedTempo", "restMeasured_s",
            ),
            SetExportWireSerializer.prescriptionKeys,
        )
    }
}
