package com.macrophage.barspeed.model

/**
 * One planned slot, as much of it as deciding the NEXT set's inputs reads.
 *
 * Two halves, and they must not be swapped. The `planned*` fields are the
 * plan's FROZEN declarations, never written back to; the carry policies compare
 * those, because the bake writes a lifter's statement into the live half and
 * comparing live values would compare a statement against itself. The live half
 * (`loadKg`, `reps`, `durationS`, `tempo`) is what the slot would run with today,
 * and it is what an untouched box is seeded from.
 *
 * Plain data so that `:app`'s `PlannedSlot` can be projected onto it and every
 * caller asks [NextSetInputs.of] the same question.
 */
data class NextSetSlot(
    val exerciseId: String,
    val setIndexInExercise: Int,
    val bodyweight: Boolean,
    val warmup: Boolean,
    val plannedLoadKg: Double?,
    val plannedReps: Int?,
    val plannedDurationS: Int?,
    val plannedTempo: String?,
    val loadKg: Double?,
    val reps: Int?,
    val durationS: Int?,
    val tempo: String?,
)

/**
 * What the lifter has STATED for the set the boxes were last describing: the
 * load, rep count, hold and tempo they actually gave, each null when they gave
 * nothing. A seeded box is not a statement (#45).
 */
data class NextSetStatements(
    val loadKg: Double?,
    val reps: Int?,
    val durationS: Int?,
    val tempo: String?,
) {
    companion object {
        /**
         * Nothing stated. What the reducers that CLEAR the statements pass --
         * switching exercise, and an append or a removal that changes which set
         * comes next -- so the boxes follow the new slot's own declarations.
         */
        val NONE = NextSetStatements(loadKg = null, reps = null, durationS = null, tempo = null)
    }
}

/**
 * The working values of a set that has just been RUN, which is the only thing
 * to go on where the plan offers nothing. Only the rest transition has one: a
 * skipped, appended, removed or switched-to slot has not run.
 */
data class LastRanSet(
    val addedKg: Double?,
    val reps: Int?,
    val durationS: Int?,
    val tempo: String?,
)

/**
 * The next set's inputs -- the statements that still stand for it and the
 * values its boxes open on -- decided ONCE for every route that changes which
 * set comes next: the rest transition, skip, append, remove and switch
 * exercise, and the change dialog's "stands for later sets" caption.
 *
 * Each of those used to call [SetLoadPolicy.sameExerciseBlock],
 * [SetLoadPolicy.standingStatedAddedKg], [TempoAdjustPolicy.standingAdjustedTempo],
 * [SetRepsPolicy.standingStatedReps], [SetRepsPolicy.standingStatedDurationS],
 * [SetLoadPolicy.seedAddedKg] and [TempoAdjustPolicy.seedTempo] by hand, with
 * its own fallbacks, so a change to one copy could leave the caption promising
 * a carry the next set did not get. Code-health review 2026-11-06, F2.
 *
 * [statedKg], [statedReps], [statedDurationS] and [statedTempo] are the
 * statements that still stand for the next set, null where it is offered its
 * own declaration. The other four are what the boxes show; a null there means
 * "nothing to seed from, leave the box as it is".
 */
data class NextSetInputs(
    val statedKg: Double?,
    val statedReps: Int?,
    val statedDurationS: Int?,
    val statedTempo: String?,
    /** The load box: a standing statement ahead of the slot's own load. */
    val loadKg: Double?,
    /** The reps box. */
    val reps: Int?,
    /** The hold box. */
    val durationS: Int?,
    /**
     * The AD-HOC tempo field, never null: "" is the field holding nothing. On a
     * plan session nothing reads it; the wheels read [statedTempo] and the slot.
     */
    val adHocTempo: String,
) {
    companion object {
        /**
         * What the reps box falls back to when the set that just ran declared
         * no count either -- a timed set, or an ad-hoc one left blank. It is
         * `RecordState.repsInput`'s own default, and only the rest transition
         * reaches it.
         */
        const val FALLBACK_REPS = 5

        /**
         * The next set's inputs.
         *
         * [last] is the slot the statements were made against: the set just
         * finished on the rest transition, the SKIPPED slot on a skip (the boxes
         * were describing it), and the set being set up for the caption. Null
         * where there is no such slot -- an ad-hoc set -- and nothing carries
         * across that.
         *
         * [next] is the slot the inputs are for, null where the plan has run out.
         *
         * [lastRan] is the set that just ran, and only the rest transition has
         * one. It is consulted for the boxes alone and never for a standing
         * statement, which is what lets the caption -- which has no set that ran
         * -- give the same answer the rest transition will. Where it is given,
         * the reps and hold boxes fall back to it whenever [next] declares no
         * count of that kind: at the end of the plan, and on the box a planned
         * slot hides (a plan set declares exactly one of reps / duration_s).
         * Where it is absent those boxes are left as they are.
         */
        fun of(
            last: NextSetSlot?,
            next: NextSetSlot?,
            stated: NextSetStatements,
            lastRan: LastRanSet? = null,
        ): NextSetInputs {
            // Where the block ends, asked once: all four statements are bounded
            // by the SAME block.
            val sameBlock =
                SetLoadPolicy.sameExerciseBlock(
                    lastExerciseId = last?.exerciseId,
                    nextExerciseId = next?.exerciseId,
                    nextSetIndexInExercise = next?.setIndexInExercise,
                )
            val standingKg =
                SetLoadPolicy.standingStatedAddedKg(
                    statedAddedKg = stated.loadKg,
                    sameExerciseBlock = sameBlock,
                    lastDeclaredAddedKg = last?.plannedLoadKg,
                    nextDeclaredAddedKg = next?.plannedLoadKg,
                    // The COMING slot's movement: inside a block the two are the
                    // same exercise by construction.
                    bodyweight = next?.bodyweight ?: false,
                    // The plan's own warm-up declaration on each side, never the
                    // lifter's live mark.
                    finishedWarmup = last?.warmup == true,
                    nextWarmup = next?.warmup == true,
                )
            val standingTempo =
                TempoAdjustPolicy.standingAdjustedTempo(
                    adjustedTempo = stated.tempo,
                    sameExerciseBlock = sameBlock,
                    lastDeclaredTempo = last?.plannedTempo,
                    nextDeclaredTempo = next?.plannedTempo,
                )
            val standingReps =
                SetRepsPolicy.standingStatedReps(
                    statedReps = stated.reps,
                    sameExerciseBlock = sameBlock,
                    lastDeclaredReps = last?.plannedReps,
                    nextDeclaredReps = next?.plannedReps,
                )
            val standingDurationS =
                SetRepsPolicy.standingStatedDurationS(
                    statedDurationS = stated.durationS,
                    sameExerciseBlock = sameBlock,
                    lastDeclaredDurationS = last?.plannedDurationS,
                    nextDeclaredDurationS = next?.plannedDurationS,
                )
            val seedKg =
                SetLoadPolicy.seedAddedKg(
                    hasPlannedNext = next != null,
                    // The live load, which carries any edit already applied to
                    // the slot -- never plannedLoadKg.
                    nextDeclaredAddedKg = next?.loadKg,
                    // ADDED load, never the body-weight-inclusive total.
                    lastAddedKg = lastRan?.addedKg,
                )
            return NextSetInputs(
                statedKg = standingKg,
                statedReps = standingReps,
                statedDurationS = standingDurationS,
                statedTempo = standingTempo,
                loadKg = standingKg ?: seedKg,
                reps = standingReps ?: next?.reps ?: lastRan?.let { it.reps ?: FALLBACK_REPS },
                durationS = standingDurationS ?: next?.durationS ?: lastRan?.durationS,
                adHocTempo =
                TempoAdjustPolicy.seedTempo(
                    hasPlannedNext = next != null,
                    nextDeclaredTempo = next?.tempo,
                    lastRanTempo = lastRan?.tempo,
                ) ?: "",
            )
        }
    }
}
