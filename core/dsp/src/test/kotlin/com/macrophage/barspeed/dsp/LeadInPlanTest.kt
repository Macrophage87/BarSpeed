package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prep before a guided set, as [LeadInPlan] lays it out second by second.
 *
 * This arithmetic is here rather than in `GuidedCadenceRunner` because `:app`
 * has no test source set. The last per-second lead-in arithmetic that lived
 * there was wrong on every set the app had ever paced -- issue 106 -- and
 * nothing could say so.
 *
 * Nothing in this file observes TTS, a speaker or a lifter. It asserts what the
 * plan says to say and when, never what was heard.
 */
class LeadInPlanTest {
    @Test
    fun `every prep is exactly as many seconds as it prescribes`() {
        // Issue 106's guard, transplanted. The defect there was a beat the
        // prescription did not ask for, inserted by the player; the plan is
        // now the only thing that decides how many seconds a prep is, so the
        // count is asserted directly rather than inferred from a cue track.
        for (p in 0..30) {
            assertEquals(p, LeadInPlan.of(p).beats.size, "prep $p must be $p beats")
            assertEquals(p, LeadInPlan.of(p).prepS, "prep $p must report itself as $p")
        }
        // And the beats run from the far end of the prep down to the last
        // second before the first stroke, one per second with no gaps.
        val plan = LeadInPlan.of(5)
        assertEquals(
            listOf(5, 4, 3, 2, 1),
            plan.beats.indices.map { plan.secondsBeforeStart(it) },
            "seconds before the first stroke, beat by beat",
        )
    }

    @Test
    fun `a word is never recorded that was not spoken`() {
        // The cue track is a record of what the app SAID. A row for a second
        // that passed in silence would be a fabricated instruction, and the
        // consumers that measure "first cue to first movement" would time it
        // from a moment nothing happened.
        for (p in 0..30) {
            LeadInPlan.of(p).beats.forEachIndexed { index, beat ->
                if (beat.cue != null) {
                    assertTrue(
                        beat.spoken != null,
                        "prep $p beat $index records ${beat.cue} without speaking it",
                    )
                }
                // Absence is null, never the empty string: an empty cue would
                // read as a row to every consumer that only checks for null.
                assertTrue(beat.cue != "", "prep $p beat $index has an empty-string cue")
                assertTrue(beat.spoken != "", "prep $p beat $index has an empty-string utterance")
            }
        }
    }
}
