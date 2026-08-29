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
     * THIS IS THE SHIPPED BEHAVIOUR, WRITTEN DOWN, NOT THE FIX. The load box
     * carries a label and nothing else today, which is the whole of #175's
     * report for this control. Returning null unconditionally is exactly that,
     * in a place a test can state it, so each rule the caption owes can be
     * pinned as a differential against a real answer.
     */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant", "LongParameterList")
    fun load(
        adHoc: Boolean,
        bodyweight: Boolean,
        unit: WeightUnit,
        plannedAddedKg: Double?,
        shownAddedKg: Double?,
        standsForLaterSets: Boolean,
    ): String? = null

    /** The caption under the reps box. Shipped state, as [load]. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun reps(adHoc: Boolean, plannedReps: Int?, shownReps: Int?, standsForLaterSets: Boolean): String? = null

    /** The caption under the hold box. Shipped state, as [load]. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun hold(adHoc: Boolean, plannedDurationS: Int?, shownDurationS: Int?, standsForLaterSets: Boolean): String? = null
}
