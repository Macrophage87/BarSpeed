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
 * What the published plan schema says about `implement`, `bar_lb` and `bar_kg`
 * (#253).
 *
 * A file of its own rather than more assertions in [SchemaContractTest], for
 * the reason [SchemaAddedSetContractTest] gives: that class sits on detekt's
 * `LargeClass` threshold and CI runs detekt first. The exercise KEY SET pin
 * stays there, because a key set asserted in two files is a key set that can
 * disagree with itself.
 *
 * The vocabulary is pinned in BOTH directions against the [Implement] enum,
 * the arrangement `VALID_KINDS` uses: a word in the schema that the app cannot
 * resolve, and a member the schema will not let a plan write, are different
 * failures and both are silent. NOT also against `PlanFile.VALID_IMPLEMENTS`,
 * which is derived from that same enum -- an assertion between a value and the
 * expression that produced it cannot fail, and a check that cannot fail reads
 * as coverage.
 */
class SchemaImplementContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun exercise() = schema("plan.schema.json")["\$defs"]!!.jsonObject["exercise"]!!.jsonObject

    private fun property(key: String) = assertNotNull(
        exercise()["properties"]!!.jsonObject[key],
        "the published exercise contract does not declare \"$key\"",
    ).jsonObject

    @Test
    fun `the published implement vocabulary is exactly the Implement enum`() {
        assertEquals(
            Implement.entries.map { it.name.lowercase() }.toSet(),
            property("implement")["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            "the schema's implement words and the Implement enum disagree",
        )
    }

    @Test
    fun `the published implement says an omitted key is other and nothing is inferred`() {
        val description = property("implement")["description"]!!.jsonPrimitive.content
        assertTrue("other" in description, "the published implement never says what an omission means")
        assertTrue(
            "single dumbbell" in description || "one dumbbell" in description,
            "the published implement never states the single-dumbbell rule: $description",
        )
    }

    @Test
    fun `both bar keys are positive numbers`() {
        for (key in listOf("bar_lb", "bar_kg")) {
            val field = property(key)
            assertEquals("number", field["type"]!!.jsonPrimitive.content, "$key is not a number")
            assertEquals(
                0,
                field["exclusiveMinimum"]!!.jsonPrimitive.int,
                "$key does not exclude zero, and a bar of no weight is not a bar",
            )
        }
    }

    @Test
    fun `the schema refuses two bar weights at once`() {
        val exclusion = assertNotNull(
            exercise()["not"],
            "the published exercise permits both bar units at once",
        ).jsonObject
        assertEquals(
            listOf("bar_kg", "bar_lb"),
            exclusion["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "the exercise-level exclusion is not the bar pair",
        )
    }

    @Test
    fun `the schema refuses a bar weight on anything but a barbell`() {
        val guard = assertNotNull(
            exercise()["allOf"],
            "the published exercise never ties a bar weight to the barbell implement",
        ).jsonArray.map { it.jsonObject }
        val barRule = assertNotNull(
            guard.firstOrNull { rule ->
                rule["if"]?.jsonObject?.get("anyOf")?.jsonArray?.any { branch ->
                    branch.jsonObject["required"]?.jsonArray?.any {
                        it.jsonPrimitive.content.startsWith("bar_")
                    } == true
                } == true
            },
            "no allOf branch keys off a declared bar weight",
        )
        val then = barRule["then"]!!.jsonObject
        assertEquals(
            "barbell",
            then["properties"]!!.jsonObject["implement"]!!.jsonObject["const"]!!.jsonPrimitive.content,
            "a bar weight does not force implement to barbell",
        )
        assertTrue(
            then["required"]!!.jsonArray.any { it.jsonPrimitive.content == "implement" },
            "a bar weight with NO implement at all is still accepted",
        )
    }

    @Test
    fun `the schema ties a declared dumbbell to a pair and a barbell to one object`() {
        val guard = exercise()["allOf"]!!.jsonArray.map { it.jsonObject }
        fun ruleFor(word: String) = assertNotNull(
            guard.firstOrNull {
                it["if"]?.jsonObject?.get("properties")?.jsonObject
                    ?.get("implement")?.jsonObject?.get("const")?.jsonPrimitive?.content == word
            },
            "no allOf branch keys off \"implement\": \"$word\"",
        )["then"]!!.jsonObject["properties"]!!.jsonObject["implementCount"]!!.jsonObject
        assertEquals(2, ruleFor("dumbbell")["minimum"]!!.jsonPrimitive.int, "a dumbbell may still be one object")
        assertEquals(1, ruleFor("barbell")["maximum"]!!.jsonPrimitive.int, "a barbell may still be two objects")
    }

    @Test
    fun `the plan's 1_12 entry records the implement key as landed under it`() {
        val description = schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("`implement`" in description, "the plan version log never names the implement key")
        assertTrue(
            "`bar_lb`" in description,
            "the plan version log never names the bar override that came with it",
        )
        assertTrue(
            "no longer" in description,
            "the version log never says what STOPS happening -- a plan written before 1.12 loses " +
                "the card's loading line, and the log is where a reader finds that out: $description",
        )
    }
}
