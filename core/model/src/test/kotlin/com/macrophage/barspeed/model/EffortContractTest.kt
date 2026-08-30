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

    private fun setProperty(name: String) = schema("session-export.schema.json")["\$defs"]!!
        .jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject[name]!!.jsonObject

    private fun versionLog() = schema("session-export.schema.json")["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

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

    // ---- the scale itself, issue #187 ---------------------------------------

    /**
     * The published `rpe` description names the four COUNTED rungs in the
     * tile's own words, and the full anchor set the app can write. The three
     * HEADROOM rungs' wording moved to `the published rpe description names
     * the headroom figures the tiles offer`, which checks them against the
     * caption table rather than against literals.
     *
     * Exact phrases rather than a looser check, because the ANCHORS are the
     * contract: a stored 6 is only readable if the document says what tapping
     * 6 claimed. Brittle to rewording is the correct sensitivity here --
     * rewording an anchor is exactly the change that must not happen quietly,
     * and a 2026 crossover trial found that redefining a scale's endpoint
     * predictably shifts the ratings people give.
     */
    @Test
    fun `the published rpe description names every counted rung, and every anchor the grid writes`() {
        val description = setProperty("rpe")["description"]!!.jsonPrimitive.content
        listOf(
            "10 nothing left",
            "9 one rep left",
            "8 two reps left",
            "7 three reps left",
        ).forEach { rung ->
            assertTrue(rung in description, "the published rpe never names the rung \"$rung\": $description")
        }
        // And the anchors it names are the anchors the app can actually write.
        assertEquals(
            listOf(1, 4, 6, 7, 8, 9, 10),
            EffortScale.tiles(timed = false, explosive = false, unit = WeightUnit.LB).mapNotNull { it.rpe },
            "the app writes a set of anchors the published description does not describe",
        )
    }

    /**
     * The headroom rungs are published as the FIGURES the tiles say, coupled
     * to the caption table rather than paraphrased.
     *
     * `EffortScale`'s governing rule is that the tile names a WEIGHT and never
     * "one notch", because there is no declared equipment increment anywhere
     * in the codebase. The published description did the opposite: it said
     * "one equipment increment" and "two increments" and named no weight and
     * no duration at all, publishing a quantity the code declares unknowable
     * -- while `PLAN_PROMPT`, the copy users are actually handed, already
     * carried the figures. One contract, two published statements, different
     * content: the drift class that has already shipped a real bug here.
     *
     * Asserted against `headroomCaption` rather than against literals, so
     * revising a band after a field session reds this test until the document
     * moves with it. The figures live in one place and are quoted in the
     * other.
     */
    @Test
    fun `the published rpe description names the headroom figures the tiles offer`() {
        val description = setProperty("rpe")["description"]!!.jsonPrimitive.content
        val quoted =
            HeadroomTier.entries.flatMap { tier ->
                listOf(
                    EffortScale.headroomCaption(tier, EffortAsk.LOAD, WeightUnit.LB),
                    EffortScale.headroomCaption(tier, EffortAsk.LOAD, WeightUnit.KG),
                    EffortScale.headroomCaption(tier, EffortAsk.TIME, WeightUnit.LB),
                )
            }.map { it.removePrefix("Could have added ").removePrefix("Could have gone ") }
        quoted.forEach { figure ->
            assertTrue(
                figure in description,
                "the published rpe never names the figure \"$figure\" the tile offers: $description",
            )
        }
        assertFalse(
            "one equipment increment" in description,
            "the published rpe names a notch, which the app cannot know: $description",
        )
        assertTrue(
            "DOES NOT RECORD which unit's caption was on screen" in description,
            "the published rpe never says the unit is unrecoverable from the export: $description",
        )
    }

    /**
     * The gaps are published as gaps, so a 5 from an older session reads as a
     * value rather than as corruption.
     */
    @Test
    fun `the published rpe description says the unanchored values are still valid`() {
        val description = setProperty("rpe")["description"]!!.jsonPrimitive.content
        assertTrue(
            "2, 3 and 5 are valid values carrying no tile" in description,
            "the published rpe never says the gaps are real values: $description",
        )
        assertEquals(
            setOf(2, 3, 5),
            EffortScale.UNANCHORED_RPE,
            "the code's unanchored set drifted from the three values the document names",
        )
    }

    /**
     * What a pre-v0.1.45 `6` meant is stated, at both the key and the version
     * log.
     *
     * 46% of this lifter's historical ratings sit on 6, and it was the FLOOR
     * of the old grid -- so it absorbed everything the new 1 and 4 now take.
     * Reusing the value is a decision with a cost, and an archive reader who
     * is not told carries the cost silently. Retiring it to 5 was the
     * alternative and is named in the commit that made the choice.
     */
    @Test
    fun `both documents say what a six meant before the scale changed`() {
        val description = setProperty("rpe")["description"]!!.jsonPrimitive.content
        assertTrue(
            "WHAT A PRE-1.14 VALUE MEANT" in description,
            "the published rpe never says what an older value meant: $description",
        )
        assertTrue(
            "'easy, 4+ reps left'" in description,
            "the published rpe never says what the old floor claimed: $description",
        )
        val log = versionLog()
        assertTrue("THE VALUE TO READ CAREFULLY IS 6" in log, "the 1.14 entry never singles out the reused value")
        assertTrue(
            "46% of this lifter's historical ratings sit on it" in log,
            "the 1.14 entry never says how much history the reused value carries",
        )
    }

    /**
     * The version log says the second change is not additive, and why the low
     * end stopped being a rep count.
     *
     * The reason is measured rather than stylistic, and a reader deciding
     * whether to trust an old 6 against a new 4 needs it: mean error in
     * calling one's own reps in reserve is 2.05 reps at 1 RIR and 5.15 at 5.
     */
    @Test
    fun `the 1_14 entry flags the scale change as not additive and gives its evidence`() {
        val log = versionLog()
        assertTrue("1.14 carries a SECOND change" in log, "the 1.14 entry never mentions the scale change")
        assertTrue("NOT additive" in log, "the 1.14 entry never flags the scale change as non-additive")
        assertTrue("5.15 reps at 5 RIR" in log, "the 1.14 entry never gives the measurement the anchors rest on")
    }

    /**
     * The published example's warm-up set records the lowest rung, which is
     * the pair the old contract could not express at all: declared
     * preparatory AND rated.
     */
    @Test
    fun `the published example rates its warm-up set on the lowest rung`() {
        val sets = schema("examples/session-export.example.json")["exercises"]!!
            .jsonArray.flatMap { it.jsonObject["sets"]!!.jsonArray }
        val warmup =
            assertNotNull(
                sets.firstOrNull { it.jsonObject["warmup"]?.jsonPrimitive?.content == "true" },
                "the published example carries no warm-up set",
            ).jsonObject
        assertEquals(
            HeadroomTier.MUCH_MORE.rpe,
            warmup["rpe"]!!.jsonPrimitive.int,
            "the example's ramp set is not rated on the rung a ramp set lands on",
        )
    }

    /**
     * Both published descriptions name their own scale and deny the other's.
     *
     * The owner's ruling, and the whole reason this key needed a design round:
     * the app carries two things called RPE over the same published range --
     * a set's is how much that set had left in it, a session's is 1-to-10
     * overall -- and a reader has nothing but these descriptions to tell two
     * integers apart. A reader that averages them is the #139/#151 defect
     * class, pre-empted rather than filed later.
     *
     * The set-side assertion moved with #187. It used to require the literal
     * "6 through 10", which was the whole range the grid offered; the grid now
     * runs 1 to 10 with rungs anchored differently along its length, so that
     * string is retired rather than reworded and the assertion asks instead
     * that the description names where the counted band starts. Leaving the
     * old one would have pinned a range the app no longer offers.
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
        assertTrue("1-10 scale" in set, "the per-set rpe never states the range the app offers: $set")
        assertTrue("three reps left" in set, "the per-set rpe never says where the counted band starts: $set")
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
}
