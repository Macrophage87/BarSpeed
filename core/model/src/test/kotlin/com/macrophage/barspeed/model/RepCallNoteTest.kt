package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prep countdown's one line about where the rep number lands (#266).
 *
 * A phrase pin and nothing more, which is what this side can hold: WHICH sets
 * get the line is `CadencePlan.announcesAtConcentricEnd`'s answer in
 * `:core:dsp`, and the coupling between the two is pinned there because
 * `:core:model` cannot see a cadence plan. Split the way
 * [StartCuePolicy.firstMovementWord] and `StartCueVoiceContractTest` are split,
 * for the same reason.
 */
class RepCallNoteTest {
    @Test
    fun `the note is drawn only on a set whose rep number lands at the end of the drive`() {
        assertEquals(RepCallNotePolicy.AT_DRIVE_END, RepCallNotePolicy.noteFor(repNumberAtDriveEnd = true))
        // Absence is a state, not an empty string: a set that speaks the number
        // at the start of the rep needs no explanation and gets no line.
        assertNull(RepCallNotePolicy.noteFor(repNumberAtDriveEnd = false))
    }

    /**
     * The words themselves, pinned because the owner's argument for the line is
     * that it confirms a habit rather than teaching one: *"People are used to
     * the reps being counted at lockout, so this would be an easy cue."* A line
     * that has to be parsed at the bar is not that.
     *
     * "Drive" and not "lockout", and the reason is a geometry the word would be
     * wrong for: a lat pulldown and a leg curl drive DOWNWARD, and nobody calls
     * the bottom of a pulldown a lockout. The app already says `Drive` aloud
     * for the working stroke of a horizontal lift, so the word is not new to a
     * reader of the screen either.
     */
    @Test
    fun `the note is one short line in the lifter's own words`() {
        val note = RepCallNotePolicy.AT_DRIVE_END
        assertTrue(note.length <= 48, "a line read at the bar, not a sentence: ${note.length} characters")
        assertEquals(1, note.lines().size, "one line, drawn under the #241 phrase")
        assertTrue("drive" in note, "the working stroke is named by the word the app already speaks")
        assertTrue("Rep numbers" in note, "and what it is about is the first thing read")
        assertTrue("lockout" !in note, "which would be false on a lift whose drive goes down")
    }
}
