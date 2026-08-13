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
        val set = plan["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(PlanFile.VALID_SIDES, enumOf(set["side"]!!.jsonObject), "side values drifted")
    }

    @Test
    fun `every declared plan key is documented in the schema`() {
        val plan = schema("plan.schema.json")
        val exerciseKeys = plan["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!.jsonObject.keys
        // Kotlin property names, since the plan model uses no @SerialName renames.
        val declared =
            setOf(
                "exercise", "notes", "start", "concentric", "sensorInverted", "sensorOnStack",
                "travelRatio", "plane", "bodyweight", "optional", "sets",
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
}
