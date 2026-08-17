package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The published JSON Schemas are the contract a plan-generating LLM works
 * from, and they drifted out of step with the code once already: the schema
 * still allowed only the old `start` values while the app's own prompt told
 * the model to emit the new ones, so every plan written by following the
 * app's instructions failed the app's own schema. These tests pin the two
 * documents to the constants they describe.
 */
class SchemaContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun enumOf(obj: kotlinx.serialization.json.JsonObject) =
        obj["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `plan schema accepts exactly the versions and vocabularies the code does`() {
        val plan = schema("plan.schema.json")
        val props = plan["properties"]!!.jsonObject
        assertEquals(
            PlanFile.SUPPORTED_SCHEMA_VERSIONS,
            enumOf(props["schemaVersion"]!!.jsonObject),
            "schema versions drifted from PlanFile.SUPPORTED_SCHEMA_VERSIONS",
        )
        val exercise = plan["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(PlanFile.VALID_STARTS, enumOf(exercise["start"]!!.jsonObject), "start values drifted")
        assertEquals(
            PlanFile.VALID_CONCENTRIC,
            enumOf(exercise["concentric"]!!.jsonObject),
            "concentric values drifted",
        )
        assertEquals(PlanFile.VALID_PLANES, enumOf(exercise["plane"]!!.jsonObject), "plane values drifted")
        assertEquals(PlanFile.VALID_KINDS, enumOf(exercise["kind"]!!.jsonObject), "kind values drifted")
        val set = plan["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(PlanFile.VALID_SIDES, enumOf(set["side"]!!.jsonObject), "side values drifted")
    }

    @Test
    fun `the declarable kinds are exactly the kinds the app can track`() {
        // Every other plan vocabulary is a bare set of strings with nothing on
        // the Kotlin side to compare against, so the schema is the only pin.
        // kind is 1:1 with an enum, so it can be pinned in both directions:
        // adding an ExerciseKind without adding it here would ship a kind no
        // plan can ask for, and the schema assertion above would not notice.
        assertEquals(
            ExerciseKind.entries.map { it.name.lowercase() }.toSet(),
            PlanFile.VALID_KINDS,
            "VALID_KINDS and ExerciseKind disagree",
        )
    }

    @Test
    fun `every declared plan key is documented in the schema`() {
        val plan = schema("plan.schema.json")
        val exerciseKeys = plan["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!.jsonObject.keys
        // Wire names. On [PlanExerciseDef] they happen to equal the Kotlin
        // property names; on [PlanSetDef] they do not — @SerialName renames six
        // of its ten — so read this as the wire contract, and do not assume the
        // two coincide anywhere else.
        //
        // Kept as a literal rather than derived from the serializer descriptor
        // on purpose. A descriptor-derived assertion follows a @SerialName
        // rename silently, which is exactly the drift this class exists to
        // catch: rename a key in the data class and the schema together and
        // every plan already written in the wild breaks with the test green.
        val declared =
            setOf(
                "exercise", "notes", "start", "concentric", "sensorInverted", "sensorOnStack",
                "travelRatio", "plane", "bodyweight", "optional", "kind", "sets",
            )
        assertEquals(declared, exerciseKeys, "PlanExerciseDef and the schema disagree on exercise keys")
    }

    @Test
    fun `session export schema allows the version the exporter writes`() {
        val export = schema("session-export.schema.json")
        val versions = enumOf(export["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject)
        assertEquals(SessionExport.SUPPORTED_SCHEMA_VERSIONS, versions, "export versions drifted")
        assertTrue(
            SessionExport.SCHEMA_VERSION in versions,
            "the exporter writes ${SessionExport.SCHEMA_VERSION}, which its own schema rejects",
        )
    }

    /**
     * The published example is an example of what the exporter actually emits.
     *
     * Found by mutation testing, not by reading: reverting
     * [SessionExport.SCHEMA_VERSION] from 1.3 to 1.2 left the entire suite
     * green. The version assertion above only requires that the version the
     * exporter writes is SOMEWHERE in the supported set, and an older version
     * is always still in it — so the exporter could stamp 1.2 on a document
     * carrying a key that only exists in 1.3, and a consumer reading the
     * version to decide how to interpret the payload would be told the wrong
     * answer by the one field whose whole job is to be right about that.
     *
     * Pinned through the example rather than as a bare literal so the check has
     * a second job: the example is the document a reader is pointed at, and one
     * still advertising a version the code no longer writes is drift of exactly
     * the kind this class exists to catch. Bumping the version now requires
     * updating the example in the same change, which was already the rule and
     * was enforced by nothing.
     */
    @Test
    fun `the published example declares the version the exporter writes`() {
        val example = schema("examples/session-export.example.json")
        assertEquals(
            SessionExport.SCHEMA_VERSION,
            example["schemaVersion"]!!.jsonPrimitive.content,
            "the published example and SessionExport.SCHEMA_VERSION disagree",
        )
    }

    /**
     * The session export requires exactly three keys of a reader, and no more.
     *
     * Every version of this contract so far has claimed to be additive -- 1.2's
     * own description says a 1.1 reader "works unchanged". That claim is only
     * checkable against something, and this is it: a key added to `required`
     * makes every export written by an older app version invalid, and a key
     * REMOVED from it silently relaxes what a consumer may assume. Neither is
     * visible in a diff of the properties block, which is where the eye goes.
     *
     * Deliberately not derived from [SessionExport]'s non-nullable fields. A
     * descriptor-derived assertion would follow a Kotlin nullability change
     * silently, which is the drift this pin exists to catch.
     */
    @Test
    fun `the session export requires exactly the three keys it requires today`() {
        val required = schema("session-export.schema.json")["required"]!!
            .jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("schemaVersion", "startedAt", "exercises"), required, "export required keys drifted")
    }

    /**
     * The exported geometry speaks the plan's vocabulary, and must go on doing
     * so. A consumer holding both schemas reads `"concentric": "down"` the same
     * way in each; a divergence would make the export's own declaration
     * unreadable against the plan that produced it.
     */
    @Test
    fun `the exported geometry uses exactly the vocabularies the plan declares in`() {
        val geometry = schema("session-export.schema.json")["\$defs"]!!.jsonObject["geometry"]!!
            .jsonObject["properties"]!!.jsonObject
        assertEquals(
            PlanFile.VALID_CONCENTRIC,
            enumOf(geometry["concentric"]!!.jsonObject),
            "the export and the plan disagree on drive-direction values",
        )
        assertEquals(
            PlanFile.VALID_PLANES,
            enumOf(geometry["plane"]!!.jsonObject),
            "the export and the plan disagree on plane values",
        )
        assertEquals(
            PlanFile.VALID_KINDS,
            enumOf(geometry["kind"]!!.jsonObject),
            "the export and the plan disagree on kind values",
        )
        assertEquals(
            SessionExport.VALID_STARTS_WITH,
            enumOf(geometry["startsWith"]!!.jsonObject),
            "startsWith values drifted",
        )
    }

    /**
     * The start phase and the provenance vocabulary are both 1:1 with a Kotlin
     * enum, so both are pinned in both directions. Adding a [StartPhase] or a
     * [GeometrySource] without publishing it would ship a value the schema
     * rejects, and the assertion above would not notice.
     */
    @Test
    fun `the published start phases and geometry sources are exactly the enums`() {
        assertEquals(
            StartPhase.entries.map { it.name.lowercase() }.toSet(),
            SessionExport.VALID_STARTS_WITH,
            "VALID_STARTS_WITH and StartPhase disagree",
        )
        assertEquals(
            GeometrySource.entries.map { it.name.lowercase() }.toSet(),
            SessionExport.VALID_GEOMETRY_SOURCES,
            "VALID_GEOMETRY_SOURCES and GeometrySource disagree",
        )
        val sourceValue = schema("session-export.schema.json")["\$defs"]!!.jsonObject["sourceValue"]!!.jsonObject
        assertEquals(
            SessionExport.VALID_GEOMETRY_SOURCES,
            enumOf(sourceValue),
            "geometry source values drifted",
        )
    }

    /**
     * Present-or-absent as a unit. The exporter writes JSON with
     * `encodeDefaults = false`, so any geometry field that acquired a Kotlin
     * default would silently vanish from the wire and read as "not stated" —
     * which is the defect this object was added to remove. Marking all nine
     * required is what makes a dropped key a schema violation rather than a
     * quiet reinterpretation.
     */
    @Test
    fun `every geometry key is required, so a dropped false cannot read as unstated`() {
        val geometry = schema("session-export.schema.json")["\$defs"]!!.jsonObject["geometry"]!!.jsonObject
        val required = geometry["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(geometry["properties"]!!.jsonObject.keys, required, "a geometry key is optional")
        val source = schema("session-export.schema.json")["\$defs"]!!.jsonObject["geometrySource"]!!.jsonObject
        val sourceRequired = source["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(source["properties"]!!.jsonObject.keys, sourceRequired, "a geometry source key is optional")
        // The three that carry no provenance, named so their absence is a
        // decision on the record rather than an oversight: they are
        // non-nullable booleans in the plan format, so a declared false and an
        // omitted key are the same value.
        assertEquals(
            setOf("sensorOnStack", "sensorInverted", "bodyweight"),
            geometry["properties"]!!.jsonObject.keys - sourceRequired - setOf("source"),
            "the set of values carrying no provenance changed",
        )
    }
}
