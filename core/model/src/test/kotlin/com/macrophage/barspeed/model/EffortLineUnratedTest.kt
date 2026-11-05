package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the rest screen's effort line says for a set that carries no rating at
 * all.
 *
 * A consequence of #168, not a cosmetic. Today every timed set is ended by a
 * tap on the effort grid, so a rating always exists by the time the rest
 * screen draws, and the one case that reaches it unrated -- END SET EARLY --
 * is rare enough that the missing line was never reported. Under auto-end the
 * clock ends the set, no tile is tapped, and EVERY hold and carry that meets
 * its target arrives unrated.
 *
 * `RecordScreen`'s effort line returns early when nothing is logged, taking
 * the "Change" button beside it with it. So the lifter would finish a plank,
 * find no effort shown, and find nothing to tap to supply one. RPE is captured
 * once, at set end, and is derivable from nothing -- no reprocessing of the
 * IMU stream rebuilds how hard a hold felt -- so a set that arrives unrated
 * with no way to rate it is unrecoverable loss of the only fatigue signal a
 * timed set carries.
 *
 * The wording rule lives here rather than in the composable for the reason
 * every other decision in this package does: no test on the CI path reaches a
 * Compose screen.
 */
class EffortLineUnratedTest {
    /**
     * A set with no rating and no shortfall says so in words.
     *
     * RED at the commit that adds it: the seam returns the empty string, which
     * is what the screen's early return amounts to. Reds again if the case is
     * ever collapsed back into a blank.
     */
    @Test
    fun `a set carrying no effort statement says it is not rated`() {
        assertEquals(
            EffortCorrectionPolicy.NOT_RATED,
            EffortCorrectionPolicy.lineText(null, failed = false, tappedFailed = false),
        )
    }

    /**
     * An unrated set the LIFTER called failed says so in the word they tapped,
     * and nothing else.
     *
     * Was `an unrated set the app judged short reads as failed`, which passed
     * `failed = true` with no way to say whose failure it was, and so pinned
     * the shared word #139 chose while the state could not tell the two apart.
     * It can now (#169), and that test is RENAMED to the tapped case, which
     * keeps the shipped word. The derive-only case is its own differential.
     *
     * Kept because it is the boundary of the case above: wording the no-facts
     * case must not put "Not rated" next to a failure, which would read as the
     * lifter having declined to rate a set that was already graded.
     */
    @Test
    fun `an unrated set the lifter called failed reads as failed`() {
        assertEquals(
            EffortCorrectionPolicy.FAILED,
            EffortCorrectionPolicy.lineText(null, failed = true, tappedFailed = true),
        )
    }

    /**
     * A rated set reads back the lifter's own wording, and a rated set that
     * also fell short carries both facts in one line.
     *
     * Green against the seam; the two facts stay two facts, which is the rule
     * `SetRatingTracker` keeps separate fields for.
     */
    @Test
    fun `a rated set reads back its own wording, with the shortfall beside it`() {
        assertEquals(
            "Hard — a little left",
            EffortCorrectionPolicy.lineText("Hard — a little left", failed = false, tappedFailed = false),
        )
        assertEquals(
            "Hard — a little left · short of target",
            EffortCorrectionPolicy.lineText("Hard — a little left", failed = true, tappedFailed = false),
        )
    }

    /**
     * The line is never blank for a stored set, whatever combination it
     * carries.
     *
     * The property the fix has to deliver, stated as a property rather than as
     * four cases: a blank line is the failure -- it is drawn, occupies the
     * place the lifter looks, and says nothing. RED at this commit on the one
     * combination the seam still blanks.
     */
    @Test
    fun `no combination leaves the line blank`() {
        listOf(null, "Warm-up — barely work", "Max — hit my limit").forEach { description ->
            listOf(false to false, true to false, true to true).forEach { (failed, tapped) ->
                val text = EffortCorrectionPolicy.lineText(description, failed, tapped)
                assertEquals(true, text.isNotBlank(), "description=$description failed=$failed tapped=$tapped")
            }
        }
    }

    /**
     * #169 item 2, the owner's default: a failure the APP derived from a
     * short count says so in its own words, and only the lifter's own tap
     * reads "Failed". The shared word made the app's judgement read as the
     * lifter's.
     */
    @Test
    fun `an unrated set the app alone judged short reads as short of target`() {
        assertEquals(
            EffortCorrectionPolicy.SHORT_OF_TARGET,
            EffortCorrectionPolicy.lineText(null, failed = true, tappedFailed = false),
        )
    }
}
