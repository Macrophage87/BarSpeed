package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seams #244 was built on, corrected at the commit that fills them.
 *
 * TWO OF THESE RECORDED THE BEFORE and are now inverted, in place and named:
 * [EffortScale.askFor] read the exercise's declaration and discarded it, and
 * [NextSetNudgePolicy.suggestedStep] answered null for every rung. Both are
 * false now. They are not deleted, because what each pins is still the thing
 * worth pinning -- that the decision reads the declaration at all, and that
 * the rung picks something -- and because a reader of this file should be able
 * to see what the behaviour was on the other side of the change.
 *
 * The full per-cell table lives in `HeadroomScaleDifferentialTest`, which is
 * where it was shown red; these are the two shapes of the answer rather than
 * its contents.
 *
 * The wire-vocabulary pin below was never a seam and is expected to hold
 * permanently: [SessionExport.VALID_RPE_SCALES] and [EffortAsk] are two
 * statements of one closed list, one of them published, and this is the only
 * thing that can see both.
 */
class HeadroomScaleContractTest {
    /**
     * The published word list and the enum that produces it agree, in BOTH
     * directions.
     *
     * Both directions because the two failures differ: a word in the schema
     * with no enum constant is a value the app can never write, and an enum
     * constant with no word is a rating the app can take and the archive
     * cannot name.
     */
    @Test
    fun `the published scale words are exactly the asks the app can make`() {
        assertEquals(
            EffortAsk.entries.map { it.word }.toSet(),
            SessionExport.VALID_RPE_SCALES,
            "VALID_RPE_SCALES and EffortAsk disagree about which scales exist",
        )
        assertEquals(
            EffortAsk.entries.size,
            SessionExport.VALID_RPE_SCALES.size,
            "two asks share one published word, so the archive cannot tell them apart",
        )
    }

    /**
     * No word is a Kotlin name that happens to lowercase correctly by
     * accident -- each is spelled out on its constant.
     */
    @Test
    fun `every scale word is lower case and matches its constant`() {
        EffortAsk.entries.forEach {
            assertEquals(it.name.lowercase(), it.word, "${it.name} publishes a word that is not its own name")
        }
    }

    // ---- the seams, answering what the app answers today ----

    /**
     * The ask READS the exercise's declaration.
     *
     * IT SAID `askFor ignores the progression and answers from the set kind`
     * and asserted LOAD for every declaration on a dynamic set and TIME for
     * every declaration on a timed one. That was the seam, and it was the
     * defect: it is why a pull-up block declared `"reps"` was asked how much
     * more WEIGHT it had in it. Inverted here rather than deleted -- the
     * property worth pinning is that three of the four declarations move the
     * answer at all, which is the whole of #244.
     *
     * Only WEIGHT still defers to the kind, and that is the design: it is what
     * an omitted key resolves to, so deferring is what keeps every plan
     * written before schema 1.11 asking exactly what it always asked.
     */
    @Test
    fun `askFor reads the progression, and only weight still defers to the set kind`() {
        listOf(false, true).forEach { timed ->
            assertEquals(EffortAsk.REPS, EffortScale.askFor(timed, ProgressionKind.REPS), "reps, timed=$timed")
            assertEquals(EffortAsk.TIME, EffortScale.askFor(timed, ProgressionKind.TIME), "time, timed=$timed")
            assertEquals(EffortAsk.FEEL, EffortScale.askFor(timed, ProgressionKind.NONE), "none, timed=$timed")
        }
        assertEquals(EffortAsk.LOAD, EffortScale.askFor(timed = false, progression = ProgressionKind.WEIGHT))
        assertEquals(EffortAsk.TIME, EffortScale.askFor(timed = true, progression = ProgressionKind.WEIGHT))
    }

    /**
     * A null declaration and a declared `"weight"` are the SAME answer, and
     * they must stay the same answer after the fix.
     *
     * This is the half of the seam that does not move: an omitted
     * `progression` key means weight ([ProgressionKind.ofPlan]), so no set
     * recorded against a plan written before schema 1.11 is asked a different
     * question than it was.
     */
    @Test
    fun `an omitted declaration and an ad-hoc set ask exactly what weight asks`() {
        listOf(false, true).forEach { timed ->
            assertEquals(
                EffortScale.askFor(timed, ProgressionKind.WEIGHT),
                EffortScale.askFor(timed, null),
                "an omitted progression asks a different question from a declared weight (timed=$timed)",
            )
        }
    }

    /**
     * Every rung suggests exactly one of the offered tiles.
     *
     * IT SAID `suggestedStep suggests nothing yet` and asserted null for every
     * rung, which was the seam. Inverted here: WHICH tile each rung picks is
     * pinned per progression in `HeadroomScaleDifferentialTest`, and what this
     * keeps is the shape -- three rungs, three answers, all of them drawn from
     * the row that was offered, never a fourth step the grid does not show.
     */
    @Test
    fun `every rung suggests one of the tiles the grid actually offers`() {
        val offered =
            NextSetNudgePolicy.options(
                tier = HeadroomTier.ONE_INCREMENT,
                failed = false,
                warmup = false,
                setsLeftInExercise = 3,
                progression = ProgressionKind.WEIGHT,
                unit = WeightUnit.LB,
            )
        HeadroomTier.entries.forEach {
            val pick = NextSetNudgePolicy.suggestedStep(it, offered)
            assertNotNull(pick, "$it suggests nothing")
            assertTrue(pick in offered, "$it suggests a tile the grid does not offer: $pick")
        }
        assertNull(NextSetNudgePolicy.suggestedStep(null, offered), "an unrated set is offered a step")
    }
}
