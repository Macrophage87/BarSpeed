package com.macrophage.barspeed.model

/**
 * Which of the four things that can end a hold decided the seconds it
 * publishes. Issue #259, and #249's ask for a corrected figure's provenance
 * rides on the same word.
 *
 * `duration_s` has had three producers since #168 -- the clock at the target,
 * the lifter's tap, and a correction tapped afterwards on the rest screen --
 * and published no way to tell them apart. #249 measured what that costs on
 * field-38's two dead hangs: 31 s and 22 s published against 36.228 s and
 * 32.188 s of measured clock, both differences exact multiples of the 5 s
 * correction step, and no key in the export saying so. A reader could not tell
 * a good correction from a double tap from a defect in the hold clock.
 *
 * [published] is the word the export carries. One owner of the four strings:
 * `SessionExport`, the archive manifest, the provenance column and the SQL the
 * correction writes all read them from here rather than spelling them.
 */
enum class HoldEndSource(val published: String) {
    /**
     * The app's own clock reached the target and ended the set (#168). The
     * lifter heard `Time` a beat earlier and the recorded seconds are the
     * target itself.
     */
    CLOCK("clock"),

    /**
     * An armed unit's stream says when the implement was let go, and that
     * instant -- not the tap that came after it -- is what the seconds were
     * measured to. `HoldRelease` in `:core:dsp` finds it.
     */
    SENSOR("sensor"),

    /**
     * The lifter's tap ended the set and nothing else spoke for the instant:
     * no unit was armed, or the armed unit's stream carried no release. The
     * walk back to the phone is inside these seconds, which is the defect #259
     * names and this is the word for the case it cannot fix.
     */
    LIFTER("lifter"),

    /**
     * The lifter restated the figure afterwards on the rest screen. It
     * overwrites whichever of the three above stood before it: what is
     * published is the lifter's number, so the lifter is who the export names.
     * The pre-correction word is NOT retained -- one column, one answer to who
     * decided the published figure -- and the raw span remains recoverable
     * from the archive's `workStartedAt_ms` and the row's `endedAt_ms`.
     */
    CORRECTED("corrected"),

    ;

    companion object {
        /**
         * The [published] word back to the constant, or null for anything else.
         *
         * The export reads the stored column through this rather than passing
         * it along, so the only words that can reach `session.json` are the
         * four above: the column is TEXT, a row written by a build this one
         * does not know about could hold anything, and the published key is
         * schema-constrained to these four.
         */
        fun ofPublished(word: String?): HoldEndSource? = entries.firstOrNull { it.published == word }
    }
}

/**
 * What a finished timed set records, and which of [HoldEndSource]'s four
 * answers decided it.
 *
 * ## Why this is a decision and not arithmetic
 *
 * [TimedSetEndPolicy.recordedSeconds] already chooses between the measurement
 * and the target for a set the clock ended. #259 adds a third candidate for
 * the instant the measurement runs TO -- the release an armed unit saw -- and
 * a third candidate is where a two-way `if` stops being arithmetic: the same
 * figure now has to say where it came from, because a reader cannot recover
 * that from the number.
 *
 * ## The rule
 *
 * The clock's target on a hold the clock ended. Otherwise the release an armed
 * unit saw, where it saw one and where believing it takes no more than
 * [MAX_TRIM_S] seconds off. The tap, where neither spoke.
 *
 * ## Why the clock is not overruled
 *
 * An auto-ended hold does not consult the sensor at all, and that is a decision
 * rather than an omission. The lifter heard `Time` and put the implement down on
 * the word; the figure recorded is the one the voice announced, measured by the
 * app against an instant the app itself chose (#168). Letting a release shorten
 * it would record less than the lifter was told they had completed, on the
 * strength of one crossing -- silent loss on the progression metric, in the
 * direction the archive cannot argue with.
 *
 * WHAT THAT LEAVES OPEN, stated rather than left to be found: a lifter who lets
 * go early and never taps still has the clock end the set at the target and
 * record the target. Nothing here changes that; the bigger correction step is
 * offered on exactly those sets ([downStepsS]).
 *
 * ## What the sensor does NOT decide
 *
 * The verdict. `failedByLifter` is the lifter's own word and
 * [TimedSetEndPolicy.fellShort] is derived from the recorded seconds; a release
 * moves the SECONDS and nothing else. On both field-38 hangs the shortened
 * figure is still under [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] of 45 s, so
 * the verdict is unchanged there -- but a hold ended by hand at 43 s of 45 whose
 * release sits at 36 would newly read as short, which is the right answer and is
 * pinned.
 */
object HoldEndPolicy {
    /**
     * The most seconds a sensor release may take off a hold the lifter ended,
     * in seconds.
     *
     * What a sensor end claims to remove is the walk back to the phone, and a
     * walk has a size: the owner's own estimate for a hands-full hold is 5-10
     * seconds, #172 measured the interval between the app calling a set over
     * and the phone being picked up at 4.3 to 13.7 s across session 32's
     * eleven qualifying sets, and field-38's two dead hangs put it at 7.04 s
     * and 6.13 s. Twenty is above every one of those with margin.
     *
     * The cap is what a crossing this file cannot otherwise account for costs:
     * beyond it the release is not a reach, the sensor is not believed, and the
     * tap stands. It is a fitted number and is stated as one.
     */
    const val MAX_TRIM_S = 20

    /**
     * Seconds the LARGER of the rest screen's two down steps moves the recorded
     * hold by.
     *
     * Ten rather than another five, because what it is for is the whole reach in
     * one tap: the owner's estimate is "about 5-10 sec" and #172 measured the
     * interval at 4.3 to 13.7 s, so two taps of five is what the fine step costs
     * on a rest screen with a countdown running. Not a replacement for
     * [TimedSetEndPolicy.CORRECTION_STEP_S] -- the fine step is what states a
     * genuine overage, and both are offered together.
     */
    const val BIG_CORRECTION_STEP_S = 10

    /** The seconds a finished timed set records, and who decided them. */
    data class Decision(val seconds: Int, val endedBy: HoldEndSource)

    /**
     * [measuredS] is the clock's own span, from `SetClockPolicy.heldSeconds`.
     * [targetS] is the seconds the set was working to, null on an ad-hoc hold.
     * [autoEnded] says the app's clock ended the set. [sensorEndS] is the same
     * span measured to the release instant instead of the tap, or null where
     * no armed unit saw one.
     */
    fun decide(measuredS: Int, targetS: Int?, autoEnded: Boolean, sensorEndS: Int?): Decision {
        if (autoEnded) {
            return Decision(
                TimedSetEndPolicy.recordedSeconds(measuredS, targetS, autoEnded = true),
                HoldEndSource.CLOCK,
            )
        }
        // The trim is what believing the sensor costs the figure, and both ends
        // of the range matter. Zero or less is a release at or after the tap --
        // nothing to remove, and a sensor end must never LENGTHEN a hold. Beyond
        // the cap it is not a reach, so the tap stands.
        val trimS = sensorEndS?.let { measuredS - it }
        if (sensorEndS != null && trimS != null && trimS in 1..MAX_TRIM_S) {
            return Decision(sensorEndS, HoldEndSource.SENSOR)
        }
        return Decision(
            TimedSetEndPolicy.recordedSeconds(measuredS, targetS, autoEnded = false),
            HoldEndSource.LIFTER,
        )
    }

    /**
     * The seconds one tap of the rest screen's DOWN correction offers, smallest
     * step first.
     *
     * TWO STEPS WHEREVER THE REACH IS STILL IN THE FIGURE, one where it is not.
     * [TimedSetEndPolicy.CORRECTION_STEP_S] is five, sized for the walk back to
     * the phone, and the owner's own estimate of that walk is "about 5-10 sec" --
     * so on the sets that still carry it one tap is not enough, and the second
     * step is [BIG_CORRECTION_STEP_S].
     *
     * Enumerated rather than collapsed to an `else`, so a fifth
     * [HoldEndSource] cannot inherit an answer nobody chose for it.
     *
     * - [HoldEndSource.SENSOR]: the release already decided the figure, so there
     *   is no reach left to remove and the fine step is all that is wanted.
     * - [HoldEndSource.CLOCK]: the case that is easy to miss. A lifter who lets
     *   go at 20 s of a 30 s hold and walks away still has the clock end it at
     *   the target and record the target, so the overstatement can be ten
     *   seconds or more.
     * - [HoldEndSource.LIFTER]: the defect #259 could not fix -- no unit armed,
     *   or one that saw nothing -- so the whole reach is inside the figure.
     * - [HoldEndSource.CORRECTED]: what decided the pre-correction figure is no
     *   longer on the row, so the control stays as capable as it was.
     * - null: a set recorded before database v19, where absence means the build
     *   could not say. Not a sensor end.
     */
    fun downStepsS(endedBy: HoldEndSource?): List<Int> = when (endedBy) {
        HoldEndSource.SENSOR -> listOf(TimedSetEndPolicy.CORRECTION_STEP_S)
        null,
        HoldEndSource.CLOCK,
        HoldEndSource.LIFTER,
        HoldEndSource.CORRECTED,
        -> listOf(TimedSetEndPolicy.CORRECTION_STEP_S, BIG_CORRECTION_STEP_S)
    }
}
