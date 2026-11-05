package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire names a serializable class declares, read off the class itself.
 * Its own private copy, as every sibling contract test here carries one, for
 * the reason `SchemaContractTest` gives: that class sits on detekt's
 * `LargeClass` limit.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun serialKeysOf(serializer: KSerializer<*>): Set<String> =
    serializer.descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

/**
 * Every object the published session export declares carries exactly the keys
 * its Kotlin class can write, in both directions (#99 item 1).
 *
 * WHAT THIS CLOSES. `SchemaContractTest` derives and compares the plan
 * exercise object's key set and had no equivalent for the export, so a
 * published property with no DTO field behind it passed silently: the
 * document could promise a key the exporter never writes. Two objects were
 * already pinned this way before this file -- the set, through
 * [SetExportWireSerializer.wireKeys] in `SetPrescriptionExportTest` (#157),
 * and the sensors block in `SchemaContractTest` -- and they stay where they
 * are. This file holds the rest, and names the set and the sensors block too
 * so that one table lists every object a reader of the export can meet.
 *
 * DERIVED FROM THE DESCRIPTOR ON PURPOSE, the opposite of the plan pins'
 * literals. A literal pin catches a rename and cannot catch a key the schema
 * declares with no field behind it, which is this file's subject; the plan
 * literals and the literal session-level pin in `SchemaContractTest` keep the
 * other job. Each object here is `additionalProperties: false`, so the
 * direction this adds is the one ajv cannot see: a schema property no build
 * writes.
 *
 * What it does NOT see: which keys the exporter actually populates. A field
 * the DTO declares and `SessionExporter` never sets passes here; the exact-set
 * pins on real exporter output are in `:core:data`.
 */
class SchemaExportKeySetContractTest {
    private val schema: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun JsonObject.at(vararg path: String): JsonObject =
        path.fold(this) { obj, key -> obj.getValue(key).jsonObject }

    private fun declared(vararg path: String): Set<String> = schema.at(*path, "properties").keys

    /** Schema object, by its path in the published document, against the class that writes it. */
    private val objects: Map<List<String>, Set<String>> =
        mapOf(
            emptyList<String>() to serialKeysOf(SessionExport.serializer()),
            listOf("properties", "timeZone") to serialKeysOf(RecordedTimeZone.serializer()),
            listOf("properties", "heartRate") to serialKeysOf(HrSessionSummary.serializer()),
            listOf("\$defs", "skippedSet") to serialKeysOf(SkippedSet.serializer()),
            listOf("\$defs", "exercise") to serialKeysOf(ExerciseExport.serializer()),
            listOf("\$defs", "set") to SetExportWireSerializer.wireKeys,
            listOf("\$defs", "setSensors") to serialKeysOf(SetSensorsExport.serializer()),
            listOf("\$defs", "geometry") to serialKeysOf(GeometryExport.serializer()),
            listOf("\$defs", "geometrySource") to serialKeysOf(GeometrySourceExport.serializer()),
            listOf("\$defs", "repMetrics") to serialKeysOf(RepMetricsExport.serializer()),
            listOf("\$defs", "set", "properties", "summary") to serialKeysOf(SetSummaryExport.serializer()),
            listOf("\$defs", "set", "properties", "tempoCompliance") to
                serialKeysOf(TempoComplianceExport.serializer()),
            listOf("\$defs", "set", "properties", "tempoCompliance", "properties", "byPhase", "items") to
                serialKeysOf(PhaseTallyExport.serializer()),
            listOf("\$defs", "set", "properties", "hr") to serialKeysOf(HrSetSummary.serializer()),
            listOf("\$defs", "set", "properties", "voiceCues", "items") to serialKeysOf(VoiceCue.serializer()),
        )

    @Test
    fun `every published export object declares exactly the keys its class writes`() {
        objects.forEach { (path, written) ->
            val name = path.joinToString("/").ifEmpty { "the root" }
            assertEquals(written, declared(*path.toTypedArray()), "$name and its Kotlin class disagree")
        }
    }

    /**
     * Every object in the published document that declares properties is in
     * the table above, so an object added to the schema without a row here
     * fails rather than going unpinned. Walked, not listed. The walk does not
     * descend into arrays: an object under an array-valued keyword (`anyOf`,
     * `allOf`, an `if`/`else` inside `allOf`) is a condition on an export
     * object, not an export object, and is not walked (L1 review round 1,
     * B-N4: `set/anyOf/2` and `geometry/allOf/0/if` and `/else` at this tree).
     */
    @Test
    fun `no published export object is missing from the table`() {
        val found = mutableSetOf<List<String>>()

        fun walk(obj: JsonObject, path: List<String>) {
            if ("properties" in obj) found += path
            obj.forEach { (key, value) ->
                if (value is JsonObject) walk(value, path + key)
            }
        }
        walk(schema, emptyList())
        // A property map's own entries are objects too, so `properties/<key>`
        // paths are only kept where that key's schema itself has properties.
        val objectPaths = found.filterNot { it.lastOrNull() == "properties" }.toSet()
        assertEquals(objects.keys, objectPaths, "an export object the table does not pin")
    }
}
