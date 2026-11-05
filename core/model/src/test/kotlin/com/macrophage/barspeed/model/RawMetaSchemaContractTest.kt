package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The raw archive's manifest schema, `docs/schemas/raw-meta.schema.json`,
 * against the documents and constants it must agree with (#326).
 *
 * `meta.json` is assembled as text by `RawExporter`, in `:core:data`, which
 * this module cannot see. What it CAN see is the two published documents and
 * the Kotlin vocabularies the writer turns into words, so these pins hold the
 * half of the contract a JVM-only build can reach. The other half -- that a
 * manifest the writer actually produced uses no key and no word this schema
 * does not declare -- is `RawExporterTest`'s, in the module that has the
 * writer.
 *
 * Its own file rather than a method on [SchemaContractTest], which sits on
 * detekt's `LargeClass` limit.
 */
class RawMetaSchemaContractTest {
    private fun document(name: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "$name is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString(),
    ).jsonObject

    private val raw = document("raw-meta.schema.json")
    private val session = document("session-export.schema.json")
    private val example = document("examples/raw-meta.example.json")

    private fun JsonObject.obj(vararg path: String): JsonObject =
        path.fold(this) { at, key -> checkNotNull(at[key]) { "no '$key' on the way to ${path.toList()}" }.jsonObject }

    private fun JsonObject.enumAt(vararg path: String): Set<String> =
        obj(*path).getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.propertyNames(): Set<String> = getValue("properties").jsonObject.keys

    private val rawSet = raw.obj("\$defs", "set")
    private val rawStream = raw.obj("\$defs", "sensorStream")
    private val sessionSet = session.obj("\$defs", "set", "properties")
    private val sessionSensors = session.obj("\$defs", "setSensors", "properties")

    /**
     * Where the manifest and session.json state one fact, they state it in one
     * vocabulary. The writer's own comments promise this -- "the SAME words
     * session.json carries, so a reader who opens the archive and one who opens
     * the analysis get one account of one set" -- and two published copies of
     * a word list are the drift class this repo keeps shipping.
     */
    @Test
    fun `the manifest's shared vocabularies are session json's words`() {
        val p = arrayOf("\$defs", "set", "properties")
        val pairs: List<Triple<String, Set<String>, Set<String>>> =
            listOf("durationEndedBy", "side", "plannedSide", "rpeScale", "limiter", "repsSource").map {
                Triple(it, raw.enumAt(*p, it), sessionSet.enumAt(it))
            } +
                listOf("startsWith", "concentric", "plane", "kind").map {
                    Triple(it, raw.enumAt(*p, it), session.enumAt("\$defs", "geometry", "properties", it))
                } +
                listOf(
                    Triple(
                        "concentricSource",
                        raw.enumAt(*p, "concentricSource"),
                        session.enumAt("\$defs", "sourceValue"),
                    ),
                    Triple("sensorsShortfall", raw.enumAt(*p, "sensorsShortfall"), sessionSensors.enumAt("shortfall")),
                    Triple(
                        "sensorRolesExpected",
                        raw.enumAt(*p, "sensorRolesExpected", "items"),
                        sessionSensors.enumAt("expected", "items"),
                    ),
                    Triple("analysedRole", raw.enumAt(*p, "analysedRole"), sessionSensors.enumAt("analysedRole")),
                    Triple(
                        "analysedRoleBasis",
                        raw.enumAt(*p, "analysedRoleBasis"),
                        sessionSensors.enumAt("analysedRoleBasis"),
                    ),
                    Triple(
                        "sensorsSilent roles",
                        raw.enumAt(*p, "sensorsSilent", "propertyNames"),
                        sessionSensors.enumAt("silent", "propertyNames"),
                    ),
                    Triple(
                        "sensorsSilent words",
                        raw.enumAt(*p, "sensorsSilent", "additionalProperties"),
                        sessionSensors.enumAt("silent", "additionalProperties"),
                    ),
                    Triple(
                        "sensorsSoleSilent",
                        raw.enumAt(*p, "sensorsSoleSilent"),
                        sessionSensors.enumAt("soleSilent"),
                    ),
                    Triple(
                        "sensors[].role",
                        raw.enumAt("\$defs", "sensorStream", "properties", "role"),
                        sessionSensors.enumAt("present", "items"),
                    ),
                )
        for ((key, manifest, analysis) in pairs) {
            assertEquals(analysis, manifest, "raw-meta '$key' and session-export disagree")
        }
    }

    /**
     * The words the writer derives from a Kotlin enum, pinned to that enum
     * rather than only to the other document, so a member added in Kotlin
     * reds here even where both documents forgot it together.
     */
    @Test
    fun `the manifest's derived words are the writer's enums`() {
        val p = arrayOf("\$defs", "set", "properties")
        assertEquals(StartPhase.entries.map { it.name.lowercase() }.toSet(), raw.enumAt(*p, "startsWith"))
        assertEquals(ExerciseKind.entries.map { it.name.lowercase() }.toSet(), raw.enumAt(*p, "kind"))
        assertEquals(GeometrySource.entries.map { it.name.lowercase() }.toSet(), raw.enumAt(*p, "concentricSource"))
        val roles = SensorRole.entries.map(SensorCapturePolicy::wireOf).toSet()
        assertEquals(roles, raw.enumAt(*p, "analysedRole"), "analysedRole")
        assertEquals(roles, raw.enumAt(*p, "sensorRolesExpected", "items"), "sensorRolesExpected")
        assertEquals(roles, raw.enumAt(*p, "sensorsSilent", "propertyNames"), "sensorsSilent keys")
        assertEquals(roles, raw.enumAt("\$defs", "sensorStream", "properties", "role"), "sensors[].role")
        assertEquals(
            DualShortfall.entries.map(SensorCapturePolicy::shortfallToWire).toSet(),
            raw.enumAt(*p, "sensorsShortfall"),
        )
        // DELIVERING is the one member that says the unit was feeding the app,
        // and the manifest writes a silence word only for a unit that was not.
        val silent = (ArmedDelivery.entries - ArmedDelivery.DELIVERING).map(ArmedSilencePolicy::wireOf).toSet()
        assertEquals(silent, raw.enumAt(*p, "sensorsSilent", "additionalProperties"), "sensorsSilent words")
        assertEquals(silent, raw.enumAt(*p, "sensorsSoleSilent"), "sensorsSoleSilent")
        assertEquals(AnalysedRoleBasis.entries.map { it.published }.toSet(), raw.enumAt(*p, "analysedRoleBasis"))
    }

    /**
     * The writer publishes every non-null key of [SetPrescriptionExport] into
     * each set descriptor by iterating the serialized object, so a key added
     * to that class reaches the manifest with no edit to the writer -- and
     * with this schema closed, an undeclared one would fail every validation.
     */
    @Test
    fun `every prescription key the writer copies in is declared`() {
        val d = SetPrescriptionExport.serializer().descriptor
        val keys = (0 until d.elementsCount).map(d::getElementName).toSet()
        assertEquals(emptySet(), keys - rawSet.propertyNames(), "SetPrescriptionExport keys the schema omits")
    }

    /**
     * Closed at every level. An open object would let a key the writer adds
     * pass validation undeclared, which is the gap this schema was published
     * to close (#326), and would make `RawExporterTest`'s coverage pin the
     * only thing standing between the writer and an undocumented key.
     */
    @Test
    fun `every object the schema declares is closed`() {
        for ((name, obj) in listOf("top level" to raw, "set" to rawSet, "sensorStream" to rawStream)) {
            assertEquals(JsonPrimitive(false), obj["additionalProperties"], "$name is not closed")
        }
    }

    /**
     * The example is the schema's worked case: CI's ajv step validates it, and
     * a key the example never carries is a key that step never checks. So
     * across its sets the example carries every key the schema declares, at
     * every level -- an exact equality, so a key added to the schema without
     * an example of it reds here, and one added to the example without a
     * declaration reds ajv.
     */
    @Test
    fun `the example carries every key the schema declares`() {
        assertEquals(raw.propertyNames(), example.keys, "top-level keys")
        val sets = example.getValue("sets").jsonArray.map(JsonElement::jsonObject)
        assertEquals(rawSet.propertyNames(), sets.flatMap { it.keys }.toSet(), "set descriptor keys")
        val streams = sets.flatMap { s -> s["sensors"]?.jsonArray.orEmpty().map(JsonElement::jsonObject) }
        assertTrue(streams.isNotEmpty(), "the example carries no sensors entry at all")
        assertEquals(rawStream.propertyNames(), streams.flatMap { it.keys }.toSet(), "sensors entry keys")
    }
}
