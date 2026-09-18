package com.macrophage.barspeed.model

/**
 * What the LIVE readout does when [LiveFeedPolicy] moves the feed off the
 * armed unit mid-set. Issue #280.
 *
 * Three answers, and the third is the one that did not exist before: the live
 * count can be given up for the rest of the set. A rep counter has no way to
 * say "I am counting off a mounting I cannot vouch for", so until now it said
 * a number instead.
 */
sealed interface LiveFallback {
    /**
     * Nothing changes: the tracker goes on being fed, the count goes on, the
     * voice goes on.
     *
     * Every set with one unit, every dual set whose armed unit kept the
     * readout, and every set whose declaration describes the LIFT rather than
     * a mount -- which is the same gate `SetAnalyzer` applies after the set
     * through `LiftDirection.mountSpecific` (#247).
     */
    data object Continue : LiveFallback

    /**
     * Rebuild the tracker for a unit measured to be [sensorOnStack].
     *
     * Only reachable where the stack term was the declaration's ONLY mount
     * term, so the geometry this produces is either the declaration unchanged
     * -- the signature CONFIRMED the partner is on the stack too -- or the
     * declaration with the stack term dropped, which
     * `LiftDirection.forMeasuredMount` produces and `LiftDirectionTest` pins as
     * mount-free.
     */
    data class Rebuild(val sensorOnStack: Boolean) : LiveFallback

    /**
     * Stop counting for the rest of the set: no number on the ring, no rep
     * called, one word said once.
     *
     * The alternative is a count made off a mounting nothing measured, which
     * is issue #280's defect and the live twin of the analysis #247 refuses.
     * Under the owner's rule that the voice is the live channel a count the
     * lifter cannot trust is worse than no count, because the lifter's `+1 REP`
     * correction is captured once and is unrecoverable.
     */
    data object Withhold : LiveFallback
}

/**
 * Whether the live readout may go on counting across a change of unit, and
 * under which geometry. Issue #280.
 *
 * ## The defect
 *
 * `RecordViewModel` builds the live tracker once, at `beginSet`, from
 * `ExerciseDef.liftDirection()` -- the geometry declared for the ARMED unit.
 * [LiveFeedPolicy] can then move the feed to the PARTNER unit's stream when
 * the armed one falls behind, and nothing re-derived the geometry at that
 * switch and nothing refused. So on a stack-declared cable set the lifter read
 * reps counted off the partner's stream under the armed unit's mounting, which
 * is exactly the inversion `SetAnalyzer` refuses after the fact (#247). The
 * two halves disagreed: the in-set screen counted, the rest screen and the
 * export refused.
 *
 * On field-42's three seated cable rows the analysed unit was the one clipped
 * to the rotating HANDLE under a declared stack mount, and the batch analysis
 * read 3, 4 and 2 reps of the 8 the lifter performed. That is the cost of
 * reading one unit's stream under the other's mounting, measured; the live
 * count has the same exposure and nothing after the set can repair what the
 * lifter already heard.
 *
 * ## The rule, in order
 *
 * 1. NO SWITCH, NOTHING TO DECIDE: [LiveFallback.Continue]. This is every set
 *    with one unit and every dual set whose armed unit kept the readout, which
 *    is the whole committed corpus.
 * 2. A DECLARATION THAT NAMES NO MOUNT transfers: [LiveFallback.Continue]. The
 *    same gate `LiftDirection.mountSpecific` is, asked live. A barbell
 *    declaration describes the lift, so the partner's stream is described by it
 *    as well as the armed unit's.
 * 3. A MOUNT TERM NOTHING CAN MEASURE refuses: [LiveFallback.Withhold]. Where
 *    `LiftDirection.mountSpecificBesidesStack` is true -- a declared inversion,
 *    or a pulley that does not travel 1:1 -- no code anywhere reads a stream
 *    and answers for it, so there is no repair to make. This fires whether or
 *    not a stack is also declared, and it is the population #247 refuses at set
 *    end for the same reason.
 * 4. A STACK-ONLY DECLARATION ASKS THE SIGNATURE. `StackRollSignature`, over
 *    the partner's working window SO FAR:
 *    - [StackMountSignal.ON_STACK]: the declaration describes this unit too.
 *      [LiveFallback.Rebuild] with the stack term intact.
 *    - [StackMountSignal.NOT_ON_STACK]: this unit did not ride the load, and
 *      the stack term was the only mount term, so what is left is the lift's
 *      own geometry. [LiveFallback.Rebuild] with the stack term dropped.
 *    - [StackMountSignal.UNMEASURED]: the window held too few samples to take a
 *      range over. Absence, never a flat reading, and never a default to the
 *      declaration. [LiveFallback.Withhold].
 *
 * ## What the answer is taken ONCE, and why
 *
 * The caller asks this on the single frame [LiveFeed.switched] is true and
 * never again, so a withhold lasts the rest of the set even if the signature
 * would decide a second later. That is [LiveFeedPolicy]'s latch argument
 * applied to the count rather than to the stream: a ring that blanks and then
 * starts again from 1 mid-set, and a voice that resumes calling reps at a
 * number the lifter has not heard, are worse to lift to than a count that
 * stops once. The owner cannot watch the ring mid-set; the voice is the live
 * channel.
 *
 * IN PRACTICE THE UNMEASURED ROW IS THE COMMON ONE, and that is stated rather
 * than discovered later. [LiveFeedPolicy]'s switch condition requires the armed
 * unit to be under [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] frames while the
 * partner is that many ahead, so it fires within roughly the first tenth of a
 * second of the set at the WT901's configured 100 Hz output -- before the
 * partner's own working window holds
 * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] samples in most shapes. The usual
 * answer at a real switch is therefore [LiveFallback.Withhold], and
 * [LiveFallback.Rebuild] is the narrower case where the partner had already
 * been streaming through a started working window. WHICH OF THE TWO A REAL
 * DUAL SESSION PRODUCES HAS NOT BEEN MEASURED: no capture in this repository
 * holds a dual set with both units streaming, and the fallback has never been
 * observed firing on a device. It is a field question and is raised as one.
 *
 * ## What it decides nothing about
 *
 * NOT WHAT IS RECORDED. Both buffers fill, both journals append and both raw
 * streams are archived exactly as before, whichever answer this gives; the
 * set's published figures remain [AnalysedRolePolicy]'s and `SetAnalyzer`'s,
 * taken over the whole set, and the rest screen shows that result as it does
 * today. This is a decision about what the lifter is SHOWN and TOLD while the
 * set runs.
 *
 * NOT THE STORED LIVE COUNT. `RecordedRepCount.live` goes on being what the
 * detector had called, and a withheld set stores whatever that was at the
 * switch -- which the switch's own condition bounds at fewer than
 * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] frames of the armed unit. So the
 * archive does not distinguish a withheld live count from one a detector
 * genuinely called low, and `RecordedRepCount.live`'s own KDoc pins zero as a
 * count rather than an absence. Closing that needs a third state on that field
 * or a new export key; it is named here as a remainder and is not folded into
 * this change.
 */
object LiveFallbackPolicy {
    /**
     * The word said once when a live count is given up, and the row written to
     * the set's cue track at the same instant.
     *
     * TWO WORDS, chosen against the owner's rule that the voice is the live
     * channel: the lifter cannot watch the ring, so a count that simply stops
     * would be indistinguishable from a set the sensor missed every rep of.
     * It is deliberately not a number and not a stroke word -- it cannot be
     * heard as a rep call or as a tempo cue -- and it is not in
     * `SetEnd.TERMINAL_CUES`, so writing it cannot bound a rep list.
     * `SetEndWindowTest` pins that.
     *
     * WHAT IT DOES NOT SAY is why, which a spoken word in a gym has no room
     * for. The reason is on the record: the cue row stamps the instant, and the
     * export's `sensors` block names the analysed role and its basis.
     */
    const val WITHHELD_CUE = "No count"

    /**
     * The answer for the frame [LiveFeed.switched] is true on.
     *
     * @param switched [LiveFeed.switched] -- true on the single call that moved
     *   the feed off the armed stream, and the only call on which anything here
     *   can happen.
     * @param declaresStackMount the set's own `sensorOnStack`, as resolved for
     *   the set rather than guessed from the exercise's name.
     * @param declaresOtherMount `LiftDirection.mountSpecificBesidesStack` -- a
     *   declared inversion or a travel ratio, neither of which anything
     *   measures.
     * @param signal what the stream now feeding the tracker says about riding
     *   the load, from `StackRollSignature` over its working window so far.
     *
     * THIS BODY IS TODAY'S RULE AND NOT THE RULE THE KDOC ABOVE DESCRIBES.
     * Every input returns [LiveFallback.Continue], which is exactly what the
     * live readout does now: it goes on counting across the switch whatever the
     * declaration says. The seam is landed first, with its two unchanged rows
     * pinned, so that the rows that CHANGE can be pushed as failing tests at
     * their own SHA before any of them passes. Rules 3 and 4 arrive there.
     */
    fun atSwitch(
        switched: Boolean,
        declaresStackMount: Boolean,
        declaresOtherMount: Boolean,
        signal: StackMountSignal,
    ): LiveFallback {
        if (!switched) return LiveFallback.Continue
        if (!declaresStackMount && !declaresOtherMount) return LiveFallback.Continue
        return when (signal) {
            StackMountSignal.ON_STACK,
            StackMountSignal.NOT_ON_STACK,
            StackMountSignal.UNMEASURED,
            -> LiveFallback.Continue
        }
    }
}

/**
 * What the in-set ring draws for the sensor's count, and the two figures
 * derived from it. Issue #280.
 *
 * ## Why these three lines are here
 *
 * They were three expressions inside `RecordScreen` -- the headline count, the
 * progress arc on the explosive ring and the reps/min line beside it -- in a
 * module no test on the CI path reaches. Issue #280 needs each of them to draw
 * NOTHING on a set whose live count has been given up, and "absence rendered as
 * a value" is the class that gets shipped when a withheld count is drawn as the
 * number zero: a lifter glancing at the ring reads a detector that has missed
 * every rep, which is a claim, rather than a count the app has stopped making.
 *
 * ## One flag, read by all three
 *
 * A single `withheld` argument, so the arc, the number and the cadence line
 * cannot disagree about whether there is a count. `RepCountPolicy.displayedCount`
 * stays the one place the NUMBER comes from -- these functions format it and
 * never re-derive it -- which is #252's rule: the ring and the voice read one
 * figure.
 */
object LiveCountReadout {
    /**
     * What is drawn where the count would be.
     *
     * An em dash, matching what the explosive ring already draws for a peak
     * velocity it has none of. Not "0", and not a blank: a blank reads as a
     * screen that has not loaded.
     */
    const val NO_COUNT = "—"

    /** The count as the ring draws it, or [NO_COUNT] where it has been given up. */
    fun countLabel(reps: Int, withheld: Boolean = false): String = if (withheld) NO_COUNT else "$reps"

    /**
     * The explosive ring's fill, 0 where nothing was prescribed and 0 where the
     * count has been given up.
     *
     * Zero in both cases and that is not this type's *absence rendered as a
     * value*: an arc has no third state to draw, and the number beside it is
     * [NO_COUNT], which is what says which of the two is happening.
     */
    fun repProgress(reps: Int, plannedReps: Int?, withheld: Boolean = false): Float = when {
        withheld -> 0f
        else -> plannedReps?.takeIf { it > 0 }?.let { reps / it.toFloat() } ?: 0f
    }

    /**
     * Reps per minute for cyclical ballistic work, or null where there is no
     * figure -- fewer than two reps, no elapsed clock, or a count given up.
     *
     * Null rather than 0 on every one of the three, which is what the caller
     * already did for the first two.
     */
    fun repsPerMin(reps: Int, elapsedS: Int, withheld: Boolean = false): Int? = when {
        withheld -> null
        reps >= 2 && elapsedS > 0 -> reps * 60 / elapsedS
        else -> null
    }

    /**
     * The caption naming the rep the lifter is about to start, or null where no
     * count stands behind it.
     *
     * Suppressed rather than frozen on a withheld set: "rep 1 ready" through a
     * set of eight is a statement about the lifter, not about the app's own
     * state.
     */
    fun nextRepCaption(reps: Int, withheld: Boolean = false): String? = when {
        withheld -> null
        else -> "rep ${reps + 1} ready"
    }
}
