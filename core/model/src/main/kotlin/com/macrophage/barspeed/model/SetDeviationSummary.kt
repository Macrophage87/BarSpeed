package com.macrophage.barspeed.model

/**
 * What the lifter has changed about the set coming up, said on the rest screen
 * without a tap.
 *
 * This exists because #152 puts the load box, the reps box, the tempo steppers
 * and the prep adjuster behind a button. The "Up next" card above them keeps
 * stating the PLAN's numbers — deliberately, because the card is the
 * prescription and the plate line beside it is the instruction — and today the
 * lifter reconciles the two by reading the boxes. Hide the boxes and the
 * disagreement goes with them: the card says "Up next — 90 kg" while the set
 * will record 100, and nothing on screen says so. A control that hides a fact
 * is worse than one that crowds a screen.
 *
 * So this is drawn UNDER the card and BEFORE the button, and it is the reason
 * the button is allowed to hide anything at all.
 *
 * Absence is absence: an untouched set produces an empty list and the screen
 * draws nothing, rather than a line reading "no changes". A line that is
 * always there is a line the lifter stops reading, which is the one failure
 * mode this control cannot afford.
 */
object SetDeviationSummary {
    /**
     * The lifter's changes to the coming set, in the order the controls are
     * laid out behind the button: load, reps or hold, tempo, prep. Empty when
     * the set will run exactly as the plan prescribed it.
     *
     * Every "planned" argument is the FROZEN declaration — `plannedLoadKg`,
     * `plannedTempo`, the slot's own `reps`/`durationS` before any bake, and
     * `LeadInPolicy.planned` for the prep. Never the live field beside it: the
     * live one carries the lifter's edit once `advancedState` has baked it in,
     * so comparing those two would compare a number against itself and this
     * line would go blank at the exact moment it has something to say.
     */
    @Suppress("UnusedParameter", "LongParameterList")
    fun parts(
        kind: ExerciseKind,
        bodyweight: Boolean,
        unit: WeightUnit,
        plannedLoadKg: Double?,
        statedLoadKg: Double?,
        plannedReps: Int?,
        statedReps: Int?,
        plannedDurationS: Int?,
        statedDurationS: Int?,
        plannedTempo: String?,
        tempo: String?,
        plannedPrepS: Int,
        prepS: Int,
    ): List<String> {
        // SEAM ONLY: today there is no deviation line at all, so the seam says
        // nothing and every differential that gives it something to say can be
        // pushed red against it. The two suppressions are the marker and leave
        // with the body.
        return emptyList()
    }
}
