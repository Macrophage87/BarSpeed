package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the published session-export schema says about a warm-up the LIFTER
 * marked (#194).
 *
 * NONE OF THESE PASSED WHEN THEY WERE WRITTEN, at 1264085 (CI run
 * 33315140693, conclusion failure). The published `warmup` description opened
 * "True when the PLAN declared this set PREPARATORY", which #187 made true and
 * #194 makes false: the plan stops being the only producer. A description left
 * standing would tell a reader the flag means something it no longer means,
 * which is the defect class this repository names as a claim false at the SHA
 * asserting it.
 *
 * A file of its own for [SchemaAddedSetContractTest]'s reason: [SchemaContractTest]
 * is at detekt's `LargeClass` threshold.
 */
class SchemaWarmupMarkContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties(): JsonObject {
        val defs = schema("session-export.schema.json")["\$defs"]!!.jsonObject
        return defs["set"]!!.jsonObject["properties"]!!.jsonObject
    }

    private fun versionLog(): String {
        val properties = schema("session-export.schema.json")["properties"]!!.jsonObject
        return properties["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content
    }

    /**
     * The published warm-up description stops saying the plan is the only
     * producer.
     *
     * It has to name the lifter's mark AND say which one wins, because a
     * reader meeting `warmup: true` after this version cannot otherwise tell
     * whether a plan or a person said it -- and the two support different
     * conclusions about adherence.
     */
    @Test
    fun `the published warmup says the lifter can mark it and who wins`() {
        val description = setProperties()["warmup"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(
            "lifter" in description,
            "the published warm-up flag still says only the plan can declare it: $description",
        )
        assertTrue(
            "wins" in description,
            "the published warm-up flag never says whose statement wins when the two disagree: $description",
        )
    }

    /**
     * The published export declares which of the two facts a document carries.
     *
     * `$defs.set` is `additionalProperties: false`, so an undeclared key makes
     * every export carrying it invalid. And without the key a reader cannot
     * tell a plan-declared ramp from one the lifter improvised at the rack.
     */
    @Test
    fun `the published export declares the lifter's own mark, described`() {
        val mark = assertNotNull(
            setProperties()["warmupByLifter"],
            "the published export schema does not declare the lifter's own warm-up mark",
        ).jsonObject
        assertEquals("boolean", mark["type"]!!.jsonPrimitive.content, "the lifter's mark is not published as a boolean")
        val description = mark["description"]!!.jsonPrimitive.content
        assertTrue(
            "OMITTED" in description,
            "the published mark never says what its absence means: $description",
        )
        assertTrue(
            "plan" in description,
            "the published mark never relates itself to the plan's declaration: $description",
        )
    }

    /**
     * The version log names the mark as 1.14's fourth change, and says it is
     * NOT purely additive.
     *
     * It is not: no key changes type, but `warmup` can now be written by a
     * second producer and its published meaning widens, so a 1.13 reader that
     * treats `warmup: true` as "the plan said so" must be re-checked. Calling
     * it additive would be the comfortable answer and the wrong one.
     */
    @Test
    fun `the 1_14 version log names the mark as its fourth change and flags it as not additive`() {
        val log = versionLog()
        assertTrue(
            "`warmupByLifter`" in log,
            "the version log never mentions the lifter's mark, so 1.14 publishes an unexplained key",
        )
        assertTrue(
            "FOURTH change" in log,
            "the version log does not carry a fourth 1.14 entry for the mark",
        )
        assertTrue(
            "NOT additive" in log,
            "the version log does not warn a 1.13 reader that warmup gained a second producer",
        )
    }

    /**
     * The published example shows the DISAGREEMENT, not only the agreement.
     *
     * `warmupByLifter` true with `warmup` ABSENT is the shape #194 exists
     * for, and until now no example carried it. ajv is the only automated
     * check the examples get and an absent case is exactly what it cannot
     * notice.
     *
     * The device produces it: the round-2 bench session's one set published
     * `warmupByLifter` true with no `warmup` key.
     */
    @Test
    fun `the published example shows a warm-up the lifter took off the plan`() {
        val sets =
            schema("examples/session-export.example.json")["exercises"]!!.jsonArray
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
                .map { it.jsonObject }
        assertTrue(
            sets.any { "warmupByLifter" in it && "warmup" !in it },
            "the published example never shows a set the lifter took off the plan's warm-up",
        )
        assertTrue(
            sets.any { "warmupByLifter" in it && "warmup" in it },
            "the published example no longer shows the agreeing case either",
        )
    }
}
