package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/**
 * One prescribed set the lifter decided not to do, named the way the plan
 * named it (#300).
 *
 * THE RECORD, not the act. [exercise] is the exercise ID -- the same string
 * `ExerciseExport.exercise` publishes, so a reader can join a skip to the sets
 * that were recorded of that movement -- and [setNumber] is the set's own
 * number as the plan prescribed it and the card showed it, counting from one.
 * Neither is a display name: the display name is what the lifter reads on the
 * control, and it is not stable enough to key a reading on.
 *
 * WHAT IT DOES NOT CARRY, decided rather than overlooked: the prescription. A
 * skipped set has no load, no reps and no tempo that were performed, and
 * publishing the numbers it would have carried would put a prescription in the
 * archive that nothing was measured against. What a coach needs from this entry
 * is that the set was prescribed and deliberately not done; the prescription
 * itself is in the plan, where every other figure a session did not reach also
 * stays.
 *
 * WHERE THE SET NUMBER IS AMBIGUOUS, and it is a limit this entry shares with
 * the document it is published in: a session running one movement in two
 * consecutive blocks numbers each block's sets from one, and `SessionExporter`
 * groups the recorded sets by exercise ID, so neither the recorded sets nor
 * this entry can say which of the two blocks it belonged to. A reader takes the
 * entries in order, as it must take the sets.
 */
@Serializable
data class SkippedSet(
    val exercise: String,
    val setNumber: Int,
)

/**
 * The upcoming set a skip would take, and what the control has to say about it.
 *
 * [skipAt] is an index INTO THE QUEUE AS GIVEN, so the caller removes that slot
 * and renumbers nothing -- [RemoveSetControl]'s arrangement, and here it is
 * load-bearing rather than incidental: the sets that remain of the block keep
 * the numbers the plan gave them, which is the whole of what makes the skip
 * readable afterwards. [setNumber] is that slot's own prescribed number,
 * counting from one, and it is stated here rather than left to the caller so
 * that the words on the control, the slot that goes and the record written
 * cannot name three different sets. [lastOfBlock] says no further set of that
 * block follows, which is the one case where skipping a set ends the exercise.
 */
data class SkipSetTarget(
    val skipAt: Int,
    val setNumber: Int,
    val lastOfBlock: Boolean,
)

/**
 * Dropping an upcoming PRESCRIBED set from the queue without ending the session
 * (#300).
 *
 * The owner's ask, in their words: *"I'd also like a mechanism to remove an
 * upcoming set."* Before this there was no such control. Finishing the session
 * dropped the remainder -- every remaining exercise, not one set -- so a lifter
 * who wanted to leave the last squat set and go on to the deadlifts had to
 * choose between doing a set they did not want and abandoning the rest of the
 * session.
 *
 * A THIRD OBJECT RATHER THAN A SECOND ELIGIBILITY ON [RemoveSetControl], and
 * the reason is what each one records. That control takes back a set the LIFTER
 * added and records nothing, because nothing prescribed it and its absence says
 * nothing about the plan. This one drops a set the PLAN prescribed and records
 * a [SkippedSet], because its absence IS a deviation and an archive that cannot
 * say so reads the hole as data loss. One target type whose meaning depended on
 * which eligibility matched would be one flag doing two jobs, and every caller
 * would have to re-derive which.
 *
 * NOT [VoidSetPolicy]. That marks a RECORDED row as not performed and deletes
 * nothing; this drops a slot that has not run, for which no row, no raw stream
 * and no export entry exists -- [SetPlace] names the two places a set can sit,
 * and this control only ever touches [SetPlace.QUEUED].
 *
 * WHICH SET, AND ONLY THAT ONE: the slot the next START would run. Later slots
 * are deliberately out of reach. Reaching them needs a list to pick from, and a
 * picker is a screen rather than a control in a row; a lifter who wants two
 * sets gone taps twice, which is also the only form in which the words on the
 * control keep up with what has already been decided.
 *
 * WHAT IT REFUSES, and both refusals are the point rather than a limitation:
 *
 *  - AN APPENDED upcoming slot. [RemoveSetControl] already takes those back,
 *    and a skip record for one would claim a deviation from a prescription that
 *    never existed. Where several appended sets sit at the end of a block,
 *    repeated taps of that control reach every one of them.
 *  - A BLOCK OPENER, which is `setIndexInExercise == 0`. Dropping the first set
 *    of a block is not dropping a set, it is dropping the exercise: the block
 *    start is what [AddSetControl.blockRange] and
 *    [SetLoadPolicy.sameExerciseBlock] both read to say where a block begins,
 *    and it is what `isExerciseChange` draws the move-the-sensor card from. A
 *    lifter who does not want the exercise at all reroutes with SWITCH EXERCISE
 *    or finishes the session, both of which already exist.
 *
 * A CONSEQUENCE OF THE SECOND REFUSAL, stated because it is what makes this
 * control the third member of the pair rather than a stranger beside it: a slot
 * whose `setIndexInExercise` is above zero is always a continuation of the block
 * before it, so the set this skips is always inside the block
 * [AddSetControl.blockRange] finds from the set just finished -- the same block
 * the add extends and the removal shortens. That is asserted rather than assumed
 * in `SkipSetControlTest`.
 *
 * Nothing in this file touches Android, Room or a sensor.
 */
object SkipSetControl {
    /**
     * The upcoming prescribed set a skip would take, or null when there is none
     * to take.
     *
     * [blocks] is the whole queue projected through [AddSetSlotKey], including
     * sets already done, so indices are into the queue itself. [upcomingIndex]
     * is the slot the next START will run, and during rest it is legitimately
     * one past the queue's last index -- there is nothing coming up, so there is
     * nothing to skip.
     *
     * NO ANCHOR PARAMETER, unlike [AddSetControl.placement] and
     * [RemoveSetControl.target]. Both of those answer a question about the set
     * just FINISHED -- "the load was wrong" is a statement about a set that
     * happened -- so both take a `queueIndex`. This one answers a question about
     * the set that has NOT happened, so the upcoming index is the whole input,
     * and a second parameter it never read would be a claim that the anchor
     * mattered.
     *
     * DECLARED AND NOT YET DECIDED. The eligibility rule arrives red-first:
     * `SkipSetControlTest` states every case of it against this body, which
     * fails with `NotImplementedError` rather than with a wrong answer, so the
     * suite says the rule is ABSENT rather than saying it is broken. The
     * suppression goes with the body.
     */
    @Suppress("UnusedParameter")
    fun target(blocks: List<AddSetSlotKey>, upcomingIndex: Int): SkipSetTarget? = TODO("#300 c3")

    /**
     * What the control says.
     *
     * [exercise] is the display name of the exercise the upcoming set belongs to
     * and [setNumber] is [SkipSetTarget.setNumber]. It opens on the decision
     * rather than on the act, which is the register [AddSetControl]'s "Load was
     * wrong?" and [RemoveSetControl]'s "Added by mistake?" already use: the
     * lifter is answering a question about what they are doing, not picking an
     * operation off a menu. The issue's own wording is the first form.
     *
     * [lastOfBlock] adds what the lifter cannot see from the card, and it is the
     * difference between dropping a set and finishing the exercise: with no
     * further set of that block queued, this tap ends the movement.
     */
    fun label(exercise: String, setNumber: Int, lastOfBlock: Boolean): String = if (lastOfBlock) {
        "Not doing it? Skip Set $setNumber of $exercise, the last one left"
    } else {
        "Not doing it? Skip Set $setNumber of $exercise"
    }

    /**
     * The question the confirmation asks.
     *
     * A skip is confirmed and the removal of an appended set is not, because
     * they cost different things. An appended set can be put back exactly as it
     * was -- ADD SET builds it from the same standing values -- while a
     * prescribed set's own prescription goes with it, and a mis-tap here is a
     * set the lifter meant to do.
     */
    fun confirmTitle(exercise: String, setNumber: Int): String = "Skip Set $setNumber of $exercise?"

    /**
     * What the confirmation says the skip costs, which is the one thing the
     * lifter cannot work out from the screen.
     *
     * ADD SET can queue another set of [exercise], and it is not this set coming
     * back: an appended set carries no `plannedLoad_kg`, no `plannedReps` and no
     * `plannedDuration_s`, publishes `added: true`, and is read as a set the
     * plan did not ask for. So the honest sentence says a set can be added and
     * that it will not be this one.
     */
    fun confirmBody(exercise: String): String =
        "Nothing is recorded for it, and the plan's numbers for it go with it. " +
            "ADD SET can queue another $exercise set, but as one you added, with no prescription."

    /**
     * What the skip records.
     *
     * [exerciseId] is the slot's exercise ID and never its display name: the ID
     * is what `ExerciseExport.exercise` publishes and what a reader joins on.
     * The set number comes off [target] rather than off the queue index, which
     * are different numbers the moment anything has already been skipped or
     * appended.
     */
    fun recordOf(exerciseId: String, target: SkipSetTarget): SkippedSet =
        SkippedSet(exercise = exerciseId, setNumber = target.setNumber)
}
