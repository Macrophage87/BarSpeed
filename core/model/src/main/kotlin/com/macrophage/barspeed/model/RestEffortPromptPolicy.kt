package com.macrophage.barspeed.model

/**
 * The effort question the REST screen puts to a set that was never asked one
 * when it ended, and how that grid is drawn.
 *
 * Null from [RestEffortPromptPolicy.prompt] is a set that owes nothing, and it
 * is the ordinary answer: on a rep set the effort grid IS the end-set control,
 * so the question was already asked and answered at the moment the set ended.
 *
 * [failedTile] and [carriesFailed] are two facts about ONE verdict and they are
 * never both set. A grid that offered the failure tile while already carrying a
 * failure would let the lifter store the verdict twice, and a grid that
 * withheld the tile while carrying nothing would leave a set no way to say it
 * failed. `RestEffortPromptDifferentialTest` asserts the pair rather than each
 * field alone for that reason.
 */
data class RestEffortPrompt(
    /**
     * Draw the grid's own "failed the set" tile.
     *
     * False where a failure is ALREADY on the row, by either route. Where the
     * lifter tapped it, the tile would re-store what stands; where the app
     * derived it from the count, the tile would store a TAPPED failure on the
     * one path a duration correction is supposed to be able to clear -- which
     * is `SetEndControlPolicy`'s rule for the in-set grid, applied to the same
     * question asked later.
     */
    val failedTile: Boolean,
    /**
     * The value every tile's tap must carry as `failed`, the failure tile's own
     * included.
     *
     * True on a set the lifter ENDED by tapping the failure control, and it is
     * the whole reason this policy returns a pair instead of a boolean.
     * `SetRatingTracker.rate` assigns `tappedFailed = failed` from its
     * argument -- it does not OR it with what stands -- so a rest-screen tap
     * that passed `failed = false` on a hold the lifter broke early would
     * clear the lifter's own verdict and publish `failedByLifter` false. The
     * headroom rungs are exactly where that happens: a lifter who broke a
     * plank at 40 s of 60 and then says they had 15 s in them is answering how
     * it felt, not withdrawing the break.
     *
     * Read from source, never observed: no test in this repository can execute
     * `SetRatingTracker`, Room or a composable, so what is pinned here is the
     * value the screen must pass and not what the database did with it.
     */
    val carriesFailed: Boolean,
)

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
 * true))`. Neither path draws a grid, so every hold has been stored with `rpe`
 * null and the rest screen has simply begun. Field session 38's two dead hangs
 * export `rpe` null.
 *
 * That is the one dimension a hold PROGRESSES in. `EffortScale.askFor` answers
 * `EffortAsk.TIME` for a hold and the timed headroom rungs are worded in
 * seconds (#244); `NextSetNudgePolicy` offers more seconds off a headroom rung.
 * With no ask, none of it is ever reached on a hold.
 *
 * ## Why it is keyed on the RATING and not on the plan
 *
 * A timed set owes the question when it carries no rating, whatever ended it
 * and whether or not a plan declared it. Two consequences worth stating,
 * because both were considered and neither is an oversight:
 *
 *  - An AD-HOC hold -- no plan, no target, ended by the lifter -- arrives with
 *    the same three facts as a planned hold that ran to its clock, and owes the
 *    same question. This function takes no plan input, so there is nothing for
 *    an ad-hoc set to fall through.
 *  - A timed set that DID get the in-set grid, which
 *    `SetEndControlPolicy.controls` draws for a hold whose completion the app
 *    cannot judge, arrives already rated and owes nothing. The rating being
 *    present is what withholds the second ask; no separate branch counts how
 *    the set ended.
 *
 * ## What is deliberately NOT widened
 *
 * A REP set that reached rest unrated -- END SET EARLY, or a guided set the
 * lifter left during its lead-in -- owes nothing here. Such a set already has a
 * route: its row reads `EffortCorrectionPolicy.NOT_RATED` and the Correct
 * button's popup opens the same grid. Whether a rep set should be ASKED rather
 * than merely offered the route is a real question and it is #283's neighbour,
 * not #283: the owner's report is about holds, and widening the prompt to every
 * unrated rep set would put a grid in front of a lifter who had just chosen to
 * leave one unrated. Raised, not folded in.
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
     *   lifter never said so. Kept apart from [tappedFailed] because the two
     *   withhold the failure tile for different reasons and only one of them
     *   has to be carried back through the write.
     */
    // A SEAM ARTEFACT, and it goes with the seam. [tappedFailed] and
    // [derivedFailed] are genuinely unread at this commit, because every branch
    // below answers null; they are in the signature now so that the fix is a
    // change to this body and not to the call site as well, and so that the red
    // differential can be written against the real shape. The suppression is
    // deleted in the same commit that reads them. It is stated here rather than
    // worked around by giving the linter a dead branch to look at.
    @Suppress("UnusedParameter")
    fun prompt(timed: Boolean, rpe: Int?, tappedFailed: Boolean, derivedFailed: Boolean): RestEffortPrompt? = when {
        // A set already carrying a rating has answered this question. First,
        // so that a timed set which DID reach the in-set grid is not asked
        // twice, and so that the prompt closes itself the moment the tap
        // lands rather than needing the screen to remember it did.
        rpe != null -> null
        // SEAM ONLY AT THIS COMMIT. Every branch below answers null, which is
        // exactly what the app does today: nothing on the rest screen asks a
        // finished set how it felt, and `EffortCorrectionPolicy.lineText` was
        // landed the same way for the same reason. The differential is the
        // commit after the reds, and it lands HERE -- in this `when` -- so the
        // screen's wiring is in place and behaviour-preserving until it does.
        timed -> null
        // A rep set was rated at the moment it ended. Written out rather than
        // folded into the line above, because this branch is the one that
        // stays null when the differential lands.
        else -> null
    }
}
