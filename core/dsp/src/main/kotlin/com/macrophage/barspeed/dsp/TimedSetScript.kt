package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.SetVoiceGuide

/** One thing a timed set says, at the wall-clock instant it is said. */
data class TimedSetCue(val atMs: Long, val cue: String)

/**
 * Everything a HOLD or a CARRY says, in order, from its prep to its terminal
 * word.
 *
 * ## Why this exists as well as the three functions it calls
 *
 * `LeadInPlan` answers "what does second k of the prep say", `TimedSetVoice`
 * answers "what does a second with k remaining say", and
 * `SetVoicePolicy.guidesFor` answers "who may speak at all". Each is pinned.
 * What none of them can be asked is the question issue #217 is about: **does
 * the set, taken whole, speak with one voice.** That question spans all three
 * and was previously only answerable by holding a phone in a gym, because the
 * loop that interleaves them is `RecordViewModel`'s tick job in `:app`, which
 * no test on the CI path reaches. This is that interleaving, with no clock and
 * no coroutine.
 *
 * It is a MODEL of two loops -- the prep runner and the tick job -- and not
 * either of them, so the two can drift apart. What holds it to the truth is the
 * field: `TimedSetScriptTest` replays field-37's sets 11 and 12 against their
 * committed cue tracks, label for label.
 *
 * ## What it is not
 *
 * Not a schedule anything plays. The runner still sleeps on the wall clock and
 * still speaks; nothing here is wired into the app. Its output is what the
 * cue TRACK should contain, which is why the lead-in's countdown digits are
 * absent from it -- `LeadInPlan.RECORDED` is the rule and this obeys it rather
 * than restating it.
 *
 * The millisecond figures it returns are an exact second grid. The device's is
 * not: `delay(1_000)` drifts, by up to 20 ms over the 20 s hold and 14 ms over
 * the 30 s one in the committed captures. A caller comparing against a real
 * capture compares ORDER and LABELS, or allows that drift.
 */
object TimedSetScript {
    /**
     * The cues a timed set of [targetS] seconds writes, given its prep, the
     * word its prep ends on, and who is allowed to speak.
     *
     * [workStartedAtMs] is the instant the prep ends and the clock starts --
     * the set's own zero, and `SetClockPolicy`'s subject. The prep is laid out
     * backwards from it and the clock forwards, which is what puts them on one
     * grid.
     *
     * [sensorCountsAtMs] is the sensor-driven counter's stream, in wall-clock
     * instants, as captured. It is a PARAMETER rather than something derived
     * here because nothing pure can predict it: it counts seconds of a phase a
     * live IMU stream decided the lifter was in. When
     * [SetVoiceGuide.SENSOR_COUNT] is not among [guides] it is dropped
     * entirely, which is the whole of what #217 changes about what a hold
     * says.
     *
     * Its LABELS are numbered 1..n here. That counter speaks the elapsed
     * second of the phase it is in, so an uninterrupted phase reads 1, 2, 3 --
     * which is what both committed captures show, three calls and two. A phase
     * BREAK restarts it, and this cannot express that. No capture of a timed
     * set has one, and rather than invent a rule for a shape nothing has
     * produced, the numbering is stated here as the assumption it is.
     */
    fun script(
        prepS: Int,
        targetS: Int,
        startWord: String,
        workStartedAtMs: Long,
        guides: Set<SetVoiceGuide>,
        sensorCountsAtMs: List<Long> = emptyList(),
    ): List<TimedSetCue> {
        require(targetS >= 0) { "a timed set is not held for a negative number of seconds" }
        val cues = mutableListOf<TimedSetCue>()
        val prep = LeadInPlan.of(prepS)
        for ((index, beat) in prep.beats.withIndex()) {
            val cue = beat.cue ?: continue
            cues += TimedSetCue(workStartedAtMs - 1000L * prep.secondsBeforeStart(index), cue)
        }
        cues += TimedSetCue(workStartedAtMs, startWord)
        if (SetVoiceGuide.TIMED_CLOCK in guides) {
            for (second in 1..targetS) {
                val said = TimedSetVoice.cueFor(targetS - second) ?: continue
                cues += TimedSetCue(workStartedAtMs + 1000L * second, said)
            }
        }
        if (SetVoiceGuide.SENSOR_COUNT in guides) {
            cues += sensorCountsAtMs.mapIndexed { index, at -> TimedSetCue(at, (index + 1).toString()) }
        }
        return cues.sortedBy { it.atMs }
    }
}
