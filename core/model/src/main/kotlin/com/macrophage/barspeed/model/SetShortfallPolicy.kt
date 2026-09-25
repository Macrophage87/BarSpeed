package com.macrophage.barspeed.model

/**
 * Whether a set ended short of the target it ran against -- the derived half
 * of `failed`, never the lifter's tapped half (#157).
 *
 * It lived as the `stoppedEarly` `when` inside `RecordViewModel.endSet` and
 * as the count rule inside [SetCorrectionPolicy.shortfall], each written out
 * on its own. Lifted here, the set write and the rest-screen correction ask
 * the same two rules, and every branch is a literal in a test that runs on
 * every push. Behaviour is unchanged by the lift.
 *
 * ## Judged against the WORKING target, never the plan
 *
 * The working target is the figure the set ran against after any in-app
 * control raised or lowered it: `targetReps` and `targetDurationS` on the
 * set write. The plan's figure is not a parameter of anything here, so no
 * branch can judge against it:
 *
 * - A set that met a RAISED working target met it: plan 8, working 10, did
 *   10 is not short.
 * - A RAISED working target missed is short, even where the count beats the
 *   plan: plan 8, working 10, did 9 is short. The owner's rule on a failed
 *   set: "if you can't do it, it's failed."
 * - A set that met a LOWERED working target is COMPLETED. The owner's ruling,
 *   2026-09-25: "It's completed even if the target is lowered, just note the
 *   discrepancy." Plan 10, working 6, did 6 is not short. This is settled,
 *   not provisional.
 *
 * The discrepancy between the working target and the plan is NOT judged
 * here. Noting it is a recording job, not a verdict. From database v20 the
 * set row stores the working target this function is handed
 * (`workingReps`, `workingDurationS`) beside the plan's figure and the
 * actual one, and from export 1.23 both documents publish it
 * (`workingReps`, `workingDuration_s`), so a set met at a lowered target
 * reads as planned 10, working 6, did 6, not failed (#157, field-45 set 8).
 * Two sentences here are deleted rather than reworded: one said the row does
 * NOT store the working target, which v20 made false, and one said the
 * export does not publish it, which 1.23 made false. #157 recovered the working count of a guided set
 * only from where `Last rep` falls in its cue track.
 *
 * ## Judged only where the count is trustworthy
 *
 * Timed sets against the recorded seconds, and rep sets against the count a
 * person or the guide stated -- never against a possibly-miscounted sensor
 * total. A straight-reps set counted by the sensor and not corrected reaches
 * [atWrite] with no stated count, and is short only if the lifter taps the
 * failed tile. `RecordState.setTargetMet` states the same refusal on the
 * control side.
 *
 * ## What this cannot check
 *
 * Which figures `:app` passes. That the set write hands in the working
 * target and not the plan's is compile- and lint-gated only.
 */
object SetShortfallPolicy {
    /**
     * Whether recorded seconds fell short of the working hold target.
     *
     * [TimedSetEndPolicy.fellShort], whose close-enough fraction keeps a
     * scheduler that lost a tick from turning a completed hold into a failed
     * one. Absent figures are not shortfalls.
     */
    fun secondsShort(recordedS: Int?, workingDurationS: Int?): Boolean =
        TimedSetEndPolicy.fellShort(recordedS, workingDurationS)

    /**
     * Whether a stated rep count fell short of the working rep target. Strict:
     * doing exactly the target is not short. Absent figures are not
     * shortfalls -- a set with no target cannot fall short of one, and a set
     * with no stated count has nothing trustworthy to judge.
     */
    fun repsShort(statedReps: Int?, workingReps: Int?): Boolean =
        statedReps != null && workingReps != null && statedReps < workingReps

    /**
     * The derived shortfall at the set write (`stoppedEarly`).
     *
     * [timed] is asked first, as `SetEndKind.of` asks it: a hold is measured
     * on its clock whatever kind of movement it is. A timed set with no hold
     * target falls through to the rep rule, as the `when` this replaces did.
     *
     * @param timed the set is measured on the clock.
     * @param recordedS the seconds the set write records.
     * @param workingDurationS the hold target the set ran against, or null.
     * @param statedReps the count a person or the guide gave, or null on a
     *   sensor-counted set the lifter did not correct.
     * @param workingReps the rep target the set ran against, or null.
     */
    fun atWrite(
        timed: Boolean,
        recordedS: Int?,
        workingDurationS: Int?,
        statedReps: Int?,
        workingReps: Int?,
    ): Boolean = when {
        timed && workingDurationS != null -> secondsShort(recordedS, workingDurationS)
        else -> repsShort(statedReps, workingReps)
    }
}
