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
 *
 * ## Mutation coverage, run at e7311b41f5e053edf17e2dac0e8ddcb25734a69e
 *
 * `:core:model:test --tests RepCallNoteTest` is 2 tests total in every run
 * below.
 *
 * 1. Inverting `noteFor`'s condition (`if (repNumberAtDriveEnd)` to
 *    `if (!repNumberAtDriveEnd)`) reds `the note is drawn only on a set whose
 *    rep number lands at the end of the drive` -- 1/2.
 * 2. Rewording [RepCallNotePolicy.AT_DRIVE_END] to drop "drive" (to "Rep
 *    numbers mark the end of each working stroke") reds `the note is one
 *    short line in the lifter's own words` -- 1/2.
 * 3. Forcing `VelocityLossRegime.of` off [VelocityLossRegime.CONTROLLED] for
 *    every numbered tempo, 1010 included (`ofTempo`'s
 *    `!tempo.isExplosiveUpStroke -> CONTROLLED` branch changed to
 *    `-> MAX_INTENT`), reds nothing in THIS file -- 0/2, because this file
 *    holds no pin on `VelocityLossRegime` at all. It reds six tests in
 *    `VelocityLossRegimeTest` and `SchemaVelocityLossRegimeContractTest`
 *    instead, run at the same SHA with `:core:model:test --rerun-tasks`:
 *    `every tempo field-38 prescribed is controlled`, `a numbered concentric
 *    digit is controlled`, `an X concentric with no drive direction is
 *    undecidable rather than guessed`, `an X in digit 3 is the eccentric on a
 *    drive that moves down, so the set is controlled`, `a horizontal set with
 *    a numbered digit 3 is controlled`, and `every regime word in the example
 *    is the one the decision derives for that set` -- 6/1579. The claim that
 *    1010 stays a tempo session under the controlled regime is pinned there,
 *    not here, and no pin needed deleting.
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
