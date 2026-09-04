package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What issue #209 changes about what the published export SAYS, pinned against
 * the real document in `docs/schemas/` exactly as [SchemaContractTest],
 * [SchemaSensorContractTest] and [SchemaAnalysedFallbackContractTest] are.
 *
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces
 * it, apart from `the shipped prompt no longer says the analysis moves off
 * any short unit`, which lands in the same commit as the sentence it pins;
 * that commit's body records the mutation run against it.
 *
 * The behaviour change is small and the CONTRACT change is the whole risk.
 * Three published keys stop meaning what they say: `analysedFellBack` said the
 * armed unit "produced no stream", `silent` said its roles "delivered NOTHING
 * for this whole set", and `soleSilent` was absent only when its one link
 * "delivered" at all. After #209 all three cover a unit that delivered a
 * handful of frames -- fewer than
 * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES], which is the estimator's own
 * bound -- and `present` can therefore name a role the analysis moved off.
 * Leaving those sentences standing would publish four false claims in the one
 * document a downstream reader is pointed at.
 *
 * NO VERSION NUMBER IS ASSERTED HERE, deliberately. The mint of 1.18 belongs
 * to another branch and this change is a further entry under it; asserting a
 * digit would pin this file to a landing order it does not control, and the
 * digit is the one thing a rebase moves. What is asserted is that the log SAYS
 * the change, in words no rebase renumbers.
 */
class SchemaShortDeliveryContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun sensorDescription(name: String) = schema()["\$defs"]!!.jsonObject["setSensors"]!!
        .jsonObject["properties"]!!.jsonObject[name]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun exportVersionLog() = schema()["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val shippedPrompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    /** The phrase all three keys are made to carry, so one reading covers the case. */
    private val theCase = "too few frames to analyse"

    /**
     * `analysedFellBack` stops saying the armed unit produced no stream.
     *
     * Asserted as an ABSENCE over several phrasings, for
     * [SchemaAnalysedFallbackContractTest]'s reason: one wording can be
     * reworded and the false claim survives a check on any single one of them.
     * The replacement is asserted positively too, so the key cannot go quiet
     * about the case instead.
     */
    @Test
    fun `the published fallback no longer says the armed unit produced no stream`() {
        val text = sensorDescription("analysedFellBack").lowercase()

        listOf(
            "that unit produced no stream",
            "produced no stream, another one did",
            "read `expected` against `present` for which unit was missing",
        ).forEach { phrase ->
            assertFalse(
                phrase in text,
                "the published analysedFellBack still says the armed unit sent nothing: $phrase",
            )
        }
        assertTrue(
            theCase in text,
            "the published analysedFellBack never says a handful of frames is what it also covers",
        )
    }

    /**
     * `silent` stops saying its roles delivered nothing at all.
     *
     * The word stored there is unchanged and is still a reading of the LINK;
     * what changes is which roles reach it. A reader taking the old sentence
     * literally would conclude the archive holds no file for a role named
     * here, and after #209 it can.
     */
    @Test
    fun `the published silent no longer says its roles delivered nothing`() {
        val text = sensorDescription("silent").lowercase()

        listOf(
            "delivered nothing for this whole set",
            "which armed units delivered nothing",
        ).forEach { phrase ->
            assertFalse(phrase in text, "the published silent still says its roles sent nothing at all: $phrase")
        }
        assertTrue(theCase in text, "the published silent never says a handful of frames also lands a role here")
    }

    /**
     * `present` still means what it always meant -- a role whose stream
     * reached the archive -- and now says outright that this is NOT a claim
     * the stream could be analysed.
     *
     * The key itself does not move: the archive holds the file, and omitting
     * it would be a false statement about the zip. What the description gains
     * is the one sentence that stops a reader inferring the analysis used it,
     * which is the near neighbour of the claim being deleted from
     * `analysedFellBack`.
     */
    @Test
    fun `the published present says reaching the archive is not being analysable`() {
        val text = sensorDescription("present").lowercase()

        assertTrue(
            "reached the raw archive" in text,
            "the published present stopped saying what it has always meant",
        )
        assertTrue(
            theCase in text,
            "the published present never warns that a role here may be one the analysis could not use",
        )
    }

    /**
     * `soleSilent` stops being absent only when its one link delivered
     * anything at all.
     *
     * The near neighbour of the other two: it is [silent]'s own vocabulary for
     * the set that has no role to key it by, and the commit that moved the
     * analysis off a unit that delivered too little to analyse widened
     * `analysedFellBack`, `silent` and the schema's `present` without touching
     * this key's own published description, so a reader of the document alone
     * would still read a one-sensor set that delivered a handful of frames as
     * one whose link "delivered".
     */
    @Test
    fun `the published soleSilent no longer says its one link delivered nothing`() {
        val text = sensorDescription("soleSilent").lowercase()

        assertFalse(
            "absent when that link delivered (1.17" in text,
            "the published soleSilent still says any delivery at all clears it",
        )
        assertTrue(
            theCase in text,
            "the published soleSilent never says a handful of frames is what it also covers",
        )
    }

    /**
     * The version log carries the change, in words rather than in a number.
     *
     * `ci.yml` validates payloads against this document and nothing validates
     * the log, so this is the only thing that fails when the behaviour lands
     * with the changelog silent -- which is how a reader comes to hold two
     * exports of different meanings under one version.
     */
    @Test
    fun `the published version log records the short-delivery change`() {
        val log = exportVersionLog().lowercase()

        assertTrue(theCase in log, "the published changelog never mentions the short-delivery change")
        assertTrue(
            "#209" in log,
            "the published changelog does not name the issue the short-delivery change came from",
        )
    }

    /**
     * ROUND 2 FINDING 2. The published `analysedRole` description states the
     * rule its Kotlin twin states, rather than the one #209 replaced.
     *
     * `SessionExport.kt`'s `analysedRole` KDoc already reads "delivered too
     * few frames to analyse" / "delivered enough". The published copy still
     * read "produced nothing" / "the unit that did", so the document a
     * downstream reader is actually pointed at and the source stated two
     * different rules for one key. Asserted as an ABSENCE over both halves of
     * the old sentence and as a PRESENCE over both halves of the new one, so
     * the description cannot go quiet about the case instead.
     */
    @Test
    fun `the published analysed role states the rule its Kotlin twin states`() {
        val text = sensorDescription("analysedRole").lowercase()

        listOf(
            "a unit that produced nothing",
            "is analysed from the unit that did",
        ).forEach { phrase ->
            assertFalse(phrase in text, "the published analysedRole still states the pre-#209 rule: $phrase")
        }
        assertTrue(theCase in text, "the published analysedRole never says what too little delivery is")
        assertTrue(
            "delivered enough" in text,
            "the published analysedRole never says what the unit it moves to did",
        )
    }

    /**
     * ROUND 2 FINDING 1. The published `analysedRole` enumerates THREE sets it
     * can name a role absent from `present` on, not two.
     *
     * #209 created the third and the enumeration was not moved. An armed unit
     * that delivered NOTHING beside a partner that delivered one to seven
     * frames leaves `analysable` empty, so
     * [SensorCapturePolicy.analysedStream] finds no candidate and keeps the
     * armed role -- while `present` names the partner, because one frame is a
     * file in the archive. That is the only one of the three where `present`
     * is NOT empty, which is exactly the shape a reader would use the old
     * "two situations" sentence to rule out.
     */
    @Test
    fun `the published analysed role names the third set it can sit outside present on`() {
        val text = sensorDescription("analysedRole").lowercase()

        assertFalse(
            "in two situations" in text,
            "the published analysedRole still enumerates two sets it can sit outside present on",
        )
        assertTrue(
            "in three situations" in text,
            "the published analysedRole does not say how many sets it can sit outside present on",
        )
        assertTrue(
            "stayed on the armed role" in text,
            "the published analysedRole never names the set where nothing analysable arrived and present is not empty",
        )
    }

    /**
     * ROUND 2 FINDING 5. The shipped plan prompt stops opening the
     * analysed-role rule with a clause its own next sentence contradicts.
     *
     * `PLAN_PROMPT` read "The app analyses a role that STREAMED wherever one
     * did, so an analysed role absent from `present` never means ..." and then
     * gave "or every unit that streamed sent fewer than eight frames" as one
     * of the cases -- a unit that streamed, and was not analysed. The clause
     * is deleted rather than reworded; the rest of the sentence was already
     * true and is left alone. This is the copy an LLM is handed, so it is the
     * one place the contradiction reaches a reader who has no source to check.
     */
    @Test
    fun `the shipped prompt no longer opens the analysed role rule with a contradicted clause`() {
        assertFalse(
            "analyses a role that STREAMED wherever one did" in shippedPrompt,
            "the shipped prompt still promises any streaming role is the analysed one",
        )
        assertTrue(
            "every unit that streamed sent fewer than eight frames" in shippedPrompt,
            "the shipped prompt stopped naming the case that contradicts the deleted clause",
        )
    }

    /**
     * ROUND 3 FINDING 2. The shipped plan prompt states the fallback's
     * condition, not a universal.
     *
     * `PLAN_PROMPT` read "the app moves the analysis off any unit that sent
     * fewer than eight". [SensorCapturePolicy.analysedStream] returns the
     * armed role with `fellBack = false` when `analysable` holds no other
     * role, so a short unit whose partner was also short keeps the analysis.
     * The three other copies of this rule -- `SensorCapture.kt`,
     * `SessionExport.kt` and the published `analysedRole` description --
     * already carry the condition.
     */
    @Test
    fun `the shipped prompt no longer says the analysis moves off any short unit`() {
        assertFalse(
            "moves the analysis off any unit that sent fewer than eight" in shippedPrompt,
            "the shipped prompt still moves the analysis off every short unit",
        )
        assertTrue(
            "moves the analysis off a unit that sent fewer than eight only when another unit " +
                "sent eight or more" in shippedPrompt,
            "the shipped prompt stopped stating the condition the move needs",
        )
    }
}
