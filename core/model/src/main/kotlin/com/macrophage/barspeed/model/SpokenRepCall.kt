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
 * NOTHING READS THIS YET, and at this commit it returns what the speak path
 * does at its parent: every utterance goes to the engine unmarked.
 */
object SpokenRepCall {
    /** What the word of a rep call is read as: the lower-case word. */
    const val WORD = "rep"

    /** How a [Span] is read: as [WORD], or as the cardinal number it spells. */
    enum class Reading { WORD, CARDINAL }

    /** Characters [start] until [end], exclusive, of an utterance, read as [text]. */
    data class Span(val start: Int, val end: Int, val reading: Reading, val text: String)

    /** Every span of [utterance] the engine should be told how to read; empty where none. */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun spansOf(utterance: String): List<Span> = emptyList()
}
