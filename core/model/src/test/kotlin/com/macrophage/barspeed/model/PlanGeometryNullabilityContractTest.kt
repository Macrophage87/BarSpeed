package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the PUBLISHED plan schema says an omitted geometry flag means.
 *
 * A separate class from [SchemaContractTest] rather than two more methods in
 * it: that class sits on detekt's `LargeClass` limit and adding these to it
 * reds `:core:model:detekt` before a single test runs. Same source of truth --
 * `docs/schemas/plan.schema.json` on the test classpath, read rather than
 * copied -- and the same reason for existing: the document a plan-writing LLM
 * is pointed at is the only statement of this contract it can validate
 * against.
 */
class PlanGeometryNullabilityContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    /**
     * The three geometry flags are published NULLABLE, so the document a
     * plan-writing model validates against says what the app already does: an
     * absent key is not a declared `false`.
     *
     * This is the schema catching up with the code, and the gap it closes was
     * real rather than cosmetic, though not on the same date for both.
     * `sensorOnStack` (#223) has read an omitted key as undeclared since
     * v0.1.49; `bodyweight` (#227) has done so since v0.1.50, one release
     * later. Both left this document still typing them as plain booleans, so
     * the published contract and the running app disagreed about what an
     * omitted key meant, and a plan spelling absence as `null` was refused by
     * the schema and accepted by the app. `sensorInverted` is the third and is
     * widened with them, in this same schema mint that first makes it nullable
     * in Kotlin too.
     *
     * `type` is asserted as the two-element list rather than by membership: a
     * bare `"boolean"` and a `["boolean", "null"]` are the whole difference
     * here, so a containment check would pass on the thing this exists to
     * catch.
     */
    @Test
    fun `the three geometry flags are published nullable`() {
        val exercise = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!
            .jsonObject["properties"]!!.jsonObject
        for (key in listOf("sensorInverted", "sensorOnStack", "bodyweight")) {
            val field = assertNotNull(exercise[key], "the published plan schema does not declare $key").jsonObject
            assertEquals(
                listOf("boolean", "null"),
                field["type"]!!.jsonArray.map { it.jsonPrimitive.content },
                "$key is published as a plain boolean, so the schema still reads an omitted key as a declared false",
            )
        }
    }

    /**
     * The version the app writes, pinned beside the mint that moved it.
     *
     * `SchemaProgressionContractTest` held this assertion while 1.11 was the
     * newest, and the copy naming 1.11 is deleted rather than carried forward.
     * It lives with whichever mint is current so that the next one has one
     * place to move it from, and so that a bump left half-done -- the schema
     * enum widened, `PlanFile.SCHEMA_VERSION` left behind -- reds here rather
     * than shipping a prompt asking for a version the app does not write.
     */
    @Test
    fun `the app writes plan schema 1_12`() {
        assertEquals("1.12", PlanFile.SCHEMA_VERSION)
    }

    /**
     * A plan written against the previous contract imports unchanged.
     *
     * The claim the mint rests on, and the one worth pinning rather than
     * asserting in prose: 1.12 widens the accepted TYPE of three keys and
     * changes the meaning of none, so a 1.11 document declaring all three
     * explicitly resolves to exactly the geometry it always did. Green before
     * the mint as well as after -- it is a premise, not a differential -- and
     * it is here so that a later change to the precedence cannot quietly
     * re-read an explicit `false`.
     */
    @Test
    fun `a 1_11 plan declaring the three flags false imports unchanged`() {
        val text = """
            {
              "schemaVersion": "1.11",
              "planName": "P",
              "sessions": [
                {
                  "name": "S",
                  "exercises": [
                    {
                      "exercise": "seated_row",
                      "sensorInverted": false,
                      "sensorOnStack": false,
                      "bodyweight": false,
                      "sets": [ { "reps": 5 } ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString(PlanFile.serializer(), text)
        assertEquals(emptyList(), plan.validate(), "a 1.11 document is refused by the 1.12 build")
        val declared = plan.sessions[0].exercises[0]
        val base = ExerciseDef(id = "seated_row", displayName = "Seated Row", sensorOnStack = true)
        val out = SetGeometryPolicy.resolve(base, declared)
        assertEquals(false, out.sensorInverted, "a declared false stopped winning on sensorInverted")
        assertEquals(false, out.sensorOnStack, "a declared false stopped winning on sensorOnStack")
        assertEquals(false, out.bodyweight, "a declared false stopped winning on bodyweight")
    }

    /**
     * The plan's 1.12 entry states the omission rule ONCE, for all three keys
     * at once, and says which ids carry a built-in default under it.
     *
     * The rule is what a reader cannot infer from the properties: three
     * nullable booleans look like three independent conveniences, and they are
     * one contract -- absent and null both mean "no opinion", and what stands
     * in that case is the app's own definition of that exercise. Stated in the
     * version log rather than three times over the properties, because four
     * near-copies of one paragraph is how this document's own history says
     * these drift.
     *
     * The `sensorInverted` half is asserted separately because it is the one
     * with an EMPTY id table: saying so is the difference between "the app has
     * a default you cannot see" and "there is nothing to default to."
     */
    @Test
    fun `the plan's 1_12 entry states the omission rule and names the empty table`() {
        val description = schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.12:" in description, "the plan version log has no 1.12 entry")
        assertTrue(
            "an absent key and a null are the same thing and neither is a declaration" in description,
            "the 1.12 entry never states what an omitted geometry flag means",
        )
        assertTrue(
            "no exercise the app ships is inverted by construction" in description,
            "the 1.12 entry never says that sensorInverted has no built-in default to fall back to",
        )
        assertTrue(
            "1.12" in PlanFile.SUPPORTED_SCHEMA_VERSIONS,
            "PlanFile does not accept 1.12, so no plan can declare the contract this entry describes",
        )
    }

    /**
     * The 1.12 entry names the release the omitted-`bodyweight` rule shipped
     * in, rather than claiming it has not shipped.
     *
     * The minted entry said the app has read an omitted `bodyweight` as
     * undeclared "since #227, which has shipped in no release yet". That was
     * false when it was written. "Make bodyweight nullable so an omitted key
     * is not a silent false" -- 61779b67, the commit that made the app read
     * it that way -- is an ancestor of tag v0.1.50 and is not an ancestor of
     * v0.1.49, both checked with `git merge-base --is-ancestor`. So the rule
     * had already shipped, and a plan author reading the entry was told the
     * build in their hands could not hold it.
     *
     * This is the SECOND time this document has said a shipped version had
     * not shipped -- the 1.8 entry still reads "1.8 has not shipped in any
     * release", which the 1.9 entry corrects by naming v0.1.44 -- which is
     * why the corrected clause is pinned here rather than only written.
     *
     * What is pinned is the SENTENCE, not the fact behind it. Nothing on a
     * JVM test classpath can read a git tag, so this reds when the clause is
     * reverted or reworded and would stay green if the tag itself ever moved.
     * The clause is asserted whole, `#227` included, because "v0.1.50" alone
     * would match any later entry naming that release for something else.
     */
    @Test
    fun `the 1_12 entry names the release the omitted-bodyweight rule shipped in`() {
        val description = schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.12:" in description, "the plan version log has no 1.12 entry")
        assertTrue(
            "since #227, which shipped in v0.1.50" in description,
            "the 1.12 entry does not name v0.1.50 as the release the omitted-bodyweight rule shipped in",
        )
    }
}
