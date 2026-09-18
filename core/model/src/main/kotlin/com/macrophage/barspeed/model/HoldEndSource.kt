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
 * ## What this answers today
 *
 * The rule as it stands before #259's fix: the clock's target on an auto-ended
 * hold, the measurement otherwise. [sensorEndS] is accepted and NOT consulted,
 * so a set whose stream carries a release still publishes the seconds that ran
 * to the tap. The differential pins in `HoldEndPolicyDifferentialTest` are
 * written against that and fail here on purpose.
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

    /** The seconds a finished timed set records, and who decided them. */
    data class Decision(val seconds: Int, val endedBy: HoldEndSource)

    /**
     * [measuredS] is the clock's own span, from `SetClockPolicy.heldSeconds`.
     * [targetS] is the seconds the set was working to, null on an ad-hoc hold.
     * [autoEnded] says the app's clock ended the set. [sensorEndS] is the same
     * span measured to the release instant instead of the tap, or null where
     * no armed unit saw one.
     */
    // The suppression IS the statement: [sensorEndS] is accepted and not read,
    // so the differentials can be written against this signature and fail. The
    // commit that reads it deletes this line.
    @Suppress("UnusedParameter")
    fun decide(measuredS: Int, targetS: Int?, autoEnded: Boolean, sensorEndS: Int?): Decision {
        val recorded = TimedSetEndPolicy.recordedSeconds(measuredS, targetS, autoEnded)
        return Decision(recorded, if (autoEnded) HoldEndSource.CLOCK else HoldEndSource.LIFTER)
    }

    /**
     * The seconds one tap of the rest screen's DOWN correction offers, largest
     * step last.
     *
     * Today: one step, [TimedSetEndPolicy.CORRECTION_STEP_S], whatever ended
     * the hold. `HoldEndPolicyDifferentialTest` states what #259 asks for
     * instead and fails here.
     */
    fun downStepsS(endedBy: HoldEndSource?): List<Int> = when (endedBy) {
        // Every case, null included, and enumerated rather than collapsed to an
        // `else` so that the commit adding the second step has to visit each
        // one. #259 asks for a larger step where the sensor did NOT decide the
        // end; `HoldEndPolicyDifferentialTest` states that and fails here.
        null,
        HoldEndSource.CLOCK,
        HoldEndSource.SENSOR,
        HoldEndSource.LIFTER,
        HoldEndSource.CORRECTED,
        -> listOf(TimedSetEndPolicy.CORRECTION_STEP_S)
    }
}
