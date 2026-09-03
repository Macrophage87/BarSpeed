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
 * DIFFERENTIALS. Every assertion here fails at the commit that introduces it.
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
}
