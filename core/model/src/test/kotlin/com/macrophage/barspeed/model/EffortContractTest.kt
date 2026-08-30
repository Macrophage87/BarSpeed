package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Published-contract pins for the effort scale and the warm-up declaration
 * (#186, #187).
 *
 * A class of its own rather than more of [SchemaContractTest], which detekt
 * refused as too large the moment these were added to it -- and which is the
 * right refusal: this is one change's contract surface, spanning both
 * documents, and it reads as a unit. The reasoning of the parent class holds
 * unchanged here: `docs/schemas/` is what a plan-writing or export-reading
 * model is pointed at, it is on this module's test classpath, and a
 * description that has gone false is worse than a missing one because it is
 * followed.
 *
 * These pin what the documents SAY. What the app does with the same facts is
 * pinned in [EffortScaleTest] and [SetEndControlPolicyTest]; nothing here
 * asserts a screen.
 */
class EffortContractTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    /**
     * The same pin one level DOWN, which did not exist until #187 added a set
     * key and found nothing checking that level at all.
     *
     * A literal set for the reason the exercise-level assertion gives. The
     * wire names, not the Kotlin ones: six of [PlanSetDef]'s twelve carry a
     * `@SerialName`, and `warmup` is one of the six that do not.
     */
    @Test
    fun `the plan's set keys are exactly the ones the app declares`() {
        val setKeys =
            schema("plan.schema.json")["\$defs"]!!.jsonObject["set"]!!
                .jsonObject["properties"]!!.jsonObject.keys
        assertEquals(
            setOf(
                "reps", "duration_s", "load_kg", "load_lb", "side", "note", "tempo",
                "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s", "sensors", "warmup",
            ),
            setKeys,
            "PlanSetDef and the schema disagree on set keys",
        )
    }

    /**
     * `warmup` is declared on the SET, is a boolean, and its description says
     * what the key is for rather than what the old tile did (#187).
     *
     * The three halves are separate claims. On the set, because a block ramps
     * and an exercise-level form would need a per-set override to say
     * anything. A boolean, because an omitted key is a working set and there
     * is no third state. And the description has to carry the sentence a plan
     * author needs -- that this is the set's PURPOSE and not its rating --
     * because the whole point of moving warm-up out of the effort scale is
     * that a warm-up now carries an effort rating like any other set.
     *
     * Narrow, and said so: this cannot check the description is right, only
     * that it names the distinction the change is about.
     */
    @Test
    fun `the published warmup key is a set-level declaration, not a rating`() {
        val plan = schema("plan.schema.json")
        val exercise = plan["\$defs"]!!.jsonObject["exercise"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue("warmup" !in exercise.keys, "warmup is declared at exercise level, where a ramp cannot use it")
        val warmup =
            assertNotNull(
                plan["\$defs"]!!.jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject["warmup"],
                "the published plan schema does not declare a set's warmup",
            ).jsonObject
        assertEquals("boolean", warmup["type"]!!.jsonPrimitive.content, "warmup is not published as a boolean")
        val description = warmup["description"]!!.jsonPrimitive.content
        assertTrue("PREPARATORY" in description, "the warmup description never says what the key means: \$description")
        assertTrue(
            "rates" in description || "rating" in description,
            "the warmup description never separates the set's purpose from its rating: \$description",
        )
    }

    /**
     * The published example declares a warm-up set, so ajv validates the key.
     *
     * The same reasoning as the prep-pair and rep-mark examples: the ajv step
     * only ever sees the two hand-written documents, so a key no example
     * carries is a key that step cannot check.
     */
    @Test
    fun `the published plan example declares a warm-up set`() {
        val sets =
            schema("examples/plan.example.json")["sessions"]!!.jsonArray
                .flatMap { it.jsonObject["exercises"]!!.jsonArray }
                .flatMap { it.jsonObject["sets"]!!.jsonArray }
        assertTrue(
            sets.any { it.jsonObject["warmup"]?.jsonPrimitive?.content == "true" },
            "no set in the published plan example is declared a warm-up, so ajv never validates the key",
        )
    }

    /**
     * The 1.9 entry names the key and says why it is a new version.
     *
     * 1.8 shipped in v0.1.44, so the window in which sensors and bodyweight
     * extended it is closed -- and the version log is where a reader learns
     * that a document declaring 1.9 is refused outright by a v0.1.44 build.
     */
    @Test
    fun `the plan's 1_9 entry records the warm-up declaration and why it is not an extension of 1_8`() {
        val description = schema("plan.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.9:" in description, "the plan version log has no 1.9 entry")
        assertTrue("`warmup`" in description, "the plan version log never names the warm-up key")
        assertTrue(
            "1.8 SHIPPED in v0.1.44" in description,
            "the plan version log never says why 1.9 is minted rather than extending 1.8",
        )
    }

    /**
     * `warmup` no longer tells a reader those sets carry no RPE (#187).
     *
     * The published description said, in so many words, "these carry no RPE
     * and should be excluded from effort/fatigue analysis". Both halves became
     * false the moment warm-up stopped being a tile: the flag is a plan
     * declaration now, and a warm-up set is rated on the same scale as any
     * other set. A description that instructs a reader to throw away a rating
     * the app records is worse than a missing one, because it is followed.
     *
     * Narrow, and said so: this cannot check the new description is right. It
     * checks that the one instruction this change falsified is gone, that the
     * key is still published as a boolean, and that a reader is told what the
     * flag now means.
     */
    @Test
    fun `the published warmup no longer says warm-up sets carry no RPE`() {
        val set = schema("session-export.schema.json")["\$defs"]!!.jsonObject["set"]!!
            .jsonObject["properties"]!!.jsonObject
        val warmup = assertNotNull(set["warmup"], "the published export schema does not declare warmup").jsonObject
        assertEquals("boolean", warmup["type"]!!.jsonPrimitive.content, "warmup is not published as a boolean")
        val description = warmup["description"]!!.jsonPrimitive.content
        assertFalse(
            "these carry no RPE" in description,
            "the published warmup still tells a reader a warm-up set has no rating: \$description",
        )
        assertTrue(
            "PREPARATORY" in description,
            "the published warmup never says the flag is a declaration of purpose: \$description",
        )
        assertTrue(
            "rated on the same scale" in description,
            "the published warmup never says a warm-up set carries a real rating: \$description",
        )
    }

    /**
     * The 1.14 entry names the released boundary it crosses.
     *
     * 1.13 shipped in v0.1.44, so nothing further may ride under it -- the
     * failure mode this pins against is a reader meeting an export whose
     * `warmup` means something the 1.13 description denies, with no version
     * boundary saying so.
     */
    @Test
    fun `the 1_14 entry says why a released version could not be extended`() {
        val description =
            schema("session-export.schema.json")["properties"]!!.jsonObject["schemaVersion"]!!
                .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("1.14:" in description, "the export version log has no 1.14 entry")
        assertTrue(
            "1.13 shipped in v0.1.44" in description,
            "the 1.14 entry never says the version it succeeds was released",
        )
        assertTrue(
            "`warmup` is now a PLAN DECLARATION" in description,
            "the 1.14 entry never says what changed about warmup",
        )
    }

    /**
     * The published example carries a warm-up set that IS rated, so ajv sees
     * the combination the old contract said could not exist.
     */
    @Test
    fun `the published export example carries a rated warm-up set`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        assertTrue(
            sets.any {
                it.jsonObject["warmup"]?.jsonPrimitive?.content == "true" && it.jsonObject["rpe"] != null
            },
            "no set in the published example is both a warm-up and rated, so ajv never validates the pair",
        )
    }
}
