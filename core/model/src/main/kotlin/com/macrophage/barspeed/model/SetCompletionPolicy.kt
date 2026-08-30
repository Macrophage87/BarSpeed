package com.macrophage.barspeed.model

/**
 * Whether the app KNOWS the set in progress has delivered its prescription --
 * and null where it cannot know at all (#186).
 *
 * This is the predicate [SetEndControlPolicy] gates on. It lived in
 * `RecordState` as a `when` over six fields of a Compose-screen view model,
 * which is a module with one test file over one pure function: the gate was
 * pinned exhaustively and the thing feeding it was not, so deleting the
 * `targetReps == null` guard or swapping [TimedSetEndPolicy.fellShort] for a
 * plain `>=` reddened nothing. Lifted here, every branch is a literal in a
 * test that runs on every push.
 *
 * ## Three answers, not two
 *
 * True is "the app can see this set finished". False is "not yet". **Null is
 * "nothing here can ever say it finished"** -- an ad-hoc hold started with no
 * target, or a guided set the plan gave no rep count, neither of which
 * `GuidedCadenceRunner` ever calls `onFinished` for. Rendering that absence as
 * false would withhold the effort grid for the whole set and leave a tapped
 * failure as the only way out of a set that went fine.
 *
 * ## Why [timed] is asked first
 *
 * The same order [SetEndKind.of] uses, and for the same reason: a hold is
 * measured on the clock whatever kind of movement it is, so a guided hold
 * reads its clock and not its guide. Two orderings of the same three facts is
 * how the control the lifter is offered ends up disagreeing with the kind the
 * set was classified as.
 *
 * ## A different question from `setTargetMet`
 *
 * That one answers `true` wherever there is nothing to fall short of, because
 * its job is to decide which way OUT sits beside the grid and a set with no
 * target must not be pushed down the failure path. This one has to keep "not
 * finished yet" and "unjudgeable" apart, which is the whole reason it is
 * nullable and that one is not.
 */
object SetCompletionPolicy {
    /**
     * @param timed the set is measured on the clock -- a hold or a carry.
     * @param timedTargetS seconds prescribed, or null for a hold with no target.
     * @param elapsedS seconds the set's own clock has counted.
     * @param guided the app is calling the cadence and counting the reps.
     * @param targetReps reps asked of the set, or null where none were.
     * @param guidedFinished the guide called the prescription all the way through.
     */
    fun complete(
        timed: Boolean,
        timedTargetS: Int?,
        elapsedS: Int,
        guided: Boolean,
        targetReps: Int?,
        guidedFinished: Boolean,
    ): Boolean? = when {
        // The SAME pair #168 ends the set on, asked of the same instant:
        // `remainingS` then `endsNow`. Deliberately not
        // [TimedSetEndPolicy.fellShort], whose 90% tolerance answers "was
        // this recorded hold short" -- the right question at the write, where
        // a scheduler losing a tick must not turn a completed hold into a
        // failed one, and the wrong one here, where it opened the grid two
        // seconds before a 20 s plank ended. Null where `remainingS` names no
        // instant: no prescription, or one of zero or less.
        timed -> TimedSetEndPolicy.remainingS(elapsedS, timedTargetS)?.let { TimedSetEndPolicy.endsNow(it) }
        // The guide finishing IS the set being done. Its rep count lands one
        // stroke early, before the closing cue is spoken, so `guidedFinished`
        // and not a rep comparison.
        guided -> if (targetReps == null) null else guidedFinished
        else -> null
    }
}
