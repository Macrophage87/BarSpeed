package com.macrophage.barspeed.model

/**
 * Which characters of an utterance are a rep call, and what each should be
 * read as by the speech engine (#339).
 *
 * The owner, 2026-10-25: *"For some reason the app keeps saying
 * "representative 1" instead of "rep one"."* The text handed to the engine is
 * `Rep N` -- [VoiceMilestonePolicy.repMilestone] on a sensor-counted set, and
 * `CadencePlan.announcementFor` in `:core:dsp` on a guided one -- and which
 * reading an engine picks for a capitalised `Rep` before a number is the
 * engine's choice. Nothing in this repo has measured one; the report above is
 * the only observation.
 *
 * `VoiceCounter` in `:app` turns each [Span] into a platform `TtsSpan` over
 * the same characters. Whether an engine honours it is a `[Field]` question.
 *
 * **The text is marked, never changed.** `RecordViewModel.speakCues` writes
 * the cue it was handed to the cue buffer and the journal BEFORE it speaks,
 * and this returns positions rather than a new string, so `Rep 3` stays
 * `Rep 3` in `voiceCues` and in the cue CSV.
 *
 * WHAT IS MARKED. The word `rep`, in any case, wherever it stands as a whole
 * word -- `Rep 3` and `Last rep` both -- read as [WORD]; and the number that
 * follows it, where one does, read as a cardinal. A word that merely contains
 * the letters (`Prep`, `Reps`, `Representative`) is not a rep call. Nothing
 * else the voice says is marked: its other words (`Down`, `Up`, `Drive`,
 * `Return`, `Hold`, `Carry`, `Ready`, `Brace`, `Done`, `Time`, `Set ended`,
 * `No count`, `Rest over`, `N seconds`, bare digits) were read for an
 * abbreviation or a number-word an engine might expand, and none was found.
 * That reading is of the spelling, not of any engine's output.
 */
object SpokenRepCall {
    /** What the word of a rep call is read as: the lower-case word. */
    const val WORD = "rep"

    /** How a [Span] is read: as [WORD], or as the cardinal number it spells. */
    enum class Reading { WORD, CARDINAL }

    /** Characters [start] until [end], exclusive, of an utterance, read as [text]. */
    data class Span(val start: Int, val end: Int, val reading: Reading, val text: String)

    /**
     * The word `rep` standing alone, then optionally whitespace and a run of
     * digits that also stands alone. Letters and digits on either side of the
     * word make it part of a longer word, which is not a rep call.
     */
    private val repCall = Regex(
        """(?<![\p{L}\p{N}])rep(?![\p{L}\p{N}])(?:\s+(\d+)(?![\p{L}\p{N}]))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Every span of [utterance] the engine should be told how to read; empty where none. */
    fun spansOf(utterance: String): List<Span> = repCall.findAll(utterance).flatMap { match ->
        val wordAt = match.range.first
        val word = Span(wordAt, wordAt + WORD.length, Reading.WORD, WORD)
        val number = match.groups[1]?.let { Span(it.range.first, it.range.last + 1, Reading.CARDINAL, it.value) }
        listOfNotNull(word, number).asSequence()
    }.toList()
}
