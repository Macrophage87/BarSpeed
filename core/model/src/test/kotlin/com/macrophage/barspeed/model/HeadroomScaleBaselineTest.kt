package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the headroom end of the effort scale says TODAY, before #244 words it
 * by the exercise's progression.
 *
 * CHARACTERIZATION, not contract. Every assertion here describes behaviour
 * that is about to move or to be joined by a sibling, and it is written down
 * first so the diff that moves it is a visible diff on a test rather than a
 * quiet reword. Three of these are expected to red in the commit that fixes
 * #244 and are corrected there; the rest are expected to survive and are here
 * so that a change which breaks them is caught rather than absorbed.
 *
 * The defect being characterized: the headroom rungs are worded by the set's
 * KIND alone. A dynamic set is asked how much LOAD it had left whatever the
 * plan's `progression` key says, so a pull-up block declared `"reps"` -- work
 * the lifter cannot add plates to -- is asked about a ladder the exercise does
 * not have, and an exercise declared `"none"` is promised an increment the app
 * will then refuse to offer.
 */
class HeadroomScaleBaselineTest {
    private fun schema(name: String) = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun setProperties() = schema("session-export.schema.json")["\$defs"]!!
        .jsonObject["set"]!!.jsonObject["properties"]!!.jsonObject

    private fun rpeDescription() = setProperties()["rpe"]!!.jsonObject["description"]!!.jsonPrimitive.content

    /**
     * The noun the rungs can be asked in is a two-value choice today, and the
     * two values are the two the set's KIND selects between.
     *
     * Pinned as an exact list rather than a size, so widening it to carry the
     * exercise's progression is a diff here and not an addition nothing
     * notices.
     */
    @Test
    fun `the headroom noun has exactly two values today, both chosen by the set kind`() {
        assertEquals(listOf("LOAD", "TIME"), EffortAsk.entries.map { it.name })
    }

    /**
     * The timed captions, verbatim. These are the strings #244's owner
     * comment of 2026-09-04 replaces with 15 s and 30 s anchors, so they are
     * transcribed here at the commit before the change.
     */
    @Test
    fun `a hold's three headroom rungs say fifteen-to-thirty, about a minute, and much longer`() {
        assertEquals(
            listOf(
                "Could have gone much longer",
                "Could have gone about a minute longer",
                "Could have gone 15-30 s longer",
            ),
            EffortScale.tiles(timed = true, explosive = false, unit = WeightUnit.LB)
                .filter { it.claim == EffortClaim.HEADROOM }
                .map { it.label },
        )
    }

    /**
     * A dynamic set is asked about LOAD, and nothing about the exercise can
     * change that -- `tiles` takes no progression at all.
     *
     * This is the defect itself, written as a passing test. It reds in the
     * commit that fixes #244 for a `reps` or `none` exercise and is corrected
     * there to assert the same thing of a `weight` exercise only.
     */
    @Test
    fun `every dynamic set is asked about load, whatever the exercise progresses on`() {
        val headroom =
            EffortScale.tiles(timed = false, explosive = false, unit = WeightUnit.LB)
                .filter { it.claim == EffortClaim.HEADROOM }
        assertEquals(
            listOf(
                "Could have added much more",
                "Could have added 20-30 lb",
                "Could have added 10-15 lb",
            ),
            headroom.map { it.label },
        )
    }

    /**
     * The post-set grid's offered row does not depend on the rung.
     *
     * #214 offers the same steps at every headroom tier, and the owner's third
     * comment on #244 confirms that is to stay -- what the rung gains is a
     * SUGGESTED step, not a narrowed offer. Pinned in the direction that would
     * catch a narrowing, which is the change the first two comments asked for
     * and the third withdrew.
     */
    @Test
    fun `the post-set grid offers the same row at every headroom rung`() {
        for (progression in ProgressionKind.entries) {
            val rows =
                HeadroomTier.entries.map { tier ->
                    NextSetNudgePolicy.options(
                        tier = tier,
                        failed = false,
                        warmup = false,
                        setsLeftInExercise = 3,
                        progression = progression,
                        unit = WeightUnit.LB,
                    ).map { it.label }
                }
            assertEquals(
                1,
                rows.toSet().size,
                "$progression offers a different row per rung, which #214 does not do: $rows",
            )
        }
    }

    /**
     * The export carries no word for which scale a rating was given on, so a
     * coach reading a 6 cannot tell a load claim from a rep claim.
     *
     * Both halves: the published schema has no key, and its own description
     * says the fact is unrecoverable. The second sentence is the one that goes
     * false when the key lands.
     */
    @Test
    fun `the published set carries no scale word and says the scale is unrecoverable`() {
        assertFalse("rpeScale" in setProperties().keys, "the export already publishes a scale word")
        assertTrue(
            "nor whether the set drew the load or the time rungs" in rpeDescription(),
            "the published rpe no longer says the drawn rungs are unrecoverable: ${rpeDescription()}",
        )
    }
}
