package com.macrophage.barspeed.dsp

/**
 * One thing the guided metronome says, and the cue rows it writes down for it.
 *
 * The two are not the same string and the split is load-bearing:
 * [utterance] is handed to TTS, [recorded] is handed to the cue track, and a
 * cue row is a persisted format every cue-track consumer matches exactly. A
 * merged rep call is spoken as one utterance -- `"Up, Rep 3"` -- because
 * `VoiceCounter` speaks with `QUEUE_FLUSH` and a second utterance a moment
 * later would cancel the first; the ROWS it writes stay separate words, so
 * nothing that counts `Up` rows sees a renamed one.
 *
 * [recorded] may be empty: the lead-in's countdown digits are spoken and
 * deliberately not written down (`LeadInPlan.RECORDED`).
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
     * A pause has no word of its own, so an announcement handed to one is
     * spoken alone; a stroke's announcement rides the stroke's word.
     *
     * A merged call writes TWO rows at one instant, the stroke word and the
     * call. It used to write only the stroke word, so on the tempo families
     * that merge -- every one of the sixteen sets on the session that found it,
     * 157 calls spoken and none written -- the archive was silent about a call
     * the lifter heard (issue 176). The stroke row is unchanged and unrenamed, because
     * the committed cue-track fixtures match those rows exactly and
     * `CueTrack.calledReps` in the test source set counts them; the call is a
     * row beside it, not a suffix on it.
     */
    fun beatCall(beat: CadenceBeat, announcement: String?): SpokenCall? {
        val label = beat.spokenLabel
        return when {
            label != null && announcement != null ->
                SpokenCall("$label, $announcement", listOf(label, announcement))
            label != null -> SpokenCall(label, listOf(label))
            announcement != null -> SpokenCall(announcement, listOf(announcement))
            else -> null
        }
    }

    /**
     * The tempo count spoken [second] seconds into [beat], or null for silence.
     *
     * Counts land on the seconds INSIDE the stroke: the last second of a stroke
     * is the next beat's word, not a count. A stroke shorter than
     * [GuidedCadence.COUNT_ALOUD_FROM_S] is not counted at all, and a stroke
     * carrying a merged announcement gives up its first count to make room for
     * it -- only when an announcement actually came, which rep 1 never has.
     */
    fun countCall(beat: CadenceBeat, announcement: String?, second: Int): SpokenCall? {
        if (!beat.isStroke || second >= beat.seconds) return null
        if (beat.seconds < GuidedCadence.COUNT_ALOUD_FROM_S) return null
        if (beat.suppressFirstCount && announcement != null && second == 1) return null
        return SpokenCall(second.toString(), listOf(second.toString()))
    }

    /**
     * Everything a set of [plannedReps] reps on [plan] says, in order, with the
     * second of the cadence each call lands on.
     *
     * Bounded sets only. A set with no planned rep count runs until the lifter
     * stops it, so it has no last rep and no script; the runner's loop is the
     * only account of one.
     */
    fun script(plan: CadencePlan, plannedReps: Int): List<ScriptedCall> {
        require(plannedReps >= 1) { "a set has at least one rep" }
        val calls = mutableListOf<ScriptedCall>()
        var second = 0
        var rep = 1
        var pending: String? = null
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
                if (rep >= plannedReps) {
                    calls += ScriptedCall(second, DONE, listOf(DONE))
                    return calls
                }
                pending = plan.announcementAfter(rep, plannedReps)
                rep++
            }
        }
    }
}
