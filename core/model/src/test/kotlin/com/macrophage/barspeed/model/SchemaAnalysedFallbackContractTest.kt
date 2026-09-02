package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
 * What issue #207 moves in the two published contracts, pinned against the
 * REAL documents in `docs/schemas/` and against the copy of the plan prompt
 * shipped to the lifter, exactly as [SchemaContractTest] and
 * [SchemaSensorContractTest] are.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it.
 *
 * A third file rather than more cases in either of those two, for the reason
 * [SchemaSensorContractTest] gives for being the second: its cases are #198's
 * and these are not, and [SchemaContractTest] is already over a thousand
 * lines. What stays in those files is the sensor block's keys, closure and
 * role vocabulary; what is here is what this issue changes about what the two
 * documents SAY.
 *
 * The version is deliberately not moved. 1.16 is on `main` and unreleased --
 * v0.1.47 shipped 1.15 -- so a key added to it extends a number no consumer
 * has ever seen, and minting 1.17 would publish a boundary that never existed.
 * That is asserted here rather than assumed.
 */
class SchemaAnalysedFallbackContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setSensors() = schema("session-export.schema.json")["\$defs"]!!.jsonObject["setSensors"]!!.jsonObject

    private fun sensorProperty(name: String) = setSensors()["properties"]!!.jsonObject[name]!!.jsonObject

    private fun sensorDescription(name: String) = sensorProperty(name)["description"]!!.jsonPrimitive.content

    private fun exportVersionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val shippedPrompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    /**
     * The published block declares the fact a reader cannot derive, as an
     * optional boolean.
     *
     * Optional and not required, because absence has to keep meaning what it
     * means on every document written before this key existed: the analysed
     * role is the role the set armed. A required boolean would make every
     * earlier export invalid against the contract its consumer is pointed at,
     * and this repository's exports are already in a coach's hands.
     */
    @Test
    fun `the published sensor block declares the fallback as an optional boolean`() {
        val fellBack = assertNotNull(
            setSensors()["properties"]!!.jsonObject["analysedFellBack"],
            "the published sensors block does not declare analysedFellBack",
        ).jsonObject

        assertEquals("boolean", fellBack["type"]!!.jsonPrimitive.content, "the fallback is not a boolean")
        assertTrue(
            fellBack["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
            "the published analysedFellBack carries no description, which is the shape of issue #76",
        )
        assertFalse(
            "analysedFellBack" in setSensors()["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "a required fallback key makes every export written before it invalid",
        )
    }

    /**
     * The published description says what the flag separates, in both
     * directions.
     *
     * A boolean whose description says only "true when it fell back" leaves a
     * reader to guess what false means on a document that also carries
     * `present`. What it means is the load-bearing half: these figures came
     * from the unit the app was pointed at, so they are comparable with the
     * rest of a corpus recorded the same way.
     */
    @Test
    fun `the published fallback description says what each answer means`() {
        val text = sensorDescription("analysedFellBack")

        assertTrue("armed" in text, "the published fallback never says what it is measured against")
        assertTrue(
            "absent" in text.lowercase(),
            "the published fallback never says what its own absence means",
        )
    }

    /**
     * The published `analysedRole` description stops telling a reader that an
     * analysed role missing from `present` means an empty summary.
     *
     * That sentence is what this issue is fixing. Asserted as an ABSENCE over
     * several phrasings, because one wording can be reworded and the false
     * claim survives a substring check on any single one of them; and the
     * replacement is asserted positively, so the key cannot simply go quiet
     * about the case.
     */
    @Test
    fun `the published analysed role no longer promises an empty summary when the unit dropped out`() {
        val text = sensorDescription("analysedRole").lowercase()

        listOf(
            "it can name a role that is absent from `present`",
            "whose figures are then empty rather than wrong",
            "a set whose analysed unit dropped out",
        ).forEach { phrase ->
            assertFalse(
                phrase in text,
                "the published analysedRole still says a role absent from present means an empty summary",
            )
        }
        assertTrue(
            "analysedfellback" in text,
            "the published analysedRole never points a reader at the key that says whether it moved",
        )
    }

    /**
     * The key extends 1.16 rather than minting 1.17, and the log says so.
     *
     * 1.16 is on `main` and unreleased -- v0.1.47 shipped 1.15 -- so no
     * consumer has ever seen a document declaring it. A new number would
     * publish a boundary between two states of the contract that never both
     * existed, which is the mistake the 1.15 entry in this same log records
     * having been corrected once already.
     */
    @Test
    fun `the fallback extends the unreleased version rather than minting one`() {
        assertEquals("1.16", SessionExport.SCHEMA_VERSION, "the version moved for an unreleased addition")
        assertFalse("1.17" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "1.17 was minted")
        assertFalse("1.17:" in exportVersionLog(), "the published log opened a 1.17 entry")

        val entry = exportVersionLog().substringAfterLast("1.16:")
        assertTrue(
            "analysedFellBack" in entry,
            "the published 1.16 entry never mentions the key this version adds",
        )
    }

    /**
     * The copy on the lifter's clipboard carries the same correction the
     * published schema does.
     *
     * `PLAN_PROMPT` is the only statement of this contract anything actually
     * sends anywhere, so a false sentence here is one an LLM reads and the
     * schema's correction never reaches. It currently tells the model that a
     * set whose analysed role is absent from `present` has an empty summary,
     * which is exactly the behaviour being removed.
     */
    @Test
    fun `the copy on the lifter's clipboard documents the fallback`() {
        assertTrue(
            "\"analysedFellBack\"" in shippedPrompt,
            "the plan prompt never mentions \"analysedFellBack\", so no reader of it will look for the key",
        )
        assertFalse(
            "if that role is the analysed one, the set's summary is empty rather than wrong" in shippedPrompt,
            "the shipped prompt still tells a reader an absent analysed role means an empty summary",
        )
    }

    /**
     * DIFFERENTIAL, issue #207 round 2, review finding 3. The published
     * example carries a set whose analysis fell back, so ajv sees the key.
     *
     * `ci.yml` validates only the two hand-written example payloads, so a key
     * absent from both is a key ajv never sees -- and `setSensors` is
     * `additionalProperties: false`, which makes ajv the only check that the
     * declared key validates inside a real document. The repo has codified
     * this three times already: `SchemaSensorContractTest` for `shortfall`,
     * and `SchemaContractTest` for the prep pair and for the rep marks. #207
     * minted a key in the same object one round later and did neither.
     *
     * The block must sit AFTER the dual set. `SchemaContractTest`'s `the
     * published export example carries a dual set's sensor block` reads the
     * FIRST sensors block in the document and asserts `count` 2 with
     * `expected` `[a, b]`.
     */
    @Test
    fun `the published example carries a set whose analysis fell back`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val blocks = sets.mapNotNull { it.jsonObject["sensors"]?.jsonObject }
        val moved =
            assertNotNull(
                blocks.firstOrNull { it["analysedFellBack"] != null },
                "no set in the published example carries analysedFellBack, so ajv never validates the key",
            )

        assertTrue(
            moved.getValue("analysedFellBack").jsonPrimitive.boolean,
            "the example writes the fallback flag false, which the exporter never emits",
        )
        assertEquals(2, moved.getValue("count").jsonPrimitive.int, "a set that fell back armed two streams")
        val present = moved.getValue("present").jsonArray.map { it.jsonPrimitive.content }
        val analysed = moved.getValue("analysedRole").jsonPrimitive.content
        assertEquals(
            listOf("b", "a"),
            moved.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
            "the example's fallback set is not one armed to analyse the second role",
        )
        assertEquals(listOf("a"), present, "the example's fallback set does not show one armed unit silent")
        assertEquals("a", analysed, "the example's fallback set is not analysed from the unit that streamed")
        assertTrue(analysed in present, "the example publishes a fallback onto a role that never streamed")
    }
}
