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
 * What is claimed here is what this function computes. Whether a lifter with
 * the phone on the floor notices there is more behind the tap is a question no
 * test in this repository can answer, and it is a [Field] item on #155.
 *
 * Precedence, and the reason for each step:
 *
 *  - `description` is the author's own statement of what the lifter needs at a
 *    glance, and the only key with a length limit. It takes the visible line.
 *  - `notes` takes it when there is no `description`. Every plan written before
 *    schema 1.8 is that shape, and a staged plan must not lose text or gain a
 *    tap because a newer key exists.
 *  - When BOTH are declared, `notes` moves behind the tap rather than being
 *    dropped, and comes out ahead of `additional_notes` — it is the older
 *    statement, so it reads as context for the newer one rather than as a
 *    continuation of it. The import gate says which of the two the lifter sees.
 *  - `additional_notes` is never on the visible line, even when it is the only
 *    thing declared. Promoting it would put the paragraph back on the screen
 *    this split exists to clear; the gate warns instead of overruling.
 *  - The set's own `note` stays on the visible line after the cue. It is the
 *    most specific thing anyone wrote about the set in front of the lifter.
 *
 * Blank is absent throughout. A generating model that emits `"description": ""`
 * would otherwise take the visible line with nothing in it and push a real
 * `notes` behind a tap — text lost to a key that says nothing.
 */
object PlanNoteDisplay {
    /** Joins the parts of one visible line, as `PlanQueue` has always joined them. */
    private const val JOIN = " · "

    /** Separates two blocks of prose behind the tap; neither is a phrase in the other's sentence. */
    private const val BLOCK_JOIN = "\n\n"

    /**
     * [description] and [additionalNotes] are the 1.8 exercise keys, [notes]
     * the older single key, [setNote] the set's own `note` — which has never
     * rendered on its own and does not start here.
     */
    fun forSet(description: String?, additionalNotes: String?, notes: String?, setNote: String?): NoteDisplay {
        val cue = description.declared()
        val blob = notes.declared()
        return NoteDisplay(
            visible = listOfNotNull(cue ?: blob, setNote.declared()).joined(JOIN),
            behindTap = listOfNotNull(blob.takeIf { cue != null }, additionalNotes.declared()).joined(BLOCK_JOIN),
        )
    }

    private fun String?.declared(): String? = this?.takeIf { it.isNotBlank() }

    private fun List<String>.joined(separator: String): String? = takeIf { it.isNotEmpty() }?.joinToString(separator)
}
