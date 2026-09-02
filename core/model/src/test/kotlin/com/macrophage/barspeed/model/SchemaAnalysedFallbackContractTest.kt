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
 * The version MOVES, and that REVERSES what two earlier rounds of this branch
 * asserted in this same KDoc. Both said 1.16 was unreleased, so extending it
 * cost nothing; each was true when it was written. While this branch sat in
 * review 1.16 shipped in v0.1.48, so the key mints 1.17 instead. Asserted
 * below against the constant, the accepted set and both published logs rather
 * than assumed.
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

    private fun exportVersionEnum() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

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
    fun `the published fallback description names what it is measured against and what absence means`() {
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
     * The key MINTS 1.17 rather than extending 1.16, and the log says so.
     *
     * 1.16 is RELEASED: `git rev-list -n1 v0.1.48` resolves to the commit
     * whose `SCHEMA_VERSION` is "1.16", so a consumer has already been handed
     * a document declaring it and a key added under that number would
     * redefine what it means. Two earlier drafts of this test asserted the
     * opposite -- that 1.16 was unreleased and extending it was free -- and
     * each was true when it was written. They are corrected here rather than
     * reworded away.
     *
     * The 1.16 half is asserted as an ABSENCE as well as the 1.17 half as a
     * presence: moving the constant and leaving the key described under the
     * old number would publish two logs disagreeing about which contract
     * added it.
     */
    @Test
    fun `the fallback mints a new version rather than extending the released one`() {
        // CORRECTED BY #216, which mints 1.18. This test asserted the
        // CURRENT constant equals "1.17" -- true while 1.17 was unreleased,
        // false since v0.1.49 shipped it, read by
        // `git show v0.1.49:core/model/.../SessionExport.kt`. What this file
        // owes is that ITS OWN change rode under 1.17 and that 1.17 is still
        // accepted; an equality against the newest constant belongs to
        // whichever change is newest and moves every time one lands, which is
        // the rule the migration tests already apply to DATABASE_VERSION. The
        // companion `assertFalse("1.18" in SUPPORTED_SCHEMA_VERSIONS)` goes
        // with it for the same reason: 1.18 is minted now.
        assertTrue("1.17" in SessionExport.SUPPORTED_SCHEMA_VERSIONS, "the version written is not accepted")
        assertTrue("1.17" in exportVersionEnum(), "the published enum does not accept the version written")

        val log = exportVersionLog()
        assertTrue(
            "analysedFellBack" in log.substringAfterLast("1.17:"),
            "the published 1.17 entry never mentions the key this version adds",
        )
        assertFalse(
            "analysedFellBack" in log.substringBefore("1.17:"),
            "the published log still describes the key under a released version",
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
