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
 * quiet reword. Each is corrected forward at the commit that makes it false,
 * naming what it used to say; none is deleted, because the thing each pins is
 * still worth pinning after it moves.
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
     * The noun the rungs can be asked in, as an exact list.
     *
     * IT SAID `listOf("LOAD", "TIME")` at the characterization commit and that
     * is now FALSE -- this commit widens the enum, which is exactly the diff
     * the pin was written to force. Corrected forward rather than deleted: the
     * list is still the thing worth pinning, because a fifth value is a fifth
     * published `rpeScale` word and must not arrive unnoticed.
     *
     * The ORDER is asserted too, and it is the ladder's own: load and time are
     * the two the set's kind selects between, reps and feel the two the
     * exercise's declaration adds.
     */
    @Test
    fun `the headroom noun has exactly four values, two by kind and two by declaration`() {
        assertEquals(listOf("LOAD", "REPS", "TIME", "FEEL"), EffortAsk.entries.map { it.name })
    }

    /**
     * The timed captions, verbatim.
     *
     * IT SAID "Could have gone 15-30 s longer" and "about a minute longer" at
     * the characterization commit, and both are now FALSE: the owner's comment
     * of 2026-09-04 -- *"For holds let's do 15 sec and 30 sec."* -- replaces
     * them, and forcing that diff is what this pin was written for. Corrected
     * forward rather than deleted, because a stored timed 4 means one thing
     * before this branch and another after it, and a transcription of both is
     * how a reader of the archive can tell.
     */
    @Test
    fun `a hold's three headroom rungs say about fifteen, about thirty, and much longer`() {
        assertEquals(
            listOf(
                "Could have gone much longer",
                "Could have gone about 30 s longer",
                "Could have gone about 15 s longer",
            ),
            EffortScale.tiles(true, explosive = false, unit = WeightUnit.LB, ask = EffortAsk.TIME)
                .filter { it.claim == EffortClaim.HEADROOM }
                .map { it.label },
        )
    }

    /**
     * A dynamic set on a WEIGHT-progression exercise is asked about load.
     *
     * IT SAID "every dynamic set is asked about load, whatever the exercise
     * progresses on" at the characterization commit, which was the defect
     * itself written as a passing test. It is false now for `reps` and `none`,
     * and the assertion is narrowed to the case that survives rather than
     * deleted -- what a weight exercise asks has NOT changed, and that is
     * worth going on pinning.
     */
    @Test
    fun `a dynamic weight-progression set is asked about load`() {
        val headroom =
            EffortScale.tiles(false, explosive = false, unit = WeightUnit.LB, ask = EffortAsk.LOAD)
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
     * The export now carries the word, and no longer says it cannot.
     *
     * IT SAID `the published set carries no scale word and says the scale is
     * unrecoverable` at the characterization commit and asserted exactly the
     * two things this branch reverses. Both halves are inverted here rather
     * than deleted: what the pin is FOR is that the two statements move
     * together, and a key that lands beside a description still saying it does
     * not exist is the drift class this repository keeps shipping.
     *
     * The narrower claim survives untouched and is asserted with them: which
     * UNIT'S caption was on screen is still not recorded, and never was.
     */
    @Test
    fun `the published set carries the scale word and no longer calls it unrecoverable`() {
        assertTrue("rpeScale" in setProperties().keys, "the export does not publish the scale word")
        assertFalse(
            "nor whether the set drew the load or the time rungs" in rpeDescription(),
            "the published rpe still says the drawn rungs are unrecoverable: ${rpeDescription()}",
        )
        assertTrue(
            "DOES NOT RECORD which unit's caption was on screen" in rpeDescription(),
            "the published rpe stopped saying the display unit is unrecoverable, which it still is",
        )
    }
}
