package com.macrophage.barspeed.dsp

/**
 * One thing the guided metronome says, and the cue rows it writes down for it.
 *
 * The two are not the same string and the split is load-bearing:
 * [utterance] is handed to TTS, [recorded] is handed to the cue track, and a
 * cue row is a persisted format every cue-track consumer matches exactly.
 *
 * A rep call used to be spoken as one utterance carrying two words --
 * `"Up, Rep 3"` -- because `VoiceCounter` speaks with `QUEUE_FLUSH` and a second
 * utterance a moment later would cancel the first, and it wrote the two as
 * separate ROWS so that nothing counting `Up` rows saw a renamed one. From #293
 * the call REPLACES the stroke word, so there is one word in the utterance and
 * one row beside it: the word the lifter did not hear is not written down. The
 * list stays a list because a caller may still need to stamp several rows at one
 * instant, and because [recorded] may be empty -- the lead-in's countdown digits
 * are spoken and deliberately not written (`LeadInPlan.RECORDED`).
 *
 */
data class SpokenCall(
    val utterance: String,
    val recorded: List<String>,
)

/** A [SpokenCall] placed at the second of the cadence it lands on. */
data class ScriptedCall(
    /** Seconds from the first stroke of the set; the lead-in is not counted. */
    val atSecond: Int,
    val utterance: String,
    val recorded: List<String>,
)

/**
 * What a guided set SAYS, second by second, and what of it reaches the record.
 *
 * `GuidedCadenceRunner` in `:app` used to decide both, inline, in the same
 * function that sleeps -- and no test on the CI path reaches that class, so
 * nothing could assert either. The arithmetic of the BEATS was lifted into
 * [CadencePlan] for that reason (issue 106); this is the same move for the
 * WORDS (issue 176). The runner keeps the sleeping and the callbacks and
 * decides nothing.
 *
 * ## Why the script exists as well as the two per-beat functions
 *
 * The runner walks beats one at a time and can only ever ask "what do I say
 * now". The question issue 176 is about -- does the set's cue track account for
 * everything the set said -- is a question about the WHOLE set, and could not
 * be asked of the app at all. [script] answers it for a bounded set, from the
 * plan alone, with no clock and no coroutine.
 *
 * It is a MODEL of the runner's loop, not the loop itself, so the two can drift
 * apart. What holds it to the truth is the field: `MergedCallCueTrackTest`
 * checks it against three cue tracks recorded by the shipped app, row for row
 * and second for second.
 */
object CadenceVoice {
    /**
     * The cue the guide speaks when the prescription has been called through.
     *
     * One of TWO words that mean a set is over, not the only one: a guided set
     * that never reaches this call speaks `SetEnd.STOPPED` instead, at the tap
     * rather than from this script (#141). `SetEnd.TERMINAL_CUES` is the whole
     * vocabulary and the one thing to read for it. Nothing on the guide's own
     * schedule ever says the other word, which is why only this one is here.
     */
    const val DONE = "Done"

    /**
     * The call a beat opens with, or null when the beat opens in silence.
     *
     * **An announcement REPLACES the beat's own word** (#293): it is the whole
     * utterance and the whole row, and the word is neither spoken nor written.
     * The owner asked for that in those terms -- *"Have the rep number be at the
     * start of the rep, and replace the relevant up or down, etc."* -- and it is
     * what makes the number arrive at the start of the rep instead of a beat
     * later or a rep early.
     *
     * Two consequences, both deliberate. One second carries one utterance, so
     * nothing is at risk of being flushed mid-word by the second after it. And a
     * word the lifter did not hear is not written down: the row the archive
     * loses is a row the app did not say, which is the same rule issue 176 fixed
     * in the other direction -- it used to write only the stroke word, so on the
     * families that merged (every one of the sixteen sets on the session that
     * found it, 157 calls spoken and none written) the archive was silent about
     * a call the lifter heard.
     *
     * What it costs a reader of an archive is published as export 1.20's THIRD
     * entry: the first stroke's word appears once per SET rather than once per
     * rep, and the call rows mark the rep boundaries it used to mark.
     * `CueTrack.calledReps` in the test source set counts `Down` rows and says
     * so in its own KDoc.
     */
    fun beatCall(beat: CadenceBeat, announcement: String?): SpokenCall? {
        val label = beat.spokenLabel
        return when {
            announcement != null -> SpokenCall(announcement, listOf(announcement))
            label != null -> SpokenCall(label, listOf(label))
            else -> null
        }
    }

    /**
     * The tempo count spoken [second] seconds into [beat], or null for silence.
     *
     * Counts land on the seconds INSIDE the stroke: the last second of a stroke
     * is the next beat's word, not a count. A stroke shorter than
     * [GuidedCadence.COUNT_ALOUD_FROM_S] is not counted at all.
     *
     * **A stroke whose word an announcement replaced is counted FROM the
     * number** (#293). The number occupies the second the word had, so it is
     * that stroke's first count and the rest continue from it: a three-second
     * eccentric goes `"Rep 3"`, `2`, `3`. The owner's example is exactly that --
     * *"a 3010 press goes Rep 3, 2, 3, Up"* -- and it is why the shift is tied
     * to the WORD being replaced rather than to an announcement arriving: a
     * wordless beat handed a call replaces nothing, so its counts, if it had
     * any, would not move.
     *
     * No count is dropped. A merged call used to silence this stroke's first
     * count to widen its own window, and rep 1 -- which never carries a call --
     * kept it, which is how the merged calls in the 0.1.43 archives were dated
     * at all (issue 176). That asymmetry is gone; what remains is that rep 1
     * counts from ONE because its word is not replaced, so a track reads
     * `Down 1 2` on rep 1 and `Rep 2 2 3` on rep 2.
     */
    fun countCall(beat: CadenceBeat, announcement: String?, second: Int): SpokenCall? {
        if (!beat.isStroke || second >= beat.seconds) return null
        if (beat.seconds < GuidedCadence.COUNT_ALOUD_FROM_S) return null
        val replacedAWord = announcement != null && beat.spokenLabel != null
        val count = if (replacedAWord) second + 1 else second
        return SpokenCall(count.toString(), listOf(count.toString()))
    }

    /**
     * Everything a set of [plannedReps] reps on [plan] says, in order, with the
     * second of the cadence each call lands on.
     *
     * Bounded sets only. A set with no planned rep count runs until the lifter
     * stops it, so it has no last rep and no script; the runner's loop is the
     * only account of one.
     *
     * ## [DONE] follows the whole of the last rep, closing pause included
     *
     * A rep is complete after [CadencePlan.repCompleteAfterBeat], and on the
     * tempo families whose prescription ends in a pause that beat is not the
     * last beat of the cycle: [CadencePlan.of] appends the closing pause AFTER
     * the second stroke, so `repCompleteAfterBeat` is one short of
     * `beats.lastIndex` there and exactly one beat is left.
     *
     * The set therefore runs to `plannedReps` x [CadencePlan.deliveredCycleS]
     * on every plan: the beats after the completion beat are played on the
     * last rep exactly as they are on reps 1 to N-1, and `Done` comes after
     * them.
     *
     * Until #265 this returned at the completion beat, so the last rep of a
     * closing-pause tempo lost its pause -- the hold the prescription is
     * training. Measured on field-39, whose two closing-pause sets read 17.023 s
     * against 6 x 3 and 23.029 s against 6 x 4; the owner's word on hearing it
     * was *"that very much feels short"*. The pause is silent and there is no
     * next rep to announce, so the restored beat speaks nothing and writes no
     * cue row: one row moves a second later and none is added.
     *
     * The instant the rest countdown runs from is the terminal cue's own
     * (`RestClockPolicy.startedAtMs`, #172), so it moves with `Done` by
     * construction and nothing else has to be told.
     */
    fun script(plan: CadencePlan, plannedReps: Int): List<ScriptedCall> {
        require(plannedReps >= 1) { "a set has at least one rep" }
        val calls = mutableListOf<ScriptedCall>()
        var second = 0
        var rep = 1
        var pending: String? = null
        var lastRep = false
        while (true) {
            for ((index, beat) in plan.beats.withIndex()) {
                val announcement = pending?.takeIf { index == plan.announceOnBeat }
                if (announcement != null) pending = null
                beatCall(beat, announcement)?.let { calls += ScriptedCall(second, it.utterance, it.recorded) }
                for (n in 1..beat.seconds) {
                    val count = countCall(beat, announcement, n) ?: continue
                    calls += ScriptedCall(second + n, count.utterance, count.recorded)
                }
                second += beat.seconds
                if (index != plan.repCompleteAfterBeat) continue
                // The last rep is complete here and the cycle may not be. Play
                // what the prescription still has left, announcing nothing --
                // there is no rep after this one -- and say DONE at the end of
                // it.
                if (rep >= plannedReps) {
                    lastRep = true
                    continue
                }
                rep++
                pending = plan.announcementFor(rep, plannedReps)
            }
            if (lastRep) {
                calls += ScriptedCall(second, DONE, listOf(DONE))
                return calls
            }
        }
    }
}
