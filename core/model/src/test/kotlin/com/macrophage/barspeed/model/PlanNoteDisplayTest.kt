package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the lifter reads between sets without touching the phone, and what one
 * tap reveals.
 *
 * The first five pins were already true when they were written -- they are what
 * `PlanQueue` did inline, moved somewhere a test can run on it. The rest are
 * differentials, red against the commit that introduced the seam: which key
 * takes the visible line, what one tap reveals, what order the hidden parts
 * come in, and that a blank string is absent rather than a quote mark and a
 * gap (`listOfNotNull` drops a null and keeps a `""`, which is what shipped).
 *
 * Nothing here touches Android, a screen or a plan document — four strings in,
 * two strings out.
 */
class PlanNoteDisplayTest {
    private fun display(
        description: String? = null,
        additionalNotes: String? = null,
        notes: String? = null,
        setNote: String? = null,
    ) = PlanNoteDisplay.forSet(description, additionalNotes, notes, setNote)

    @Test
    fun `an exercise declaring no coaching text at all shows nothing`() {
        val shown = display()

        assertNull(shown.visible, "nothing was declared, so nothing is drawn")
        assertNull(shown.behindTap, "an expand affordance with nothing behind it is worse than none")
    }

    @Test
    fun `the exercise note alone is what shows, as it always has`() {
        val shown = display(notes = "Brace before the first rep.")

        assertEquals("Brace before the first rep.", shown.visible)
        assertNull(shown.behindTap)
    }

    @Test
    fun `a set's own note shows even when the exercise declares nothing`() {
        val shown = display(setNote = "closer to the plate")

        assertEquals("closer to the plate", shown.visible)
        assertNull(shown.behindTap)
    }

    @Test
    fun `the exercise note comes first and the set's note second`() {
        // The order is the general cue then the correction to it, which is how
        // it reads out loud. Reversing them reads as a correction to nothing.
        val shown = display(notes = "Brace hard.", setNote = "closer to the plate")

        assertEquals("Brace hard. · closer to the plate", shown.visible)
    }

    @Test
    fun `a blank note is absent, not a quote mark and a gap`() {
        // Was a characterization pin in the commit before this one, recording
        // what `listOfNotNull` does: it drops a null and keeps a `""`. A
        // generating model that emits "notes": "" for an exercise it has
        // nothing to say about drew a quote mark and a gap. The split makes it
        // worse rather than merely ugly -- a blank "description" would take the
        // visible slot and push a real "notes" behind the tap -- so blank means
        // absent everywhere here.
        assertNull(display(notes = "").visible)
        assertEquals("closer to the plate", display(notes = "", setNote = "closer to the plate").visible)
        assertEquals("Brace hard.", display(description = "   ", notes = "Brace hard.").visible)
        assertNull(display(description = "Brace hard.", additionalNotes = "").behindTap)
    }

    @Test
    fun `a description is what shows, and it is all that shows`() {
        val shown = display(description = "Brace, then break at the hips.")

        assertEquals("Brace, then break at the hips.", shown.visible)
        assertNull(shown.behindTap, "an exercise declaring only a description has nothing behind the tap")
    }

    @Test
    fun `additional notes are behind the tap and never in the visible line`() {
        val shown =
            display(
                description = "Brace, then break at the hips.",
                additionalNotes = "Set the safeties at the bottom of your range before the first set.",
            )

        assertEquals("Brace, then break at the hips.", shown.visible)
        assertEquals("Set the safeties at the bottom of your range before the first set.", shown.behindTap)
    }

    @Test
    fun `an exercise declaring both keys loses neither`() {
        // The author wrote a description AND kept the old blob. The description
        // is the newer, deliberate statement of what the lifter reads at a
        // glance, so it takes the visible slot -- but the blob is text somebody
        // wrote and must still be reachable. The import gate says which of the
        // two is which; nothing here drops either.
        val shown = display(description = "Brace, then break at the hips.", notes = "Third week of the block.")

        assertEquals("Brace, then break at the hips.", shown.visible)
        assertEquals("Third week of the block.", shown.behindTap)
    }

    @Test
    fun `the old blob comes before the additional notes behind the tap`() {
        val shown =
            display(
                description = "Brace, then break at the hips.",
                additionalNotes = "Safeties at the bottom of your range.",
                notes = "Third week of the block.",
            )

        assertEquals("Brace, then break at the hips.", shown.visible)
        assertEquals("Third week of the block.\n\nSafeties at the bottom of your range.", shown.behindTap)
    }

    @Test
    fun `additional notes alone stay behind the tap rather than being promoted`() {
        // Promoting them would put a paragraph back on the rest screen, which
        // is the defect this change exists to remove. The author gets told at
        // the import gate instead; the app does not overrule them on screen.
        val shown = display(additionalNotes = "A paragraph about bar position.")

        assertNull(shown.visible, "nothing was declared for the visible line")
        assertEquals("A paragraph about bar position.", shown.behindTap)
    }

    @Test
    fun `a plan written before the split puts all its text on the visible line`() {
        // The 1.7 case, pinned as its own test rather than left implied by the
        // two above: an already-staged plan carrying only `notes` keeps all of
        // it on the visible line and puts none of it behind the tap. Whether
        // the drawn block gains a SHOW MORE control is `ExpandableNote`'s
        // decision, and nothing in this module reaches it.
        val shown = display(notes = "Keep the eccentric honest.", setNote = "closer to the plate")

        assertEquals("Keep the eccentric honest. · closer to the plate", shown.visible)
        assertNull(shown.behindTap, "nothing an old plan wrote may move behind a tap")
    }

    @Test
    fun `a set's own note stays visible beside the description`() {
        // The set note is the most specific thing anyone wrote about the set
        // in front of the lifter, and it is short by construction. It stays
        // where it has always been: on the visible line, after the cue.
        val shown = display(description = "Brace, then break at the hips.", setNote = "closer to the plate")

        assertEquals("Brace, then break at the hips. · closer to the plate", shown.visible)
    }
}
