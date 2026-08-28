package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The published `prep_s` description no longer says the key applies only to
     * sets carrying a tempo.
     *
     * That sentence was true when it was written and this change makes it
     * false, and the published schema is the document a plan-writing model is
     * pointed at -- a commit body is read once, a description is read every
     * week. What this pins is narrow and said so: it cannot check that the
     * description is RIGHT, only that the one claim this change falsified is
     * gone and that the case it was wrong about is named.
     */
    @Test
    fun `the published prep_s description does not tie the key to a tempo`() {
        val exercise = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!.jsonObject
        val description =
            exercise["properties"]!!.jsonObject["prep_s"]!!.jsonObject["description"]!!.jsonPrimitive.content

        assertFalse(
            "ONLY APPLIES TO SETS CARRYING A tempo" in description,
            "the published prep_s description still restricts the key to tempo'd sets: $description",
        )
        assertTrue(
            "a hold or a carry" in description,
            "the published prep_s description never names the timed case: $description",
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
                "exercise", "notes", "description", "additional_notes", "start", "concentric",
                "sensorInverted", "sensorOnStack", "travelRatio", "plane", "bodyweight",
                "implementCount", "optional", "kind", "prep_s", "sensors", "sets",
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
     * The cap the published schema advertises and the cap [PlanFile.validate]
     * enforces are the same cap.
     *
     * Modelled on `the schema's implement floor is the one the app validates`,
     * and it guards the same shape: a bound in one document the other
     * contradicts. A generator conforming to the published `maxLength` must not
     * then be refused at the import gate, and a document the gate accepts must
     * not be one the schema calls invalid.
     *
     * The other half is that `additional_notes` carries NO cap. It is where the
     * overflow goes; a limit on it would leave the plan's author nowhere to put
     * the paragraph and is how text starts getting deleted to fit.
     */
    @Test
    fun `the published description cap is the one the app validates, and the overflow key has none`() {
        val exercise = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!
            .jsonObject["properties"]!!.jsonObject
        val description =
            assertNotNull(exercise["description"], "the published schema does not declare description")
                .jsonObject
        assertEquals("string", description["type"]!!.jsonPrimitive.content, "description is not a string")
        assertEquals(
            PlanFile.DESCRIPTION_MAX_CHARS,
            description["maxLength"]!!.jsonPrimitive.int,
            "the published description cap drifted from the one PlanFile.validate enforces",
        )
        val additional =
            assertNotNull(exercise["additional_notes"], "the published schema does not declare additional_notes")
                .jsonObject
        assertEquals("string", additional["type"]!!.jsonPrimitive.content, "additional_notes is not a string")
        assertTrue(
            "maxLength" !in additional,
            "additional_notes carries a cap, so the overflow from description has nowhere to go",
        )
    }

    /**
     * The published `notes` description says the key still renders, and where.
     *
     * The same shape as the `prep_s` pin below: a sentence in a published
     * schema is read every week, and this one has to answer the question every
     * author of an already-staged plan will have — whether their text is about
     * to disappear behind a tap. It does not, and the document a generator is
     * pointed at has to say so.
     *
     * Narrow, and said so: this cannot check the description is right, only
     * that it names the newer key and does not tell the reader the older one is
     * gone.
     */
    @Test
    fun `the published notes description says what happens to it under the split`() {
        val exercise = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!
            .jsonObject["properties"]!!.jsonObject
        val notes = exercise["notes"]!!.jsonObject["description"]!!.jsonPrimitive.content

        assertTrue(
            "description" in notes,
            "the published notes description never mentions the key that replaces it: $notes",
        )
        assertTrue(
            "additional_notes" in notes,
            "the published notes description never says where a long cue goes now: $notes",
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
     * A description each. `rest_s` is the one planned field in this document
     * whose name does not say it is planned, and a reader takes it for an
     * observation; that is issue #76.
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

    /**
     * `repMarks` is declared, typed as instants, and described (#158).
     *
     * `$defs.set` is `additionalProperties: false`, so a key the exporter
     * writes and the schema does not declare makes every export carrying it
     * INVALID against the contract its own reader was pointed at -- the key
     * would not merely go unmentioned. The item type is asserted because the
     * marks are epoch milliseconds and a schema declaring them as strings, or
     * as objects the way `voiceCues` is, would send a reader looking for a
     * field that is not there.
     */
    @Test
    fun `the published export declares repMarks as an array of instants, described`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        val marks = assertNotNull(set["repMarks"], "the published export schema does not declare repMarks")
            .jsonObject
        assertEquals("array", marks["type"]!!.jsonPrimitive.content, "repMarks is not published as an array")
        assertEquals(
            "integer",
            marks["items"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            "a rep mark is not published as a whole number of milliseconds",
        )
        assertTrue(
            marks["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "repMarks is declared with no description, which is the shape of issue #76",
        )
    }

    /**
     * The 1.13 version-log entry names both of its halves.
     *
     * 1.13 was minted for `duration_s`'s changed meaning and is unreleased, so
     * `repMarks` extends it rather than minting 1.14 -- which leaves one entry
     * carrying two changes of different kinds, and a reader who is told only
     * about the additive half would take the whole version as safe to ignore.
     * Narrow, and said so: this cannot check the entry is RIGHT, only that the
     * additive half is not silently absent from the one place a consumer looks
     * for it.
     */
    @Test
    fun `the 1_13 version log names the rep marks as well as the duration change`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "repMarks" in description,
            "the version log never mentions repMarks, so 1.13 publishes a key it does not explain",
        )
        assertTrue(
            "duration_s" in description,
            "the version log lost the duration_s half of 1.13",
        )
    }

    /**
     * The published example carries a set's rep marks.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage. An example carrying none of
     * the new key passes a schema that declares it and a schema that does not,
     * so the declaration could be wrong in every detail while the step stayed
     * green -- the same reasoning as the prep-pair example above.
     */
    @Test
    fun `the published export example carries a set's rep marks`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val marks =
            assertNotNull(
                sets.firstNotNullOfOrNull { it.jsonObject["repMarks"] },
                "no set in the published example carries repMarks, so ajv never validates it",
            )
        assertTrue(marks.jsonArray.isNotEmpty(), "the example's repMarks array is empty, which nothing emits")
    }

    // ---- the sensor declaration, issue #156 ---------------------------------

    /**
     * The published `sensors` block declares the four things it declares, each
     * described, and refuses anything else.
     *
     * `$defs.set` is `additionalProperties: false`, so an undeclared key would
     * make every dual export INVALID against the contract its consumer was
     * pointed at -- which is why the block is declared here before anything
     * emits it, rather than in the commit that starts writing it.
     */
    @Test
    fun `the published export declares the sensor block, described, closed and required where it must be`() {
        val defs = schema("session-export.schema.json")["\$defs"]!!.jsonObject
        val set = defs["set"]!!.jsonObject["properties"]!!.jsonObject
        assertNotNull(set["sensors"], "the published export schema does not declare a set's sensors")
        val sensors = assertNotNull(defs["setSensors"], "the sensors block has no \$defs entry").jsonObject
        assertEquals(
            false,
            sensors["additionalProperties"]!!.jsonPrimitive.content.toBoolean(),
            "the sensors block accepts undeclared keys, so a typo would validate",
        )
        assertEquals(
            setOf("plannedCount", "count", "expected", "present", "analysedRole"),
            sensors["properties"]!!.jsonObject.keys,
            "SetSensorsExport and the published sensors block disagree on keys",
        )
        assertEquals(
            listOf("plannedCount", "count", "expected", "present"),
            sensors["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the counts and both role lists must be required; only the analysed role may be absent",
        )
        sensors["properties"]!!.jsonObject.forEach { (key, value) ->
            assertTrue(
                value.jsonObject["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "the published sensors.$key carries no description, which is the shape of issue #76",
            )
        }
    }

    /**
     * An empty role list reaches the wire instead of being dropped as a
     * default.
     *
     * The exporter serializes with `encodeDefaults = false`, so a list
     * defaulted to empty would vanish from the document exactly when it is
     * empty -- and an absent key reads as "not stated" where an empty one
     * means "no role was armed" or "nothing arrived". Those are the two most
     * informative states this object has. `GeometryExport` carries the same
     * rule for the same reason.
     *
     * The two settings that decide it are mirrored here rather than shared,
     * because the exporter that owns them lives in `:core:data` and this
     * module cannot see it -- the dependency runs the other way, the same
     * arrangement `VALID_VELOCITY_LOSS_BASES` uses.
     */
    @Test
    fun `an empty role list is written out rather than dropped as a default`() {
        val wire =
            Json {
                encodeDefaults = false
                explicitNulls = false
            }
        val text =
            wire.encodeToString(
                SetSensorsExport.serializer(),
                SetSensorsExport(plannedCount = 2, count = 1, expected = emptyList(), present = emptyList()),
            )

        assertTrue("\"expected\"" in text, "an empty expected list was dropped from the wire: $text")
        assertTrue("\"present\"" in text, "an empty present list was dropped from the wire: $text")
        assertFalse("analysedRole" in text, "a null analysed role reached the wire: $text")
    }

    /**
     * The role vocabulary is exactly the enum, everywhere it appears.
     *
     * Pinned in BOTH directions, the arrangement [PlanFile.VALID_KINDS] uses:
     * against the published schema so a document cannot declare a role the app
     * refuses, and against [SensorRole] so the app cannot record one the
     * published schema refuses. Three sites in the schema state the same set,
     * and a role added to two of them is a role a reader accepts in one field
     * and rejects in another.
     */
    @Test
    fun `the published sensor roles are exactly the roles the app records`() {
        val sensors = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!
            .jsonObject["properties"]!!.jsonObject
        assertEquals(
            SensorRole.entries.map { it.name.lowercase() }.toSet(),
            SessionExport.VALID_SENSOR_ROLES,
            "VALID_SENSOR_ROLES drifted from the SensorRole enum",
        )
        assertEquals(
            SessionExport.VALID_SENSOR_ROLES,
            enumOf(sensors["expected"]!!.jsonObject["items"]!!.jsonObject),
            "the published expected-role vocabulary drifted",
        )
        assertEquals(
            SessionExport.VALID_SENSOR_ROLES,
            enumOf(sensors["present"]!!.jsonObject["items"]!!.jsonObject),
            "the published present-role vocabulary drifted",
        )
        assertEquals(
            SessionExport.VALID_SENSOR_ROLES,
            enumOf(sensors["analysedRole"]!!.jsonObject),
            "the published analysed-role vocabulary drifted",
        )
    }

    /**
     * The role vocabulary is NOT the side vocabulary, and the schema says why.
     *
     * The owner's ruling is that a role is the identity of a physical unit and
     * carries no anatomical claim -- units get flipped and are corrected in
     * post-processing. A schema that let `a` and `left` mean the same thing
     * would put that claim back in the document by the back door, and a reader
     * would believe a per-limb measurement exists.
     */
    @Test
    fun `a sensor role is not a side, in the vocabulary and in the prose`() {
        val description = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            SessionExport.VALID_SENSOR_ROLES.none { it in PlanFile.VALID_SIDES },
            "a sensor role and a worked side share a value, so one word means two facts",
        )
        assertTrue(
            "side" in description,
            "the published sensors block never warns a reader off reading a role as a side",
        )
    }

    /**
     * The 1.13 version-log entry names its third change too.
     *
     * Same reasoning as the rep-marks entry above: 1.13 is unreleased, so this
     * extends it rather than minting 1.14, and one entry now carries three
     * changes. A consumer reading the log to decide whether to re-check a
     * reader must be told all three are in there.
     */
    @Test
    fun `the 1_13 version log names the sensor declaration as its third change`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "sensors" in description,
            "the version log never mentions sensors, so 1.13 publishes a key it does not explain",
        )
        assertTrue(
            "imu-a.csv" in description,
            "the version log never says how the raw archive names a role-tagged stream",
        )
    }

    /**
     * The published example carries a dual set's sensors block.
     *
     * `ci.yml` validates this example with ajv and that is the schema half's
     * only automated coverage; an example carrying none of the new key passes
     * a schema that declares it and one that does not. The same reasoning as
     * the prep-pair and rep-mark examples.
     */
    @Test
    fun `the published export example carries a dual set's sensor block`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val sensors =
            assertNotNull(
                sets.firstNotNullOfOrNull { it.jsonObject["sensors"] },
                "no set in the published example carries sensors, so ajv never validates the block",
            ).jsonObject
        assertEquals(2, sensors["count"]!!.jsonPrimitive.int, "the example's sensors block is not a dual set")
        assertEquals(
            listOf("a", "b"),
            sensors["expected"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the example does not exercise both roles",
        )
    }

    /**
     * The plan's sensor bounds are the bounds [PlanFile.validate] enforces, at
     * both levels it can be declared at.
     *
     * The same failure shape `the schema's implement floor is the one the app
     * validates` guards, and with an upper bound as well because there is one:
     * the app runs one collector per stream and knows two roles, so a 3 has no
     * client, no journal file and no column value.
     */
    @Test
    fun `the plan's sensor bounds are the ones the app validates, on the exercise and on the set`() {
        val defs = schema("plan.schema.json")["\$defs"]!!.jsonObject
        for (level in listOf("exercise", "set")) {
            val sensors =
                assertNotNull(
                    defs[level]!!.jsonObject["properties"]!!.jsonObject["sensors"],
                    "the published plan schema does not declare sensors on a $level",
                ).jsonObject
            assertEquals("integer", sensors["type"]!!.jsonPrimitive.content, "$level sensors is not an integer")
            assertEquals(
                SensorCapturePolicy.MIN_COUNT,
                sensors["minimum"]!!.jsonPrimitive.int,
                "the published $level sensor floor drifted from the one PlanFile.validate enforces",
            )
            assertEquals(
                SensorCapturePolicy.MAX_COUNT,
                sensors["maximum"]!!.jsonPrimitive.int,
                "the published $level sensor ceiling drifted from the one PlanFile.validate enforces",
            )
        }
    }

    /**
     * The plan's 1.8 entry records that the sensors declaration landed under
     * it.
     *
     * 1.8 was minted for the coaching-text split and is unreleased, so this
     * extends it. The entry already ANTICIPATED the key by name before it
     * existed; an anticipation left standing after the fact reads as a plan,
     * not as a record, so what is pinned here is that the entry says it has
     * landed and states the additive terms.
     */
    @Test
    fun `the plan's 1_8 entry records the sensors declaration as landed and additive`() {
        val description = schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("`sensors`" in description, "the plan version log never names the sensors key")
        assertTrue(
            "an omitted `sensors` is one sensor" in description,
            "the plan version log never states what an omitted sensors declaration means",
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

    // ---- what the session level says today, ahead of issue #159 -------------

    /**
     * The session-level key set of the published export, stated as a literal.
     *
     * The root object IS `additionalProperties: false`, so an undeclared
     * session-level key fails ajv exactly as an undeclared `$defs.set` key
     * does. What this pin catches is narrower: it fails when the schema's
     * own session-level key list changes, so a declaration cannot be added
     * or removed here without this literal moving. It does not compare the
     * schema against what the exporter writes; the exact-set pin on that
     * side is `SessionExporterTest`'s `the export root states exactly the
     * keys it states today`, in `:core:data`.
     *
     * A literal for the reason `every declared plan key is documented in the
     * schema` keeps one: a set derived from the serializer descriptor follows
     * a rename silently, and following it silently is the drift this class
     * exists to catch.
     */
    @Test
    fun `the published export declares exactly the session-level keys it declares today`() {
        val keys = schema("session-export.schema.json")["properties"]!!.jsonObject.keys
        assertEquals(
            setOf(
                "schemaVersion", "startedAt", "endedAt", "timeZone", "planRef", "notes",
                "sessionRpe", "heartRate", "exercises",
            ),
            keys,
            "a session-level key was added or removed without moving this pin",
        )
    }

    /**
     * The published session rating is an integer bounded by the scale
     * [SessionRpe] owns (#159).
     *
     * Bounds pinned against the Kotlin constants rather than against literals,
     * the arrangement `the schema's implement floor is the one the app
     * validates` uses: the control the lifter taps is built from
     * [SessionRpe.VALUES], so a scale widened in Kotlin and left alone here
     * would ship a control offering a number the published contract rejects.
     */
    @Test
    fun `the published session rating is bounded by the scale the app offers`() {
        val props = schema("session-export.schema.json")["properties"]!!.jsonObject
        val rating =
            assertNotNull(props["sessionRpe"], "the published export schema does not declare sessionRpe").jsonObject
        assertEquals("integer", rating["type"]!!.jsonPrimitive.content, "sessionRpe is not published as an integer")
        assertEquals(
            SessionRpe.MIN,
            rating["minimum"]!!.jsonPrimitive.int,
            "the published session-rating floor drifted",
        )
        assertEquals(
            SessionRpe.MAX,
            rating["maximum"]!!.jsonPrimitive.int,
            "the published session-rating ceiling drifted",
        )
    }

    /**
     * Both published descriptions name their own scale and deny the other's.
     *
     * The owner's ruling, and the whole reason this key needed a design round:
     * the app now carries two things called RPE over overlapping published
     * ranges -- a set's is reps-in-reserve on a 6-to-10 grid, a session's is
     * 1-to-10 overall -- and a reader has nothing but these descriptions to
     * tell two integers apart. A reader that averages them is the #139/#151
     * defect class, pre-empted rather than filed later.
     *
     * Narrow, and said so: this cannot check either description is RIGHT. It
     * checks that neither is silent about which instrument it is, and that
     * each names the other, so a reader meeting one is sent to the other.
     */
    @Test
    fun `both published rpe descriptions name their own scale and point at the other`() {
        val root = schema("session-export.schema.json")
        val session = root["properties"]!!.jsonObject["sessionRpe"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        val set = root["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject["rpe"]!!
            .jsonObject["description"]!!.jsonPrimitive.content

        assertTrue("1-10" in session, "the session rating never states its own range: $session")
        assertTrue("reps-in-reserve" in session, "the session rating never denies the set scale: $session")
        assertTrue("`rpe`" in session, "the session rating never names the key it is confused with: $session")
        assertTrue("reps-in-reserve" in set, "the per-set rpe never states which instrument it is: $set")
        assertTrue("6 through 10" in set, "the per-set rpe never states the range the app offers: $set")
        assertTrue("`sessionRpe`" in set, "the per-set rpe never names the key it is confused with: $set")
        // Case-insensitive: the session description shouts the sentence and the
        // set description does not, and which one is in capitals is a matter of
        // where the reader most needs stopping, not of contract.
        listOf(session, set).forEach { description ->
            assertTrue(
                "never be averaged or compared as one quantity" in description.lowercase(),
                "a description omits the one instruction that keeps the two scales apart: $description",
            )
        }
    }

    /**
     * Absence is published as unrated, and the description says it is not a low
     * rating.
     *
     * The rating is skippable by design, so absence is the ORDINARY state of
     * this key, not an edge case -- and the reader most likely to meet it is a
     * model aggregating sessions, for which "no answer" and "an easy session"
     * differ by everything. Absence rendered as a value is the defect class;
     * this is the published half of refusing it.
     */
    @Test
    fun `the published session rating says absence means unrated, not easy`() {
        val session = schema("session-export.schema.json")["properties"]!!.jsonObject["sessionRpe"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "ABSENT MEANS UNRATED, WHICH IS NOT A LOW RATING" in session,
            "the published session rating never says what its absence means: $session",
        )
        assertTrue(
            "Do not substitute a default, a midpoint or an estimate" in session,
            "the published session rating never refuses a substituted value: $session",
        )
    }

    /**
     * The 1.13 version-log entry names its fourth change and still says which
     * one of the four is not additive.
     *
     * The same shape as `the 1_13 version log names the rep marks as well as
     * the duration change`, one change further on. The hazard grows with the
     * count: four changes under one unreleased number is exactly when a reader
     * starts treating the version as a bucket, and the one non-additive member
     * is what they must not miss.
     */
    @Test
    fun `the 1_13 version log names the session rating and still flags the one non-additive change`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "sessionRpe" in description,
            "the version log never mentions sessionRpe, so 1.13 publishes a key it does not explain",
        )
        assertTrue(
            "duration_s" in description,
            "the version log lost the duration_s half of 1.13",
        )
        assertTrue(
            "exactly one of the four -- `duration_s` -- is not additive" in description,
            "the version log no longer says which of 1.13's changes a 1.12 reader must be re-checked against",
        )
    }

    /**
     * The published example carries a session rating.
     *
     * `ci.yml` validates this example against the schema with ajv, and that is
     * the schema half's only automated coverage. An example carrying none of
     * the new key passes a schema that declares it and a schema that does not
     * -- the same reasoning the prep-pair and rep-mark example pins carry.
     */
    @Test
    fun `the published export example carries a session rating`() {
        val rating = schema("examples/session-export.example.json")["sessionRpe"]
        assertNotNull(rating, "the published example carries no sessionRpe, so ajv never validates it")
        assertTrue(
            rating.jsonPrimitive.int in SessionRpe.MIN..SessionRpe.MAX,
            "the published example's session rating is off the scale it is supposed to demonstrate",
        )
    }

    /**
     * The published per-set `rpe` accepts 1 to 10, which is WIDER than the grid
     * the app draws.
     *
     * Characterization, not endorsement. `rpeOptions` in `RecordScreen.kt`
     * offers 6 through 10 and is the only thing that writes this key --
     * `rateLastSet` is its one caller, reaching `SessionRepository.rateSet` and
     * `SessionDao.updateRpe` -- so nothing the app ships produces a 1 here.
     * The bound is pinned as it is because the next change adds a SECOND
     * rating whose range genuinely is 1 to 10, and a reader meeting two 1-to-10
     * integers in one document has nothing but the descriptions to tell them
     * apart.
     */
    @Test
    fun `the published per-set rpe is bounded one to ten, wider than the grid the app draws`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        val rpe = assertNotNull(set["rpe"], "the published export schema does not declare a set's rpe").jsonObject
        assertEquals("integer", rpe["type"]!!.jsonPrimitive.content, "a set's rpe is not published as an integer")
        assertEquals(1, rpe["minimum"]!!.jsonPrimitive.int, "the published per-set rpe floor moved")
        assertEquals(10, rpe["maximum"]!!.jsonPrimitive.int, "the published per-set rpe ceiling moved")
        assertTrue(
            rpe["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "a set's rpe is declared with no description, which is the shape of issue #76",
        )
    }
}
