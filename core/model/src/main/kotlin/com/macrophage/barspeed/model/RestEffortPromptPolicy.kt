package com.macrophage.barspeed.model

/**
 * The effort question the REST screen puts to the one set that ran what it was
 * asked to run and was never asked how it felt.
 *
 * Fieldless on purpose: its PRESENCE is the whole answer, and null from
 * [RestEffortPromptPolicy.prompt] is a set that owes nothing. It carried two
 * booleans until the owner's rule arrived -- `failedTile`, whether the grid drew
 * its own "failed the set" tile, and `carriesFailed`, what every tap had to pass
 * back as `failed` so a rest-screen rung could not withdraw a failure the lifter
 * had already tapped. Neither can take more than one value now, so both are
 * deleted rather than kept as constants dressed up as decisions: the question is
 * drawn ONLY for a set with no failure standing, so its grid has no failure tile
 * and every tap carries `failed = false`.
 *
 * Those two invariants now live at the one call site, `RestEffortPromptSection`,
 * which filters the failure tile out of the ladder and passes each remaining
 * tile's own `failed` -- false for all seven of them. That is a `:app` rule and
 * `:app` has no reachable test seam for it, so it is compile- and lint-gated
 * only, not test-gated. Nothing here can measure it.
 */
data object RestEffortPrompt

/**
 * Which finished sets owe an effort question on the rest screen (#283).
 *
 * ## The gap this closes
 *
 * The owner: *"Another thing is holds. Holds don't display the 'how hard was
 * the set' part, just continuing on."*
 *
 * On a rep set `EndSetRpeGrid` is the end-set control -- its caption is "Tap
 * how that set felt to end it" -- so rating and ending are one action and no
 * set of that kind can reach the rest screen unasked. A hold has no such
 * moment. It ends when its clock reaches the target, which `RecordViewModel`
 * does by calling `endSet()` with no rating at all, or when the lifter taps the
 * standalone failure control, which calls `endSet(SetRating(null, failed =
 * true))`. Neither path draws a grid. Field session 38's two dead hangs export
 * `rpe` null.
 *
 * That is the one dimension a hold PROGRESSES in. `EffortScale.askFor` answers
 * `EffortAsk.TIME` for a hold and the timed headroom rungs are worded in
 * seconds (#244); `NextSetNudgePolicy` offers more seconds off a headroom rung.
 * With no ask, none of it is ever reached on a hold that finished.
 *
 * ## The owner's rule, which decides the other half
 *
 * *"Don't ask for an RPE on failed sets, if you can't do it, it's failed."*
 * (#283, 2026-09-12, overriding this policy's first shape.)
 *
 * So of the two ways a hold ends, only ONE owes the question. A hold that ran
 * its clock to the target is asked. A hold the lifter ended with the failure
 * control is asked nothing -- not the ladder with the failure tile withheld,
 * nothing; the set already said what it had to say, and the page a failed set
 * gets is the limiter and reason page, which is not this policy's question. A
 * hold the app judged short of its target is asked nothing either, for the same
 * reason read the other way round: it did not deliver the clock it was given.
 *
 * ## Why it is keyed on the RATING and on the ABSENCE OF ANY FAILURE
 *
 * A timed set owes the question when it carries no rating AND no failure of
 * either kind, whatever ended it and whether or not a plan declared it. How it
 * ended is still not read here -- only what the row carries -- and that is the
 * same argument as before, applied to three facts instead of one. Two
 * consequences worth stating, because both were considered and neither is an
 * oversight:
 *
 *  - An AD-HOC hold -- no plan, no target -- is the case the in-set grid IS
 *    drawn for, since its `complete` is null, so it arrives rated where the
 *    lifter tapped a rung and unrated with `tappedFailed` standing where they
 *    tapped the failure tile. Either way this function needs no plan input to
 *    reach the right answer, so there is nothing for an ad-hoc set to fall
 *    through.
 *  - A timed set that DID get the in-set grid owes nothing by either route out
 *    of it. The grid has exactly TWO ways out on a running timed set and only
 *    one of them stores an `rpe`: `SetEndControlPolicy.controls` returns
 *    `{EFFORT_GRID, FAILED_TILE}` whenever that grid is drawn on such a set,
 *    and END SET EARLY is NOT an exit from it -- `SetEndControl.END_UNRATED`
 *    needs `targetMet` false, which on a TIMED kind implies `complete == false`,
 *    which the branch above it already answered with the standalone failure
 *    control. `SetEndControlPolicy`'s own KDoc states it: once such a set is
 *    running, END_UNRATED is unreachable in practice. So a set that left the
 *    in-set grid unrated left by its failure tile, `tappedFailed` stands, and
 *    the rating being absent no longer means it is asked. `derivedFailed` is
 *    reached on a different path: a timed set ended during its LEAD-IN, where
 *    `!started` gives END SET EARLY, records measured seconds with `autoEnded`
 *    false, and `TimedSetEndPolicy.fellShort` derives the shortfall at the
 *    write. That set is not asked either. Read from source; no test here can
 *    execute either caller.
 *
 * ## What is deliberately NOT widened
 *
 * A REP set owes nothing here whatever it carries, and after the owner's rule
 * the two reasons have merged into one shape. A rep set that WAS rated was
 * rated at the moment it ended, because there the grid is the end-set control.
 * A rep set that reached rest unrated did so by leaving -- END SET EARLY, or a
 * guided set left during its lead-in -- and a rep set that failed, tapped or
 * derived from a short count, is exactly what the owner's rule says not to ask.
 * The rep half of that rule therefore needs no branch of its own here: every
 * rep set already answers null. The route back for a set the lifter wants to
 * rate after all is unchanged -- its row reads
 * `EffortCorrectionPolicy.NOT_RATED` and the Correct button's popup opens the
 * same grid, which the lifter opens and which asks for nothing on its own.
 *
 * Here rather than in `:app` for the reason [SetEndControlPolicy] and
 * [EffortCorrectionPolicy] are here: no test on the CI path can render a
 * composable, so a rule written inside one is a rule nothing can measure.
 */
object RestEffortPromptPolicy {
    /**
     * The question the rest screen owes the set just stored, or null where it
     * owes none.
     *
     * @param timed the set was measured on the clock -- a hold or a carry.
     * @param rpe the rating standing on the row, null where none does.
     * @param tappedFailed the lifter's own failure verdict, already stored.
     * @param derivedFailed the app judged the set short of its target and the
     *   lifter never said so. Kept as a separate input from [tappedFailed]
     *   although both now answer null, because they are two different facts
     *   about the set and collapsing them into one boolean here would make the
     *   cross-product pin unable to tell which of them a later change stopped
     *   reading.
     */
    fun prompt(timed: Boolean, rpe: Int?, tappedFailed: Boolean, derivedFailed: Boolean): RestEffortPrompt? = when {
        // A set already carrying a rating has answered this question. First,
        // so that a timed set which DID reach the in-set grid is not asked
        // twice, and so that the prompt closes itself the moment the tap
        // lands rather than needing the screen to remember it did.
        rpe != null -> null
        // The owner's rule: "if you can't do it, it's failed". A set carrying
        // a failure by either route is not asked how hard it was. Both facts
        // are tested rather than the effective OR, because the row stores
        // them separately and a change that stopped reading one of them would
        // otherwise be invisible here.
        tappedFailed || derivedFailed -> null
        // A hold or a carry that ran its clock and carries no rating. This is
        // the whole of what #283 leaves: the set delivered what it was asked
        // to deliver, nothing asked how it felt while it was ending, so the
        // question is asked afterwards or not at all.
        timed -> RestEffortPrompt
        // A rep set was rated at the moment it ended, because there the grid
        // IS the end-set control. Written out rather than folded into the line
        // above, so that widening this to an unrated rep set is a visible
        // change to this branch.
        else -> null
    }
}
