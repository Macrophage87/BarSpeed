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
    private fun word(start: Int) = SpokenRepCall.Span(start, start + 3, SpokenRepCall.Reading.WORD, "rep")

    private fun cardinal(start: Int, number: String) =
        SpokenRepCall.Span(start, start + number.length, SpokenRepCall.Reading.CARDINAL, number)

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

    /**
     * The owner's report, exactly: `Rep 1` is the word rep and the number
     * one. The word is read lower-case, so the engine is not handed a
     * capitalised `Rep` to take for an abbreviation, and the number is handed
     * over as a cardinal.
     */
    @Test
    fun `a rep call marks the word and its number`() {
        assertEquals(listOf(word(0), cardinal(4, "1")), SpokenRepCall.spansOf("Rep 1"))
        assertEquals(listOf(word(0), cardinal(4, "12")), SpokenRepCall.spansOf("Rep 12"))
    }

    /** `Last rep` carries the same word with no number after it. */
    @Test
    fun `last rep marks the word alone`() {
        assertEquals(listOf(word(5)), SpokenRepCall.spansOf("Last rep"))
    }

    /**
     * Every word the sensor-counted voice says at a lockout, from the policy
     * that says it: each `Rep N` marks the word over its first three
     * characters and the number N, and `Last rep` and `Done` are what they
     * are above.
     */
    @Test
    fun `every lockout call the sensor's voice makes is marked as a rep call`() {
        for (planned in listOf(null, 1, 2, 5, 12)) {
            for (rep in 1..12) {
                val cue = VoiceMilestonePolicy.repMilestone(rep, rep - 1, planned) ?: continue
                val spans = SpokenRepCall.spansOf(cue)
                val expected = when (cue) {
                    "Done" -> emptyList<SpokenRepCall.Span>()
                    "Last rep" -> listOf(word(5))
                    else -> listOf(word(0), cardinal(4, rep.toString()))
                }
                assertEquals(expected, spans, cue)
            }
        }
    }

    /** `rep` is a whole word or it is not a rep call. */
    @Test
    fun `a word that only contains rep is not marked`() {
        for (text in listOf("Prep 3", "Reps 3", "Representative 1", "Repeat")) {
            assertEquals(emptyList(), SpokenRepCall.spansOf(text), text)
        }
    }
}
