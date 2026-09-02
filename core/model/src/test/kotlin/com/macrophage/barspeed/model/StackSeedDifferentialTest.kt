package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differentials for the field-37 shape (#223): an assisted pull-up, run on the
 * machine's assist stack, whose plan never mentioned `sensorOnStack`.
 *
 * Every assertion here is RED at the commit that introduces it. Today
 * `PlanExerciseDef.sensorOnStack` is a non-nullable `Boolean` defaulting to
 * false, so an omitted key and a declared false are the same value,
 * `SetGeometryPolicy.resolve` assigns it unconditionally over whatever the
 * built-in definition said, no provenance is published for it, and the import
 * gate says nothing.
 *
 * The one green assertion is marked as such: a plan that DID declare the key
 * must draw no gate line, because a warning that fires on a correct plan is
 * how a gate becomes something the eye skips.
 */
class StackSeedDifferentialTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(id: String, declarations: String = ""): PlanExerciseDef = json.decodeFromString(
        PlanFile.serializer(),
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).sessions[0].exercises[0]

    private fun adHoc(id: String) = ExerciseDef(id, id.replace('_', ' '))

    private fun warnings(id: String, declarations: String = ""): List<String> = PlanImport.parse(
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).warnings

    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    /** RED. The field-37 set: the key is absent, and the machine rides a stack. */
    @Test
    fun `an absent stack key on an assisted pull-up resolves to the stack`() {
        val plan = exercise("assisted_pull_up")
        assertEquals(true, SetGeometryPolicy.resolve(adHoc("assisted_pull_up"), plan).sensorOnStack)
    }

    /**
     * RED. The other direction, and the reason the seed default is safe to
     * ship: a plan that says `false` still wins, because only its author knows
     * the sensor went on the handle. Red today only in its provenance -- the
     * VALUE is already false, for the wrong reason.
     */
    @Test
    fun `a declared false on a seeded machine wins and is published as declared`() {
        val plan = exercise("lat_pulldown", ""","sensorOnStack":false""")
        val used = SetGeometryPolicy.resolve(adHoc("lat_pulldown"), plan)
        assertEquals(false, used.sensorOnStack)
        assertEquals(
            "declared",
            sourceOf(SetGeometryPolicy.describe(used, plan), "sensorOnStack"),
        )
    }

    /** RED. What the export publishes about where the value came from. */
    @Test
    fun `an inferred stack mount is published as seeded and a declared one is not`() {
        val absent = exercise("assisted_pull_up")
        val used = SetGeometryPolicy.resolve(adHoc("assisted_pull_up"), absent)
        assertEquals("seeded", sourceOf(SetGeometryPolicy.describe(used, absent), "sensorOnStack"))

        val declared = exercise("assisted_pull_up", ""","sensorOnStack":true""")
        val usedDeclared = SetGeometryPolicy.resolve(adHoc("assisted_pull_up"), declared)
        assertEquals(
            "declared",
            sourceOf(SetGeometryPolicy.describe(usedDeclared, declared), "sensorOnStack"),
        )
    }

    /** RED. A free-weight id with nothing to seed it still reports default. */
    @Test
    fun `an id nothing seeds still publishes a default stack mount`() {
        val plan = exercise("back_squat")
        val used = SetGeometryPolicy.resolve(ExerciseDef.seedById("back_squat")!!, plan)
        assertEquals(false, used.sensorOnStack)
        assertEquals("default", sourceOf(SetGeometryPolicy.describe(used, plan), "sensorOnStack"))
    }

    /**
     * RED. The import gate names the inference once per exercise, the way a
     * declared `sensors` key is named: the plan looks like it decided nothing
     * and the app is about to decide it, and this is the only surface that can
     * ask before the lifter is on the machine.
     */
    @Test
    fun `the gate names an inferred stack mount once for the exercise`() {
        val lines = warnings("assisted_pull_up").filter { "sensorOnStack" in it }
        assertEquals(1, lines.size, "expected exactly one stack line: ${warnings("assisted_pull_up")}")
        assertTrue("assisted_pull_up" in lines.single(), lines.single())
    }

    /** GREEN, and must stay so: a plan that declared the key is not lectured. */
    @Test
    fun `a plan that declares the key draws no stack warning`() {
        for (value in listOf("true", "false")) {
            val lines = warnings("assisted_pull_up", ""","sensorOnStack":$value""")
            assertTrue(lines.none { "sensorOnStack" in it }, "warned about a declared key: $lines")
        }
        assertTrue(
            warnings("back_squat").none { "sensorOnStack" in it },
            "warned about an id nothing seeds",
        )
    }

    /**
     * RED. The published document and its Kotlin twin, which have to move
     * together: `geometrySource` is `additionalProperties: false`, so a key
     * added to one and not the other either fails ajv or is silently dropped.
     */
    @Test
    fun `the published geometry source block and its Kotlin twin both carry the stack mount`() {
        val required = schema("session-export.schema.json")["\$defs"]!!.jsonObject["geometrySource"]!!
            .jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("sensorOnStack" in required, "the published source block does not carry it: $required")
        val twin = GeometrySourceExport.serializer().descriptor
        assertEquals(required, (0 until twin.elementsCount).map(twin::getElementName).toSet())
    }

    private fun sourceOf(g: ResolvedGeometry, key: String): String? =
        Json.parseToJsonElement(Json.encodeToString(GeometrySources.serializer(), g.sources))
            .jsonObject[key]?.jsonPrimitive?.content
}
