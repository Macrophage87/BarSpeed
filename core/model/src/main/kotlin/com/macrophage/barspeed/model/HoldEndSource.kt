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
     * The app's own clock reached the target and ended the set (#168), and no
     * armed unit reported a release 1 to [HoldEndPolicy.MAX_TRIM_S] seconds
     * before it (#311). The lifter heard `Time` a beat earlier and the
     * recorded seconds are the target itself.
     */
    CLOCK("clock"),

    /**
     * An armed unit's stream says when the implement was let go, and that
     * instant -- not the tap that came after it, nor, since #311, the clock's
     * end at the target on a hold nobody tapped -- is what the seconds were
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
 * and the target, by who ended the set. #259 adds a third candidate for
 * the instant the measurement runs TO -- the release an armed unit saw -- and
 * a third candidate is where a two-way `if` stops being arithmetic: the same
 * figure now has to say where it came from, because a reader cannot recover
 * that from the number.
 *
 * ## The rule
 *
 * First the figure that stands without the sensor: the clock's target on a
 * hold the clock ended, the measurement to the tap on one the lifter ended.
 * Then the release an armed unit saw, where it saw one and where believing it
 * takes 1 to [MAX_TRIM_S] seconds off THAT figure. The standing figure, where
 * the release is absent, at or after it, or further off than the cap.
 *
 * ## A clock end consults the release too (#311)
 *
 * Until #311 an auto-ended hold did not consult the sensor at all, and the
 * paragraph that stood here argued that as a decision. It is deleted rather
 * than reworded, because field-45 measured what it cost: set 13, a rope dead
 * hang planned at 35 s, let go 30.742 s in by its analysed unit's stream and
 * never tapped -- the owner's phone is 5-10 s away on a hands-full hold -- so
 * the clock ended it at 35 and the code recorded 35 under
 * [HoldEndSource.CLOCK]. Only the lifter's correction restated it.
 *
 * What survives of the old argument is the direction it guarded. A hold that
 * ran to `Time` must not record less than the voice told the lifter they had
 * completed. A release at or after the target takes zero or fewer seconds off
 * and is refused, and a hold whose stream crosses nothing keeps the target
 * (field-42 set 16, pinned in `HoldReleaseFieldTest`). The
 * risk left is a false crossing 1 to [MAX_TRIM_S] seconds before a target the
 * lifter did reach. `HoldRelease`'s settle is fitted to one measured onset and
 * its band's margins are measured on four committed hold streams, so that risk
 * is bounded by what was observed, not by design.
 *
 * WHAT IS STILL OPEN: a hold with no unit armed, or one whose unit saw no
 * release, still has the clock end it at the target and record the target
 * when the lifter let go early. The bigger correction step is offered on
 * exactly those sets ([downStepsS]).
 *
 * ## What the sensor does NOT decide
 *
 * The verdict. `failedByLifter` is the lifter's own word and
 * [TimedSetEndPolicy.fellShort] is derived from the recorded seconds; a release
 * moves the SECONDS and nothing else. On both field-38 hangs the shortened
 * figure is still under [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] of 45 s, so
 * the verdict is unchanged there -- but a hold ended by hand at 43 s of 45 whose
 * release sits at 36 would newly read as short, which is the right answer and is
 * pinned. The same derivation reaches a clock end since #311: field-45 set 13's
 * 30 of 35 derives short where its target did not, which is the answer the
 * lifter gave himself when he tapped the failure tile.
 */
object HoldEndPolicy {
    /**
     * The most seconds a sensor release may take off the figure that would
     * otherwise stand -- the tap's measurement, or since #311 the clock's
     * target -- in seconds.
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
     * The least the larger of the rest screen's two down steps moves the
     * recorded hold by, in seconds, unless the floor at zero stops it sooner:
     * a 7 s draft steps to 0 and is labelled "−7s".
     *
     * It survives #312, and for a new reason. It was sized for the walk back
     * to the phone -- the owner's "about 5-10 sec", #172's 4.3 to 13.7 s -- as
     * two taps of a five-second fine step. Since #312 the fine step inside the
     * last [TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S] seconds is ONE second, and
     * that is exactly where a hold the clock ended at its target sits: 0 s to
     * go. The owner's own case, "so I'd know if I let go around 10 sec to go",
     * would be ten taps without it; with it, a 45 of 45 is one tap from
     * "Held 35s · 10s to go".
     *
     * Where the figure is off the voice's marks it no longer moves exactly ten:
     * [bigStepDownSeconds] carries it on to the next mark, and the label says
     * how far it actually went. Offered where [downStepsS] offers it, beside
     * the fine step and never instead of it.
     */
    const val BIG_CORRECTION_STEP_S = 10

    /** The seconds a finished timed set records, and who decided them. */
    data class Decision(val seconds: Int, val endedBy: HoldEndSource)

    /**
     * [measuredS] is the clock's own span, from `SetClockPolicy.heldSeconds`.
     * [targetS] is the seconds the set was working to, null on an ad-hoc hold.
     * [autoEnded] says the app's clock ended the set. [sensorEndS] is the same
     * span measured to the release instant instead of to the tap or the
     * clock's end, or null where no armed unit saw one.
     */
    fun decide(measuredS: Int, targetS: Int?, autoEnded: Boolean, sensorEndS: Int?): Decision {
        // What stands without the sensor: the target on a hold the clock ended
        // (#168), the measurement to the tap on one the lifter ended.
        val standingS = TimedSetEndPolicy.recordedSeconds(measuredS, targetS, autoEnded)
        // The trim is what believing the sensor costs that figure, and both
        // ends of the range matter. Zero or less is a release at or after the
        // tap or the target -- nothing to remove, and a sensor end must never
        // LENGTHEN a hold. Beyond the cap it is not a reach, so the standing
        // figure stands.
        val trimS = sensorEndS?.let { standingS - it }
        if (sensorEndS != null && trimS != null && trimS in 1..MAX_TRIM_S) {
            return Decision(sensorEndS, HoldEndSource.SENSOR)
        }
        return Decision(standingS, if (autoEnded) HoldEndSource.CLOCK else HoldEndSource.LIFTER)
    }

    /**
     * The seconds one tap of the rest screen's DOWN correction offers, smallest
     * step first.
     *
     * TWO STEPS WHEREVER THE REACH IS STILL IN THE FIGURE, one where it is not.
     * The values are the steps' nominal sizes, the flat pair a hold with no
     * target steps by; with a target, where each step LANDS is
     * [steppedSeconds]' and [bigStepDownSeconds]' decision (#312), and this
     * answers only whether the second, larger step is offered.
     *
     * Enumerated rather than collapsed to an `else`, so a fifth
     * [HoldEndSource] cannot inherit an answer nobody chose for it.
     *
     * - [HoldEndSource.SENSOR]: the release already decided the figure, so there
     *   is no reach left to remove and the fine step is all that is wanted.
     * - [HoldEndSource.CLOCK]: the case that is easy to miss. Since #311 a
     *   reported release 1 to MAX_TRIM_S s before the target turns such a hold
     *   into a sensor end, but a lifter who lets go at 20 s of a 30 s hold with
     *   no unit armed, or one that saw nothing, still has the clock end it at
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

    /**
     * Everything the Correct popup's hold row draws: the figure, where each
     * control moves the draft to, and the larger down step's label, or null
     * where no larger step is offered.
     */
    data class HoldCorrection(
        val figure: String,
        val downS: Int,
        val upS: Int,
        val bigDownS: Int?,
        val bigDownLabel: String?,
    )

    /**
     * The hold row for a draft of [seconds] against [targetS], the seconds the
     * set was working to (null on an ad-hoc hold), ended by [endedBy].
     *
     * The composable reads every field of the answer and decides nothing.
     */
    fun correction(seconds: Int, targetS: Int?, endedBy: HoldEndSource?): HoldCorrection {
        // Offered where downStepsS offers it AND it would move the draft: a
        // control that does nothing is not drawn. The label is the distance
        // it really moves, which off the marks is more than ten.
        val bigDownS =
            bigStepDownSeconds(seconds, targetS).takeIf { downStepsS(endedBy).size > 1 && it != seconds }
        return HoldCorrection(
            figure = heldFigure(seconds, targetS),
            downS = steppedSeconds(seconds, targetS, up = false),
            upS = steppedSeconds(seconds, targetS, up = true),
            bigDownS = bigDownS,
            bigDownLabel = bigDownS?.let { "−${seconds - it}s" },
        )
    }

    /**
     * The draft after one tap of the fine step, [up] or down (#312).
     *
     * The owner's rule: "Allow adjustments for holds to be based with the same
     * increment" the voice counts in. With a target, the step works on the
     * time LEFT, as `TimedSetVoice` does:
     *
     * - more than [TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S] seconds left: the
     *   next remaining time that is a multiple of [TimedSetEndPolicy.MARK_EVERY_S]
     *   in the direction stepped -- a mark the voice named. A figure off the
     *   marks (a sensor end at 33 of 45, 12 s left) snaps to the nearest one in
     *   that direction, 15 left down and 10 left up.
     * - inside the last [TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S] seconds: one
     *   second, because the voice said every one of them.
     * - past the target: [TimedSetEndPolicy.CORRECTION_STEP_S] up, as an
     *   overage was always stated, and down by the same but never past the
     *   target, the one mark on that side. At the target itself the up step
     *   is the same, and the down step is the one second of the bullet above:
     *   0 s left is inside the last [TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S].
     *
     * The same rule for every [HoldEndSource]: what ended the hold decides
     * whether a larger step is offered ([downStepsS]), not where the fine one
     * lands. With no target, or a target of zero or less, there are no marks
     * and the step is the flat [TimedSetEndPolicy.CORRECTION_STEP_S] it always
     * was. Floored at zero by [TimedSetEndPolicy.adjustedSeconds], as the
     * write floors.
     */
    fun steppedSeconds(currentS: Int, targetS: Int?, up: Boolean): Int {
        val target = targetS?.takeIf { it > 0 }
        if (target == null) {
            val stepS = if (up) TimedSetEndPolicy.CORRECTION_STEP_S else -TimedSetEndPolicy.CORRECTION_STEP_S
            return TimedSetEndPolicy.adjustedSeconds(currentS, stepS)
        }
        val leftS = target - currentS
        val nextLeftS = if (up) leftAfterStepUp(leftS) else leftAfterStepDown(leftS)
        return TimedSetEndPolicy.adjustedSeconds(target, -nextLeftS)
    }

    /**
     * The draft after one tap of the larger down step.
     *
     * With a target: at least [BIG_CORRECTION_STEP_S] seconds more left, carried
     * on to the next mark where that lands above the last
     * [TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S] seconds and off the marks --
     * 45 of 45 lands on 35 (10 left), 42 of 45 on 30 (15 left). With no target,
     * the flat [BIG_CORRECTION_STEP_S]. Floored at zero.
     */
    fun bigStepDownSeconds(currentS: Int, targetS: Int?): Int {
        val target = targetS?.takeIf { it > 0 }
            ?: return TimedSetEndPolicy.adjustedSeconds(currentS, -BIG_CORRECTION_STEP_S)
        val movedLeftS = target - currentS + BIG_CORRECTION_STEP_S
        val nextLeftS =
            if (movedLeftS > TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S) markAtOrAbove(movedLeftS) else movedLeftS
        return TimedSetEndPolicy.adjustedSeconds(target, -nextLeftS)
    }

    /**
     * The hold row's figure: the held total, and the time left to [targetS]
     * (#312), e.g. "Held 35s · 10s to go" -- so a lifter who let go around
     * ten to go can see the correction land there.
     *
     * At or past the target it says "target reached" rather than a zero or a
     * negative time to go. With no target, or a target of zero or less, the
     * total alone: there is nothing to go to.
     */
    fun heldFigure(seconds: Int, targetS: Int?): String {
        val target = targetS?.takeIf { it > 0 } ?: return "Held ${seconds}s"
        val leftS = target - seconds
        return if (leftS > 0) "Held ${seconds}s · ${leftS}s to go" else "Held ${seconds}s · target reached"
    }

    private fun leftAfterStepUp(leftS: Int): Int = when {
        leftS > TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S ->
            (leftS - 1) / TimedSetEndPolicy.MARK_EVERY_S * TimedSetEndPolicy.MARK_EVERY_S
        leftS > 0 -> leftS - 1
        else -> leftS - TimedSetEndPolicy.CORRECTION_STEP_S
    }

    private fun leftAfterStepDown(leftS: Int): Int = when {
        leftS < 0 -> minOf(leftS + TimedSetEndPolicy.CORRECTION_STEP_S, 0)
        leftS < TimedSetEndPolicy.FINAL_COUNTDOWN_FROM_S -> leftS + 1
        else -> (leftS / TimedSetEndPolicy.MARK_EVERY_S + 1) * TimedSetEndPolicy.MARK_EVERY_S
    }

    private fun markAtOrAbove(leftS: Int): Int =
        (leftS + TimedSetEndPolicy.MARK_EVERY_S - 1) / TimedSetEndPolicy.MARK_EVERY_S * TimedSetEndPolicy.MARK_EVERY_S
}
