package com.macrophage.barspeed.model

/**
 * What the change-set dialog says the PLAN prescribed, under the three boxes
 * the lifter actually adjusts: load, reps and hold.
 *
 * Beside [SetDeviationSummary] because it answers the neighbouring question.
 * That object says what the lifter CHANGED, on the rest screen, without a tap;
 * this one says what the plan ASKED FOR, inside the dialog, beside the control
 * doing the changing. Tempo, prep and sensor count have said it since #152;
 * load, reps and hold -- the three changed most often -- have said nothing at
 * all. #175.
 *
 * NO FOURTH PHRASING. Two sentences ship today and both are re-used verbatim:
 *
 *  - "Plan says X - your change is recorded in the export", from the prep
 *    adjuster and the sensor-count line. It is a claim about the export and it
 *    is TRUE of all three of these boxes, each of which publishes a
 *    planned/actual pair -- load_kg beside plannedLoad_kg, reps beside
 *    plannedReps, actualDuration_s beside plannedDuration_s.
 *  - "Plan says X - the rest of this exercise runs Y unless the plan changes
 *    it", from the tempo steppers. It is a claim about REACH.
 *
 * WHICH OF THE TWO, AND WHY IT MATTERS MORE THAN IT LOOKS. With the rep and
 * hold carries landed (#174) beside the load carry (#124), a number in one of
 * these boxes can differ from the plan because of something the lifter said
 * several sets ago. The plan's PRESCRIPTION and the lifter's STANDING
 * STATEMENT are then two different facts about the same box, and a caption
 * that named only one of them would be read as naming the other. Both
 * sentences above name the plan's number explicitly as the plan's, so neither
 * can be read as attributing the shown value to the plan; the reach sentence
 * additionally says whose the shown value is and how far it goes. So the reach
 * sentence is used wherever the statement will outlive this set, and the
 * export sentence wherever it will not, and [standsForLaterSets] -- computed
 * by the carry policy itself at the call site, never guessed here -- is what
 * decides. A caption whose reach claim came from a second reading of the carry
 * rule could disagree with the carry.
 *
 * The export claim is not repeated in the reach sentence, and that is a
 * decision about space rather than about truth: the dialog's own subtitle
 * already says "deviations are recorded" over all of these controls, the reach
 * is stated nowhere else, and at 360dp with font scale 2 a third clause is how
 * a caption ends up drawn through the label beside it.
 *
 * ABSENCE IS ABSENCE. An unchanged value gets no caption, an ad-hoc set gets no
 * caption rather than "Plan says none", and a plan that declared no number for
 * a box gets no caption for that box -- with one deliberate exception, stated
 * at [load].
 *
 * `added` is `adHoc`'s neighbour and is here for the identical reason: a set the
 * LIFTER appended to the exercise mid-session has no prescription either, so
 * naming one would be an invention (#177). The two are separate parameters and
 * not one "has no plan" flag, because they are false in different directions --
 * an ad-hoc set has no plan at all, while an appended set sits inside a plan
 * block whose other sets do have prescriptions, and only the slot knows which.
 *
 * THE BODY-WEIGHT BOX IS WHY IT IS NOT ENOUGH TO RELY ON A NULL. [load] goes
 * through [plannedLoadText], which answers "BW" for a body-weight set that
 * declared no load -- deliberately, because BW is the zero of that notation --
 * so an appended pull-up would be captioned "Plan says BW" on a set nothing
 * prescribed -- a claim stronger than its evidence, in one line of UI text.
 * [reps] and [hold] already answer null on a null prescription, so for them
 * this is belt-and-braces rather than a live defect; the guard is on all three
 * anyway, because a rule that holds for one box and is enforced by accident on
 * the other two is a rule nothing states.
 *
 * `adHoc` is a parameter on all three rather than a call-site guard, and it is
 * [SetLoadPolicy.resolve]'s parameter by the same name for the same reason. The
 * dialog these captions draw in is plan-sets-only today -- the ad-hoc rest and
 * READY layouts keep their inline form -- so the guard is structural at the one
 * call site that exists; a rule enforced only by which composable happens to
 * call you is a rule nothing states, and on a body-weight ad-hoc set the answer
 * without it would be "Plan says BW" on a set that has no plan.
 */
object PlanValueCaption {
    /**
     * The plan's declared added load in the notation the box itself uses: the
     * body-weight BW± form on body-weight work (#160), a plain figure
     * otherwise, and both in the display unit the box is in.
     *
     * Null on loaded work the plan declared no load for. "Plan says 0 kg" would
     * be an invention there -- a barbell movement with the weight left to the
     * lifter has no prescription to name -- and that is the same reading #170
     * item 2 asks for one screen over.
     *
     * NOT null on body-weight work the plan declared no load for: "BW" is the
     * zero of that notation rather than an absence, which is
     * [BodyweightLoadDisplay.label]'s own documented rule, and a pull-up
     * prescribed at body weight has genuinely prescribed something.
     */
    fun plannedLoadText(bodyweight: Boolean, unit: WeightUnit, plannedAddedKg: Double?): String? = when {
        bodyweight -> BodyweightLoadDisplay.label(plannedAddedKg, unit)
        plannedAddedKg == null -> null
        else -> unit.format(plannedAddedKg)
    }

    /**
     * The caption under the load box, or null for no caption.
     *
     * [shownAddedKg] is what the box currently holds, parsed — which after
     * #124's carry may be a load the lifter stated several sets ago rather than
     * one they typed for this set. Null when the box holds nothing readable.
     *
     * DECIDED ON THE RENDERED TEXT, not on the doubles. `inputValue` quantises
     * to a tenth of the DISPLAY unit, so a plan declaring 175 lb seeds a kg box
     * with 79.4 and reads back 79.3786647517562 ≠ 79.4 on a set nobody touched;
     * comparing the two doubles would print a caption on every set of that
     * block. This is #45 one control over, and comparing what the lifter can
     * actually see is what makes the rule the same rule at both units.
     */
    @Suppress("LongParameterList")
    fun load(
        adHoc: Boolean,
        added: Boolean,
        bodyweight: Boolean,
        unit: WeightUnit,
        plannedAddedKg: Double?,
        shownAddedKg: Double?,
        standsForLaterSets: Boolean,
    ): String? {
        if (adHoc || added || shownAddedKg == null) return null
        val planned = plannedLoadText(bodyweight, unit, plannedAddedKg) ?: return null
        val shown = plannedLoadText(bodyweight, unit, shownAddedKg) ?: return null
        return caption(planned, shown, standsForLaterSets)
    }

    /**
     * The caption under the reps box. Bare numbers: the box is labelled "Reps"
     * and the sentence sits directly under it.
     */
    fun reps(
        adHoc: Boolean,
        added: Boolean,
        plannedReps: Int?,
        shownReps: Int?,
        standsForLaterSets: Boolean,
    ): String? {
        if (adHoc || added) return null
        if (plannedReps == null || shownReps == null) return null
        return caption("$plannedReps", "$shownReps", standsForLaterSets)
    }

    /** The caption under the hold box, in seconds, as its label is. */
    fun hold(
        adHoc: Boolean,
        added: Boolean,
        plannedDurationS: Int?,
        shownDurationS: Int?,
        standsForLaterSets: Boolean,
    ): String? {
        if (adHoc || added) return null
        if (plannedDurationS == null || shownDurationS == null) return null
        return caption("${plannedDurationS}s", "${shownDurationS}s", standsForLaterSets)
    }

    /**
     * One sentence, chosen between the two that already ship, or null when the
     * two figures read the same and there is nothing to say.
     *
     * The equality test is on the rendered strings for the reason [load]
     * gives, and it is written once here so that the load, the reps and the
     * hold cannot answer "has this changed" three different ways.
     */
    private fun caption(planned: String, shown: String, standsForLaterSets: Boolean): String? = when {
        planned == shown -> null
        standsForLaterSets -> "Plan says $planned - the rest of this exercise runs $shown unless the plan changes it"
        else -> "Plan says $planned - your change is recorded in the export"
    }
}
