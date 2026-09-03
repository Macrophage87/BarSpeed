package com.macrophage.barspeed.model

/**
 * A thing that can speak while a set is under way.
 *
 * Three of them exist and they are not interchangeable: each counts a
 * different quantity, off a different clock, and says so in a different
 * vocabulary. Which one runs is [SetVoicePolicy.guidesFor]'s decision.
 */
enum class SetVoiceGuide {
    /**
     * The guided metronome: stroke calls, tempo counts and rep announcements,
     * on the cadence `CadencePlan` lays out. Runs on a tempo'd set of a
     * rep-based lift and nothing else.
     */
    CUED_CADENCE,

    /**
     * The set's own clock: the milestone, the final countdown and the terminal
     * word, from `TimedSetVoice`. Runs on a set measured in seconds.
     *
     * That it RUNS is what this says. Whether it speaks on a given second is
     * `TimedSetVoice.cueFor`'s answer, and whether it speaks at all is the
     * lifter's audio-cues setting and whether a target exists -- neither of
     * which is decided here.
     */
    TIMED_CLOCK,

    /**
     * The sensor-driven counter: bare digits for each second of a detected
     * eccentric or concentric, and a rep call at each detected lockout. Runs
     * on a set nothing else is counting -- an explosive lift with a sensor, or
     * demo mode.
     */
    SENSOR_COUNT,
}

/**
 * Which guide speaks during the WORK of a set of this shape.
 *
 * ## Why this is a type and not a boolean in `:app`
 *
 * It was a boolean in `:app`: `manualSet`, computed in `RecordViewModel.beginSet`
 * and read back out of the state at every arriving sample to decide whether the
 * sensor counter may speak. That flag has three other jobs -- which UI branch
 * draws, which counter completion is judged against, and whether a manual rep
 * tap is accepted -- so the one question this file asks was answered by a
 * variable answering three others, in a module no test on the CI path reaches.
 *
 * It was wrong for timed sets, on every timed set the app has ever recorded.
 * `manualSet` is false on a hold, so the sensor counter ran beside the hold
 * clock and both spoke: field-37's sets 11 and 12 carry the hold cadence on the
 * work-start grid and a stray bare-digit stream 0.8 s off it, the first digit
 * 0.186 s before `Hold`. That is issue #217 and
 * `TimedHoldCueTrackTest` in `:core:dsp` measures it.
 *
 * ## The contract
 *
 * **At most one guide -- and that does not hold yet.** A set has one voice;
 * two voices counting different quantities in overlapping vocabularies is the
 * defect above, and a lifter cannot be expected to tell whose `1` they just
 * heard. This commit lifts the rule out of `:app` UNCHANGED, so a timed set
 * still returns two guides here exactly as it behaved before. The pin that
 * asserts one, and the change that makes it true, are #217's own commits. The
 * empty set is legal and means the set is counted by the lifter, silently.
 *
 * Returning a SET rather than a single value or a null is deliberate. Before
 * #217 two guides genuinely did run at once, and a type that could not say so
 * would have made the defect unrepresentable in the very function that owns
 * it -- the pin asserting there is at most one would have been a tautology
 * rather than a check.
 *
 * This decides only WHO may speak. What they say, whether the audio-cues
 * toggle lets them say it, and which counter the set's reps are read from are
 * three other questions with three other owners.
 */
object SetVoicePolicy {
    /**
     * The guides that speak during the work of this set.
     *
     * [hasTempo], [isTimed] and [kind] are the set's prescription;
     * [demoMode] and [imuConnected] are the device. The pairing of the first
     * three is not re-derived here -- [LeadInPolicy.prepCase] owns it, and a
     * cued set is exactly the set whose prep runs into a cadence.
     */
    fun guidesFor(
        hasTempo: Boolean,
        isTimed: Boolean,
        kind: ExerciseKind,
        demoMode: Boolean,
        imuConnected: Boolean,
    ): Set<SetVoiceGuide> {
        val cued = LeadInPolicy.prepCase(hasTempo, isTimed, kind) == PrepCase.CUED
        // Kept as `:app` computed it, so lifting the decision here changes
        // nothing on its own. #217 changes it, in its own commit.
        val sensor = !cued && (isTimed || demoMode || (kind == ExerciseKind.EXPLOSIVE && imuConnected))
        return buildSet {
            if (cued) add(SetVoiceGuide.CUED_CADENCE)
            if (isTimed) add(SetVoiceGuide.TIMED_CLOCK)
            if (sensor) add(SetVoiceGuide.SENSOR_COUNT)
        }
    }

    /**
     * Whether the sensor-driven counter may speak on a set of this shape.
     *
     * The one question `RecordViewModel` asks per arriving sample, so it is
     * answered here rather than by a call site re-reading a set for a member.
     */
    fun sensorCounts(
        hasTempo: Boolean,
        isTimed: Boolean,
        kind: ExerciseKind,
        demoMode: Boolean,
        imuConnected: Boolean,
    ): Boolean = SetVoiceGuide.SENSOR_COUNT in guidesFor(hasTempo, isTimed, kind, demoMode, imuConnected)
}
