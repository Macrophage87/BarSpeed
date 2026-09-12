package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RED AT THIS COMMIT: four of the five tests below (#283).
 *
 * The owner's rule, which this file pins and which the tree at
 * `edaec37e7df144361379a8ba77cfb2442c3b0291` violates: *"Don't ask for an RPE on
 * failed sets, if you can't do it, it's failed."* So the rest screen's effort
 * question is owed by ONE set and no other -- a timed set that ran its clock and
 * carries no rating. A hold the lifter ended with the failure control owes
 * nothing, and a hold the app judged short owes nothing either.
 *
 * `RestEffortPromptPolicy.prompt` at this commit answers non-null for EVERY
 * unrated timed set, failures included, and hands the screen a pair saying
 * whether to draw a failure tile and what a tap must carry back as `failed`. The
 * four tests that name a failed hold therefore fail here on `assertNull`, and so
 * does the cross-product contract, which fails on the three failed-and-unrated
 * timed combinations. The fifth test -- the clean hold that IS asked -- passes at
 * this commit and after the fix, and is written out here rather than in the
 * baseline file because it is the surviving half of the same rule: it is labelled
 * green rather than quietly counted among the reds.
 *
 * Nothing here reads a field of the returned prompt. Under the owner's rule the
 * question is drawn only for a hold that met its target, so there is no failure
 * tile to decide about and every tap carries `failed = false`; the pair
 * `failedTile`/`carriesFailed` no longer varies and the fix deletes it. That
 * leaves PRESENCE as the whole answer, which is what these assertions read, and
 * it is also why they compile unchanged against both trees -- the red is an
 * assertion failure rather than a compile error, and the fix commit touches no
 * test file.
 */
class RestEffortPromptDifferentialTest {
    @Test
    fun `a hold the lifter ended with the failure control is not asked how hard it was`() {
        assertNull(
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = true,
                derivedFailed = false,
            ),
            "a hold the lifter gave up on was asked for a rating anyway",
        )
    }

    @Test
    fun `a hold the lifter broke early and the app also judged short is not asked`() {
        // Field session 38's two dead hangs are this pair: the lifter tapped the
        // failure control AND the recorded seconds fell below
        // TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION of the prescription, so both
        // facts stand on one row. Neither of them owes a question.
        assertNull(
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = true,
                derivedFailed = true,
            ),
            "a hold that failed by both routes was asked for a rating",
        )
    }

    @Test
    fun `a hold the app judged short of its target is not asked how hard it was`() {
        // The shortfall the lifter never tapped: a timed set ended during its
        // LEAD-IN, where SetEndControlPolicy.controls answers END_UNRATED and
        // the write derives the failure from the near-zero count. "If you can't
        // do it, it's failed" covers this one too -- the set did not deliver its
        // clock, so there is nothing to rate.
        assertNull(
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = false,
                derivedFailed = true,
            ),
            "a hold recorded short of its target was asked for a rating",
        )
    }

    @Test
    fun `a hold that ran its clock unrated is the one set still asked`() {
        // GREEN at this commit as well as after the fix. Nothing on the row says
        // this set fell short -- TimedSetEndPolicy.recordedSeconds stores the
        // target itself on an auto-ended hold -- and no rating stands, so this
        // is the hold the owner watched finish and walk on unasked.
        assertNotNull(
            RestEffortPromptPolicy.prompt(
                timed = true,
                rpe = null,
                tappedFailed = false,
                derivedFailed = false,
            ),
            "a hold that ended on its own clock was never asked how it went",
        )
    }

    @Test
    fun `only an unrated timed set with no failure standing is asked`() {
        // The contract over the whole cross product the policy takes, so that
        // widening it in any of the four inputs is a visible failure here and
        // not a case nobody enumerated.
        for (timed in listOf(false, true)) {
            for (rpe in listOf(null, 1, 6, 10)) {
                for (tapped in listOf(false, true)) {
                    for (derived in listOf(false, true)) {
                        val asked =
                            RestEffortPromptPolicy.prompt(
                                timed = timed,
                                rpe = rpe,
                                tappedFailed = tapped,
                                derivedFailed = derived,
                            ) != null
                        assertEquals(
                            timed && rpe == null && !tapped && !derived,
                            asked,
                            "wrong ask: timed=$timed rpe=$rpe tapped=$tapped derived=$derived",
                        )
                    }
                }
            }
        }
    }
}
