package com.macrophage.barspeed.model

/**
 * Which appended set "Remove the set you added" takes back, and what the
 * caller has to re-seed when it goes.
 *
 * [removeAt] is an index INTO THE QUEUE AS GIVEN, so the caller removes that
 * slot and renumbers nothing. [wasUpcoming] says the slot removed is the one
 * the next START would have run, which is what tells the caller its editable
 * boxes were seeded from a set that no longer exists -- the mirror of
 * [AddSetPlacement.becomesNextSet]. [removableCount] is how many appended sets
 * of that exercise are still eligible, INCLUDING the one named here, so a
 * control drawn from this can say which of several it will take.
 */
data class RemoveSetTarget(
    val removeAt: Int,
    val wasUpcoming: Boolean,
    val removableCount: Int,
)

/**
 * Taking back a set the lifter appended, #177's named remainder (#206).
 *
 * Beside [AddSetControl] and reading its [AddSetControl.blockRange], because
 * the two are one decision seen from both ends: the add extends a block and
 * the removal shortens the same block, and a second statement of "what is a
 * block" would be a second rule.
 *
 * THE BOUNDARY THIS DRAWS, and it is the whole reason the eligibility is
 * stated in code rather than only in the issue. Removing a QUEUED appended set
 * discards a plan for a set that has not happened: nothing recorded is lost.
 * Removing a RECORDED set would delete a row of training history with its
 * samples, its raw stream and its export entry. Only the first is offered
 * here, and the rule that separates them is `>= upcomingIndex`: every slot
 * before the upcoming one has already run.
 *
 * ELIGIBILITY IS `isAddedSet` AND NOTHING ELSE BESIDES THAT BOUNDARY. A slot
 * the PLAN prescribed is never removable, however unwanted -- a plan's set
 * count is how a coach reads adherence, and dropping prescribed sets would
 * corrupt exactly the reading `isAddedSet` was introduced to protect. A
 * prescribed set the lifter does not want is disposed of the way every
 * unwanted queued set always has been: by finishing the session, which drops
 * the remainder.
 *
 * WHICH ONE, WHEN THERE ARE SEVERAL: the LAST appended set of the anchor's
 * block, and this object offers no way to say otherwise. Stated rather than
 * left to list ordering, which is #206 requirement 5. It is the mirror of the
 * add's placement -- an appended set goes on the end of the block, so the last
 * one on is the first one off -- and it is the only choice that makes two taps
 * of the control undo two taps of the add. [RemoveSetTarget.removableCount]
 * exists so the control can SAY it is taking the last one at the moment there
 * is more than one to take.
 *
 * REPEATABLE, BOTH WAYS. Removing one appended set disturbs no other: the
 * result is a shorter queue whose remaining appended slots keep their own
 * carried load, reps and tempo, and a further removal simply finds the next
 * last one. Nothing here assumes at most one addition or at most one removal.
 *
 * Nothing in this file touches Android, Room or a sensor.
 */
object RemoveSetControl {
    /**
     * The appended set to remove, or null when there is none to take.
     *
     * [blocks] is the whole queue projected through [AddSetSlotKey], including
     * sets already done, so indices are into the queue itself. [queueIndex] is
     * the slot being recorded or set up and names the block; [upcomingIndex]
     * is the slot the next START will run, and doubles as the line between
     * what has run and what has not.
     *
     * THE ANCHOR IS [queueIndex], as [AddSetControl.placement]'s is. The
     * control sits beside the add and has to refer to the same exercise the
     * add does, or the pair reads as two unrelated controls rather than one
     * decision -- and at a block boundary the upcoming slot is a different
     * exercise entirely.
     *
     * c1 DECLARES THIS SEAM AND DOES NOT IMPLEMENT IT. The body is `TODO`
     * rather than a plausible-looking `null`, so that the differentials
     * pushed red in c2 fail because the rule is ABSENT and cannot be mistaken
     * for a rule that ran and disagreed. The rule lands in c3, after c2's red
     * has its own CI run at its own SHA.
     */
    fun target(blocks: List<AddSetSlotKey>, queueIndex: Int, upcomingIndex: Int): RemoveSetTarget? =
        TODO("#206 c3: which of ${blocks.size} slots to remove, anchored at $queueIndex, run to $upcomingIndex")

    /**
     * What the control says.
     *
     * [anchorExercise] is the display name of the exercise the appended set
     * belongs to and [setNumber] is the set's own number as the card shows it,
     * counting from one. [several] says more than one appended set of that
     * exercise is eligible, which is the only moment "the last" is worth
     * saying: with one there is nothing to disambiguate, and naming an
     * ordering the lifter cannot see would be noise.
     *
     * c1 DECLARES THIS AND DOES NOT IMPLEMENT IT, for [target]'s reason: an
     * empty string is a wording, and a differential red against one cannot be
     * told from a differential red against the wrong words.
     */
    fun label(anchorExercise: String, setNumber: Int, several: Boolean): String =
        TODO("#206 c3: the wording for set $setNumber of $anchorExercise, several=$several")
}
