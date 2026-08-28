package com.macrophage.barspeed.model

/**
 * A plan's coaching text for one set, split by whether the lifter has to touch
 * the phone to read it.
 *
 * [visible] is drawn where the note has always been drawn; [behindTap] is drawn
 * only once the lifter expands it. Either may be null, and null is a distinct
 * state from an empty string: an empty string still draws a quote mark, a
 * spacer and a gap on a screen the whole point of this is to reclaim.
 */
data class NoteDisplay(val visible: String?, val behindTap: String?)

/**
 * Which of an exercise's coaching keys the lifter reads between sets, and which
 * one tap reveals.
 *
 * The decision lives here rather than in `PlanQueue` for the reason
 * [SetGeometryPolicy] gives: `:app` has no test seam this repo can run against,
 * and "which text is hidden behind a tap" is the kind of decision that is
 * invisible when it goes wrong. A cue that never appears looks exactly like a
 * cue nobody wrote.
 *
 * This commit reproduces the split as it stands — the exercise's `notes` and
 * the set's own `note` joined, nothing behind a tap. [forSet] already takes
 * `description` and `additionalNotes` and ignores them, so the signature is
 * settled before the behaviour moves and the differentials against it can be
 * written first.
 */
object PlanNoteDisplay {
    /** Joins the parts of one visible line, as `PlanQueue` has always joined them. */
    private const val JOIN = " · "

    /**
     * [description] and [additionalNotes] are the 1.8 keys and are not read
     * yet. [notes] is the exercise-level key every plan written so far uses;
     * [setNote] is the set's own `note`, which has never rendered on its own.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forSet(description: String?, additionalNotes: String?, notes: String?, setNote: String?): NoteDisplay =
        NoteDisplay(
            visible = listOfNotNull(notes, setNote).takeIf { it.isNotEmpty() }?.joinToString(JOIN),
            behindTap = null,
        )
}
