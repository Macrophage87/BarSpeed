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
    @Suppress("LongParameterList")
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
        val plannedLoad = plannedLoadKg ?: 0.0
        // What will be RECORDED, by SetLoadPolicy.resolve's own rule for a
        // planned set: the statement if there is one, else the declaration,
        // else nothing added. Comparing that against the declaration is what
        // keeps a stated 0 on a loadless plan silent while a stated 0 on a
        // 90 kg plan speaks -- a lifter who stripped the bar has said
        // something, and a truthiness guard here would silence exactly them.
        val load = statedLoadKg ?: plannedLoad
        val loadPart =
            (load != plannedLoad).ifTrue {
                if (bodyweight) BodyweightLoadDisplay.label(load, unit) else unit.format(load)
            }
        val repsPart =
            (statedReps != null && plannedReps != null && statedReps != plannedReps)
                .ifTrue { "$statedReps reps" }
        val holdPart =
            (statedDurationS != null && plannedDurationS != null && statedDurationS != plannedDurationS)
                .ifTrue { "${statedDurationS}s ${if (kind == ExerciseKind.CARRY) "carry" else "hold"}" }
        // Compared as the four values a stepper can show rather than as text,
        // so "4-0-1-0" and "4010" are one prescription. wheelValues is the
        // same function the control itself is drawn from, so a tempo it cannot
        // show falls back to the raw strings -- unequal there means the plan
        // and the set really do differ, and the control was not what changed
        // it.
        val tempoPart =
            (plannedTempo != null && tempo != null && !sameTempo(plannedTempo, tempo))
                .ifTrue { "tempo $tempo" }
        val prepPart = (prepS != plannedPrepS).ifTrue { "prep ${prepS}s" }
        return listOfNotNull(loadPart, repsPart, holdPart, tempoPart, prepPart)
    }

    /** Whether two tempo strings are the same prescription, dashes and all. */
    private fun sameTempo(a: String, b: String): Boolean {
        val left = TempoAdjustPolicy.wheelValues(a)?.joinToString("") ?: a
        val right = TempoAdjustPolicy.wheelValues(b)?.joinToString("") ?: b
        return left == right
    }

    private inline fun Boolean.ifTrue(text: () -> String): String? = if (this) text() else null
}
