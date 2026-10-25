package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which characters of an utterance [SpokenRepCall] marks for the engine (#339).
 *
 * Positions only: nothing here, and nothing in [SpokenRepCall], can change the
 * text, which is what keeps the recorded cue `Rep N`. Whether an engine reads
 * a marked span the way it is marked is not testable here; it is `[Field]`.
 */
class SpokenRepCallTest {
    /**
     * Every other word the voice says, spelled as the app spells it, is left
     * alone: the stroke words, the pause and timed-start words, the lead-in
     * phrase, the terminal words, the withheld-count word, the rest countdown
     * and the seconds and digit calls.
     */
    @Test
    fun `no other word of the voice's vocabulary is marked`() {
        val vocabulary = listOf(
            "Down", "Up", "Drive", "Return", "Hold", "Carry",
            "Ready", "Brace", "Done", "Time", "Set ended",
            LiveFallbackPolicy.WITHHELD_CUE, "Rest over",
            "10 seconds", "15 seconds", "3", "2", "1",
        )
        for (word in vocabulary) assertEquals(emptyList(), SpokenRepCall.spansOf(word), word)
    }

    /** `rep` is a whole word or it is not a rep call. */
    @Test
    fun `a word that only contains rep is not marked`() {
        for (text in listOf("Prep 3", "Reps 3", "Representative 1", "Repeat")) {
            assertEquals(emptyList(), SpokenRepCall.spansOf(text), text)
        }
    }
}
