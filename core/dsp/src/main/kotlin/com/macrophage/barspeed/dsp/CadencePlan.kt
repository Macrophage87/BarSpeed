package com.macrophage.barspeed.dsp

/**
 * One beat of a guided rep: a label held for a whole number of seconds.
 *
 * [spokenLabel] is what the voice says on entering the beat, or null when the
 * beat opens in silence. [isStroke] separates a movement stroke, which counts
 * itself out loud when long enough, from a pause, which does not.
 * [suppressFirstCount] drops a stroke's opening count to make room for a merged
 * rep announcement -- see [CadencePlan].
 */
data class CadenceBeat(
    val label: String,
    val seconds: Int,
    val spokenLabel: String?,
    val isStroke: Boolean,
    val suppressFirstCount: Boolean = false,
)

/**
 * The beats a guided rep is played as, derived from a [TempoSchedule].
 *
 * This arithmetic used to live inside GuidedCadenceRunner in `:app`, which has
 * no test source set, so nothing could assert that the cycle the metronome
 * plays is the cycle the plan prescribes. It was not, on every set the app has
 * ever paced -- issue 106.
 *
 * ## Whole seconds
 *
 * The runner can only sleep in one-second units, so every beat is a whole
 * number of seconds and a fractional prescription cannot be delivered exactly.
 * [deliveredCycleS] reports what will actually be played, so any shortfall is
 * visible rather than hidden. `Tempo.parse` accepts `"3-0-1.5-0"`; no captured
 * session has ever used such a tempo.
 *
 * ## Where the rep announcement goes, and why it is not free
 *
 * `VoiceCounter` speaks with `TextToSpeech.QUEUE_FLUSH`: every utterance
 * cancels the one before it. An announcement therefore needs silence after it
 * or it is cut off mid-word -- and the runner says something every second, the
 * stroke label and then a count on each following second. **The gap between
 * utterances is one second regardless of how long the stroke is**, so a
 * three-second eccentric buys no more room than a one-second drive. That is
 * measurable in the committed cue tracks: `Down` 594039, `1` 595041, `2`
 * 596043, `Up` 597043.
 *
 * Three places it can go:
 *
 * 1. **A closing pause the prescription already provides**, when it is at least
 *    a second. Free, and preferred.
 * 2. **Merged into the next stroke's opening call** -- "Down, rep three" -- with
 *    that stroke's FIRST count suppressed so the window is two seconds instead
 *    of one. Costs one tempo count and no time. Possible only when the stroke
 *    has a count to give up, which is exactly [MERGE_MIN_STROKE_S].
 * 3. **Not spoken.** The rep number stays on screen, driven by `onRepCounted`,
 *    and that on-screen number is the metronome's own count rather than the
 *    sensor's.
 *
 * The behaviour before issue 106 was a fourth option: insert a one-second beat
 * the prescription did not ask for.
 */
data class CadencePlan(
    val beats: List<CadenceBeat>,
    /** Index of the beat after which a rep is complete. */
    val repCompleteAfterBeat: Int,
    /** Beat index carrying the rep announcement, or null when it is not spoken. */
    val announceOnBeat: Int?,
    /** True when the announcement is merged into a stroke's opening call. */
    val announceMerged: Boolean,
) {
    /** Seconds the metronome will actually play per rep. */
    val deliveredCycleS: Int get() = beats.sumOf { it.seconds }

    companion object {
        /**
         * Shortest opening stroke that can carry a merged announcement.
         *
         * The quantity that matters is the WINDOW the merged utterance gets
         * before anything else speaks and QUEUE_FLUSH cuts it off. A merged
         * call needs two seconds; a plain label needs one. A stroke of two
         * seconds or more leaves two, either because its first count is
         * suppressed or because it is too short to count aloud at all and is
         * silent until it ends.
         *
         * This is deliberately NOT written as [GuidedCadence.COUNT_ALOUD_FROM_S].
         * An earlier version was, on the reasoning that a stroke can only give
         * up a count it has -- but that ties an announcement-window quantity to
         * a counting-out-loud quantity, and the two come apart in both
         * directions. Raise COUNT_ALOUD_FROM_S to 3 and a two-second stroke
         * falls silent after its label, so its window is already two seconds
         * and merging is safe -- yet an aliased threshold would rise and drop
         * the announcement. Lower it to 1 and the window stays one second while
         * an aliased threshold would fall and merge unsafely.
         */
        const val MERGE_MIN_STROKE_S = 2

        /** Seconds an announcement needs when it is given a beat of its own. */
        const val ANNOUNCE_BEAT_S = 1

        const val HOLD = "HOLD"
        const val BREATHE = "BREATHE"

        fun of(schedule: TempoSchedule): CadencePlan {
            val firstS = strokeSeconds(schedule.first.seconds)
            val secondS = strokeSeconds(schedule.second.seconds)
            val firstPause = schedule.pauseAfterFirstS.toInt()
            // The prescription decides this, and nothing else may add to it.
            val closing = schedule.pauseAfterSecondS.toInt()

            val beats = mutableListOf<CadenceBeat>()
            beats += stroke(schedule.first.label, firstS)
            if (firstPause > 0) beats += CadenceBeat(HOLD, firstPause, "Hold", isStroke = false)
            beats += stroke(schedule.second.label, secondS)
            val repCompleteAfter = beats.lastIndex
            if (closing > 0) beats += CadenceBeat(BREATHE, closing, null, isStroke = false)

            // The rep call rides a closing pause when the prescription provides
            // one long enough to say it in.
            if (closing >= ANNOUNCE_BEAT_S) {
                return CadencePlan(beats, repCompleteAfter, beats.lastIndex, announceMerged = false)
            }
            // Otherwise it opens the next rep's first stroke, which gives up its
            // first count to make room. Only a stroke that HAS a count can.
            if (firstS >= MERGE_MIN_STROKE_S) {
                beats[0] = beats[0].copy(suppressFirstCount = true)
                return CadencePlan(beats, repCompleteAfter, announceOnBeat = 0, announceMerged = true)
            }
            // Nowhere left to put it. The screen still carries the rep number.
            return CadencePlan(beats, repCompleteAfter, announceOnBeat = null, announceMerged = false)
        }

        private fun stroke(label: String, seconds: Int) = CadenceBeat(
            label = label,
            seconds = seconds,
            spokenLabel = label.lowercase().replaceFirstChar { it.uppercase() },
            isStroke = true,
        )

        private fun strokeSeconds(seconds: Double?): Int = (seconds ?: 1.0).toInt().coerceAtLeast(1)
    }
}

/** Constants the guided metronome and its plan share. */
object GuidedCadence {
    const val LEAD_IN_S = 5

    /** Strokes at least this long get counted out loud second by second. */
    const val COUNT_ALOUD_FROM_S = 2
}
