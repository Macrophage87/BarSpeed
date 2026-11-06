package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which characters of an utterance [SpokenRepCall] marks for the engine (#339).
 *
 * Positions, and the text the engine is handed. Neither can change the
 * recorded cue, which keeps `Rep N`. Whether an engine reads what it is
 * handed the way it is marked is not testable here; it is `[Field]`.
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

    /**
     * The same for the words a sensor-counted set says at and before its
     * target (UX review 2026-11-06, voice F5): `Rep 4, last rep` marks the
     * leading `Rep 4` and the trailing `rep` alone, and `Rep 5, target` is
     * read like any other rep call. Expected positions are written from the
     * rep and the target, not read back off the cue.
     */
    @Test
    fun `the sensor's target and last-rep calls are marked as rep calls`() {
        val target = VoiceMilestonePolicy.Milestones.MARKS_TARGET
        for (planned in listOf(null, 1, 2, 5, 12)) {
            for (rep in 1..12) {
                val cue = checkNotNull(VoiceMilestonePolicy.repMilestone(rep, rep - 1, planned, target))
                val number = rep.toString()
                val lead = listOf(word(0), cardinal(4, number))
                val expected = if (planned != null && rep == planned - 1) {
                    lead + word(4 + number.length + ", last ".length)
                } else {
                    lead
                }
                assertEquals(expected, SpokenRepCall.spansOf(cue), cue)
                if (planned != null && rep == planned - 1) assertEquals("Rep $rep, last rep", cue)
                if (planned != null && rep == planned) assertEquals("Rep $rep, target", cue)
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

    /**
     * What the engine is handed keeps every character where the cue had it,
     * so each span [SpokenRepCall.spansOf] finds on the cue covers the same
     * word or number of the handed text.
     */
    @Test
    fun `the handed text keeps every span where the cue had it`() {
        val cues = listOf("Rep 1", "Rep 12", "Rep 4, last rep", "Rep 5, target", "Last rep", "Done", "Down")
        for (cue in cues) {
            val handed = SpokenRepCall.utterance(cue)
            assertEquals(cue.length, handed.length, cue)
            assertEquals(SpokenRepCall.spansOf(cue), SpokenRepCall.spansOf(handed), cue)
        }
    }

    /**
     * The owner, 2026-11-06, on v0.2.2's first live session: *"it kept saying
     * "representative" and not "rep"."* The TtsSpan v0.2.2 laid over the
     * word was not honoured by his engine, so the text itself carries the
     * reading: every rep call reaches the engine with the word lower-case,
     * which is how the rest of the vocabulary already says it ("Last rep").
     * The cue keeps its spelling; only the handed text changes.
     */
    @Test
    fun `the engine is handed the word rep in lower case`() {
        assertEquals("rep 1", SpokenRepCall.utterance("Rep 1"))
        assertEquals("rep 12", SpokenRepCall.utterance("Rep 12"))
        assertEquals("rep 4, last rep", SpokenRepCall.utterance("Rep 4, last rep"))
        assertEquals("rep 5, target", SpokenRepCall.utterance("Rep 5, target"))
        assertEquals("Last rep", SpokenRepCall.utterance("Last rep"))
        assertEquals("REPS 3", SpokenRepCall.utterance("REPS 3"))
        assertEquals("Representative 1", SpokenRepCall.utterance("Representative 1"))
    }
}
