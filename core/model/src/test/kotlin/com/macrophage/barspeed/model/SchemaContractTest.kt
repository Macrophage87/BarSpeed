package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    /**
     * [PlanFile.SCHEMA_VERSION] is the version the app ASKS FOR rather than
     * one it writes. No production code reads it; its only consumer in the
     * repository is `GuidePromptContractTest`, which pins it into
     * `GuideScreen.kt`'s PLAN_PROMPT -- the text the COPY PLAN PROMPT button
     * puts on the lifter's clipboard. So a version the app advertises but will
     * not accept means every plan written by following the app's own
     * instructions is refused at the import gate.
     *
     * Found by mutation testing, as `the published example declares the
     * version the exporter writes` was: raising SCHEMA_VERSION to 1.5 and
     * moving the prompt's prose and skeleton to 1.5, while leaving
     * SUPPORTED_SCHEMA_VERSIONS and the published schema at 1.4, left the
     * whole suite green.
     *
     * Both halves are asserted because they refuse the plan at different
     * moments: a version absent from the published enum is rejected by a
     * generator validating against the contract it was pointed at, and one
     * absent from SUPPORTED_SCHEMA_VERSIONS is rejected by [PlanFile.validate]
     * after the file reaches the app.
     */
    @Test
    fun `plan schema allows the version the plan prompt asks for`() {
        val props = schema("plan.schema.json")["properties"]!!.jsonObject
        val versions = enumOf(props["schemaVersion"]!!.jsonObject)
        assertTrue(
            PlanFile.SCHEMA_VERSION in versions,
            "the prompt asks for ${PlanFile.SCHEMA_VERSION}, which the published plan schema rejects",
        )
        assertTrue(
            PlanFile.SCHEMA_VERSION in PlanFile.SUPPORTED_SCHEMA_VERSIONS,
            "the prompt asks for ${PlanFile.SCHEMA_VERSION}, which PlanFile.validate() rejects",
        )
    }

    /**
     * The published example is the document a plan writer is pointed at, and
     * it declares a version. One advertising a version the prompt no longer
     * asks for teaches the reader a contract the app has moved off.
     *
     * Found by mutation testing: reverting the example to "1.1" left the suite
     * green. Nothing else reads the example's version.
     * `ShippedPlanExampleTest` decodes the example and asserts
     * [PlanFile.validate] returns no errors, but that passes for ANY still
     * supported version, so an example left a bump behind cannot fail it.
     */
    @Test
    fun `the published plan example declares the version the prompt asks for`() {
        val example = schema("examples/plan.example.json")
        assertEquals(
            PlanFile.SCHEMA_VERSION,
            example["schemaVersion"]!!.jsonPrimitive.content,
            "the published plan example and PlanFile.SCHEMA_VERSION disagree",
        )
    }

    /**
     * The exercise-level `bodyweight` description states, in so many words,
     * that the set's own load "may be NEGATIVE for band or machine
     * assistance" — and [PlanFile.validate] already accepts a negative
     * load_kg/load_lb whenever the exercise is bodyweight. An unconditional
     * `minimum: 0` on the set's load properties contradicts that, so a plan
     * generator that conforms to the schema exactly cannot write the one
     * document its own description says is legal (#39).
     */
    @Test
    fun `a set's load has no floor, so bodyweight assistance can be declared negative`() {
        val set = schema("plan.schema.json")["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(
            "minimum" !in set["load_kg"]!!.jsonObject,
            "load_kg still forbids the negative load bodyweight assistance requires",
        )
        assertTrue(
            "minimum" !in set["load_lb"]!!.jsonObject,
            "load_lb still forbids the negative load bodyweight assistance requires",
        )
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
        // Wire names. They used to equal the Kotlin property names on
        // [PlanExerciseDef] and no longer do: prep_s is that type's first
        // @SerialName, alongside the six of [PlanSetDef]'s ten that were already
        // renamed. Read this as the wire contract and assume nothing about the
        // Kotlin spelling of any of it.
        //
        // Kept as a literal rather than derived from the serializer descriptor
        // on purpose. A descriptor-derived assertion follows a @SerialName
        // rename silently, which is exactly the drift this class exists to
        // catch: rename a key in the data class and the schema together and
        // every plan already written in the wild breaks with the test green.
        val declared =
            setOf(
                "exercise", "notes", "start", "concentric", "sensorInverted", "sensorOnStack",
                "travelRatio", "plane", "bodyweight", "implementCount", "optional",
                "kind", "prep_s", "sets",
            )
        assertEquals(declared, exerciseKeys, "PlanExerciseDef and the schema disagree on exercise keys")
    }

    /**
     * The floor the published schema advertises and the floor
     * [PlanFile.validate] enforces are the same floor.
     *
     * Modelled on `a set's load has no floor, so bodyweight assistance can be
     * declared negative`, and it guards the same failure shape from the other
     * direction: a bound in one document that the other contradicts. A count
     * below one reaches a division, so the two must agree -- a schema that
     * accepted 0 would publish a document the app refuses, and one that
     * refused 1 would forbid the default.
     */
    @Test
    fun `the schema's implement floor is the one the app validates`() {
        val exercise = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!
            .jsonObject["properties"]!!.jsonObject
        val implementCount = exercise["implementCount"]!!.jsonObject
        assertEquals("integer", implementCount["type"]!!.jsonPrimitive.content, "implementCount is not an integer")
        assertEquals(
            1,
            implementCount["minimum"]!!.jsonPrimitive.int,
            "the published implement floor drifted from the one PlanFile.validate enforces",
        )
    }

    /**
     * The prep pair is declared in the published export schema, and both keys
     * carry a description.
     *
     * `$defs.set` is `additionalProperties: false`, so an undeclared key does
     * not merely go unmentioned -- it makes every export carrying it INVALID
     * against the contract its own consumer was pointed at.
     *
     * A description each, because the omission has a number on it. `rest_s` is
     * the one planned field in this document with neither a `planned` prefix nor
     * a description, and a reader takes it for an observation; that is issue
     * #76.
     */
    @Test
    fun `the published export declares both prep keys, each described`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        listOf("plannedPrep_s", "prep_s").forEach { key ->
            val property = assertNotNull(set[key], "the published export schema does not declare $key")
            val description = property.jsonObject["description"]?.jsonPrimitive?.content
            assertTrue(
                description?.isNotBlank() == true,
                "$key is declared with no description, which is the shape of issue #76",
            )
        }
    }

    /**
     * The published example carries the prep pair.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage. An example carrying none of the
     * new contract passes a schema that declares it and a schema that does not
     * -- so the keys could be wrong in every detail while the step stayed green.
     */
    @Test
    fun `the published export example carries the prep pair`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        assertTrue(
            sets.any { "plannedPrep_s" in it.jsonObject && "prep_s" in it.jsonObject },
            "no set in the published example carries the prep pair, so ajv never validates it",
        )
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
     * The vocabulary `velocityLossBasis` is published in, pinned in both
     * directions the way the geometry vocabularies are.
     *
     * The names are produced by `VelocityLoss` in `:core:dsp`, which this
     * module cannot see. `VelocityLossTest` pins that side against
     * [SessionExport.VALID_VELOCITY_LOSS_BASES]; this pins the same constant
     * against the published schema. Neither hop on its own would catch a
     * rename -- one would leave the schema declaring a value nothing emits,
     * the other a value the schema rejects.
     */
    @Test
    fun `the published velocity-loss bases are exactly the ones the exporter declares`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        assertEquals(
            SessionExport.VALID_VELOCITY_LOSS_BASES,
            enumOf(set["velocityLossBasis"]!!.jsonObject),
            "velocityLossBasis values drifted",
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
