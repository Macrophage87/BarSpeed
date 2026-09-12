package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The sets that owe the rest screen's effort question NOTHING, pinned before the
 * differential that decides which ones do (#283).
 *
 * Every case here is true at this commit AND after the fix, which is what makes
 * it a baseline rather than a characterization to be rewritten: the cases that
 * CHANGE are in `RestEffortPromptDifferentialTest`, pushed red at their own SHA.
 * Nothing in this file asserts the timed-and-unrated answer, because that is
 * the answer the fix moves.
 *
 * What these pins are FOR is the direction the fix is most likely to overreach
 * in. The seam answers null for everything today, so both of these pass
 * trivially at this commit; they earn their keep at the fix, where dropping the
 * `!timed` guard would put an effort grid on the rest screen after every rep
 * set, and dropping the rated guard would ask a set that had just been rated
 * the same question again with its own answer not lit. Both mutations are in
 * the commit body's table.
 */
class RestEffortPromptBaselineTest {
    @Test
    fun `a rep set owes no question however it ended`() {
        for (rpe in listOf(null, 1, 6, 10)) {
            for (tapped in listOf(false, true)) {
                for (derived in listOf(false, true)) {
                    assertNull(
                        RestEffortPromptPolicy.prompt(
                            timed = false,
                            rpe = rpe,
                            tappedFailed = tapped,
                            derivedFailed = derived,
                        ),
                        "a rep set was asked again on the rest screen: rpe=$rpe tapped=$tapped derived=$derived",
                    )
                }
            }
        }
    }

    @Test
    fun `a set already carrying a rating is never asked a second time`() {
        // Every anchored rung the grid can store, plus one unanchored value
        // that is valid in the column and carries no tile: a stored 3 is still
        // a rating the lifter gave and is not an absence to be filled in.
        for (rpe in listOf(1, 3, 4, 6, 7, 8, 9, 10)) {
            for (timed in listOf(false, true)) {
                for (tapped in listOf(false, true)) {
                    assertNull(
                        RestEffortPromptPolicy.prompt(
                            timed = timed,
                            rpe = rpe,
                            tappedFailed = tapped,
                            derivedFailed = false,
                        ),
                        "a set rated $rpe was asked again: timed=$timed tapped=$tapped",
                    )
                }
            }
        }
    }
}
