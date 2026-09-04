package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED. The PUBLISHED plan schema still names FIVE body-weight ids while
 * [ExerciseDef.BODYWEIGHT_IDS] holds eight (#239), and it names them TWICE.
 *
 * Both tests here fail at the commit that adds them and pass at the one after.
 * The defect they guard is not the widening itself -- that landed correctly in
 * Kotlin -- but the document a plan-writing LLM is pointed at, which is the
 * only statement of this contract it can validate a plan against. A model
 * reading the published five would go on writing `bodyweight: true` by hand on
 * a muscle-up, or worse, believe an omitted key there resolves to false.
 *
 * A separate class from [PlanGeometryNullabilityContractTest] and from
 * [SchemaContractTest] for that class's own stated reason: [SchemaContractTest]
 * sits on detekt's `LargeClass` limit, so growing it reds `:core:model:detekt`
 * before a test runs.
 */
class BodyweightPublishedPopulationTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun planSchema() = schema("plan.schema.json")

    /**
     * The property's own enumeration is EQUAL to the table, not a subset of it.
     *
     * Asserted as set equality against [ExerciseDef.BODYWEIGHT_IDS] rather than
     * by containment, and parsed out of the clause rather than searched for in
     * the whole string, because both weaker forms pass on the thing this exists
     * to catch: `dip` occurs in the sentence "pull-ups, dips, push-ups" that
     * opens the same description, so a containment check for it can never fail
     * however the id list is edited, and a subset check cannot see an id the
     * table dropped.
     */
    @Test
    fun `the published bodyweight description enumerates exactly the ids the app defaults`() {
        val description = planSchema()["\$defs"]!!.jsonObject["exercise"]!!
            .jsonObject["properties"]!!.jsonObject["bodyweight"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        val marker = " -- ids the app ships as body-weight work by construction --"
        val end = description.indexOf(marker)
        assertTrue(end >= 0, "the published bodyweight description no longer names an id population at all")
        val start = description.lastIndexOf("On ", end)
        assertTrue(
            start >= 0,
            "the id clause in the published bodyweight description has no 'On ' opener to parse from",
        )
        val published = description.substring(start + "On ".length, end)
            .split(", ", " and ")
            .map { it.trim() }
            .toSet()
        assertEquals(
            ExerciseDef.BODYWEIGHT_IDS,
            published,
            "the published plan schema and ExerciseDef.BODYWEIGHT_IDS disagree about which ids default to body weight",
        )
    }

    /**
     * The 1.12 version-log entry POINTS at that property instead of repeating
     * it.
     *
     * `chin_up` is the marker: it occurs exactly once in the version log today,
     * inside the body-weight clause of the 1.12 entry, so its absence is an
     * exact statement that the second copy is gone. The `sensorOnStack` half of
     * the same sentence already reads "on the stack machines it lists" -- a
     * pointer -- and this makes the body-weight half match it. One enumeration
     * is what stops the two drifting apart again, which is the whole defect:
     * the widening moved the Kotlin table and neither copy of the population.
     */
    @Test
    fun `the 1_12 entry points at the property instead of restating the body-weight ids`() {
        val versionLog = planSchema()["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.12:" in versionLog, "the plan version log has no 1.12 entry")
        assertFalse(
            "chin_up" in versionLog,
            "the 1.12 entry still carries its own copy of the body-weight id population",
        )
        assertTrue(
            "`bodyweight` on the ids named at \$defs.exercise.bodyweight" in versionLog,
            "the 1.12 entry neither enumerates the body-weight ids nor points at where they are enumerated",
        )
    }
}
