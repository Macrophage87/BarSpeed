package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the lifter reads between sets without touching the phone, and what one
 * tap reveals.
 *
 * The pins in this class are the ones that are already right at the commit that
 * writes them, plus a characterization of one thing that is wrong and stays
 * wrong until the split lands: a blank string is joined in as though it were
 * text. `PlanQueue` has always built this line with
 * `listOfNotNull(exerciseDef.notes, set.note)`, and `listOfNotNull` drops a
 * null and keeps a `""`.
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
    fun `a blank note is joined in as though it were text`() {
        // Characterization, not an endorsement. This is what ships today and it
        // is reachable: a generating model that emits "notes": "" for an
        // exercise it has nothing to say about gets a quote mark and a gap on
        // the rest screen. The differential that makes blank mean absent is in
        // the commit after this one.
        assertEquals("", display(notes = "").visible)
        assertEquals(" · closer to the plate", display(notes = "", setNote = "closer to the plate").visible)
    }
}
