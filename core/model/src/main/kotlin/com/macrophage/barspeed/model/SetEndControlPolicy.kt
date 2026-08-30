package com.macrophage.barspeed.model

/** A control the set-end block can draw while a set is running and no write is outstanding. */
enum class SetEndControl {
    /** The effort tiles. Tapping one rates the set and ends it in a single action. */
    EFFORT_GRID,

    /**
     * The grid's own "failed the set" tile, which stores the lifter's verdict as
     * a TAPPED failure.
     *
     * Named apart from [EFFORT_GRID] because it is not part of rating the
     * effort: it stores no RPE, and the fact it sets is the one a later rep
     * correction can never clear.
     */
    FAILED_TILE,

    /** End the set now with no rating of any kind attached. Drawn as END SET EARLY. */
    END_UNRATED,

    /**
     * End the set now as a TAPPED failure, drawn on its own where the grid is
     * withheld.
     *
     * The difference from [FAILED_TILE] is what DRAWS. That one is a tile
     * inside the grid and puts nothing on the screen by itself; this one is a
     * control of its own, and it is the only exit a guided or timed set that
     * is UNDER WAY has before it is complete (#186). During the lead-in the
     * exit is [END_UNRATED] instead: a set whose clock or cadence has not
     * begun has not failed at anything.
     */
    END_FAILED,
}

/**
 * How the app knows -- or does not know -- that a set has delivered what it
 * was asked for.
 *
 * The distinction is not about the movement. It is about whether there is an
 * authoritative completion signal at all:
 *
 *  - [TEMPO_GUIDED]: the app is calling the tempo and counting the reps, so
 *    the guide finishing IS the set being done.
 *  - [TIMED]: the clock says so, and since #168 the clock also ends the set.
 *  - [STRAIGHT_REPS]: the LIFTER decides. Nothing in the app knows the set is
 *    over until they say so, and tapping an effort tile is how they say it.
 *  - [EXPLOSIVE]: sensor-counted single drives; the owner's ruling is that
 *    today's behaviour is fine here.
 *
 * [gatesOnCompletion] is that fact and nothing else. Withholding the grid on a
 * kind that answers false would leave the lifter with no way to end the set
 * except by logging a failure they did not have.
 */
enum class SetEndKind(val gatesOnCompletion: Boolean) {
    TEMPO_GUIDED(gatesOnCompletion = true),
    TIMED(gatesOnCompletion = true),
    STRAIGHT_REPS(gatesOnCompletion = false),
    EXPLOSIVE(gatesOnCompletion = false),
    ;

    companion object {
        /**
         * Which kind a set in progress is, from the three facts `:app` holds
         * about it.
         *
         * The order is load-bearing. A hold is [TIMED] whatever kind of
         * exercise it is, because it is measured on the clock; an explosive
         * lift never carries a cadence, so it is tested before [TEMPO_GUIDED]
         * rather than after; and [STRAIGHT_REPS] is what is left, which is the
         * case with no completion signal.
         */
        fun of(timed: Boolean, explosive: Boolean, guided: Boolean): SetEndKind = when {
            timed -> TIMED
            explosive -> EXPLOSIVE
            guided -> TEMPO_GUIDED
            else -> STRAIGHT_REPS
        }
    }
}

/**
 * Which controls end the set in front of the lifter.
 *
 * Here rather than in `:app` for the reason [RestControlPolicy] is here: `:app`
 * has no test that can reach a composable, so a gate written inside one is a
 * gate nothing can measure. This one shipped wrong and stayed wrong for every
 * session the app has recorded.
 *
 * ## When the question is asked
 *
 * The owner: *"it should only be shown when all the reps are finished or the
 * hold is finished. Earlier than that the only option available should be
 * fail."* So on a set the app is counting -- a cadenced rep set, a hold on its
 * clock -- the grid is withheld until the prescription is delivered, and once
 * the set is under way the one control before that is
 * [SetEndControl.END_FAILED]; during the lead-in it is
 * [SetEndControl.END_UNRATED]. Mid-set the question is not answerable: the
 * set is not over, so how it went is not a fact yet.
 *
 * **This is not #137 coming back, and the difference is what makes it safe.**
 * #137 gated the grid on the TARGET, which left the RPE record holding only
 * the sets that hit their target -- every failed set absent by construction,
 * and the set the lifter stopped is where the fatigue information is, so the
 * record read easier the harder the session got. This gates on COMPLETION, and
 * a set ended via Fail is still rateable: #140's correction grid sits on the
 * rest screen, where a Fail-ended set's row reads EFFORT -- FAILED and carries
 * a Change action that opens the same grid with the failure tile pre-lit. The
 * rating moves from the moment of ending to the rest period rather than
 * disappearing. (An auto-ended hold, which carries no verdict at all, is the
 * row that reads EFFORT -- NOT RATED with a Rate action instead; both routes
 * reach the same grid. `EffortCorrectionPolicy.lineText` decides which, and a
 * tapped failure is not an absence.) Two things must stay true or the defect
 * IS back -- the rest-screen Rate path has to work on a Fail-ended set, which
 * no test in this repository can check and which is verified on a device
 * instead; and the gating must not spread to a kind with no completion
 * signal, which is what [SetEndKind.gatesOnCompletion] is for.
 *
 * ## Which way out, once the grid is drawn
 *
 * What the target decides is the way OUT beside the grid, never whether rating
 * is possible:
 *
 *  - A set that met its target may tap the failure itself. Nothing derived says
 *    that set fell short, so the lifter's word is the only thing that can.
 *  - A set that came up short gets END SET EARLY instead, ending it with no
 *    rating at all. The shortfall is derived at the write either way, so the
 *    tile would add one thing only: a TAPPED failure, which no later rep
 *    correction can clear. A miscounted rep total lands on exactly this path.
 *
 * Neither case nags. Every case leaves one tap that ends the set storing no
 * RPE, because a lifter walking away mid-set has to be able to leave.
 *
 * WHAT THAT TAP STORES IS NOT THE SAME EVERYWHERE, and the summary above is
 * the sentence a reader checks the design against, so it is qualified here
 * rather than left to be discovered. On a hand-counted or explosive set the
 * tap stores NOTHING and absence stays a state the record can hold: an
 * unrated set is not a zero. On a tempo-guided or timed set that is true only
 * during the lead-in. Once such a set is running, [SetEndControl.END_UNRATED]
 * is unreachable in practice, because `RecordState` answers `complete = true`
 * and `complete = null` only in states where [targetMet] is also true -- so
 * the three states a running guided or timed set can be in are the grid with
 * the failure tile, the grid with the failure tile, and the standalone
 * failure control. The exit from an unfinished one therefore always writes a
 * verdict. That is the owner's instruction applied honestly rather than an
 * oversight, and the repair is the rest screen: re-rating overwrites the
 * tapped verdict.
 *
 * ## The cost, named rather than discovered
 *
 * On a guided or timed set that is UNDER WAY, every early exit is now
 * recorded as a failure: a rack taken, a cramp, a dropped phone. Those sets
 * are not failures and the record will call them one. The lead-in is exempt
 * -- a set whose clock or cadence had not begun has not failed at anything --
 * and leaving during it stores no tapped verdict, though the write still
 * DERIVES a shortfall from the near-zero count, so the row reads failed with
 * `tappedFailed` false. Whether such a set should be recorded at all is the
 * owner's call and is carried as a field question rather than answered here.
 *
 * The running case is the owner's instruction -- "the only option available
 * should be fail" -- and the alternative, keeping END SET EARLY beside it, is
 * what he ruled out. A TAPPED failure is also the one a later rep correction
 * cannot clear; re-rating the set on the rest screen does overwrite it, which
 * is the repair that fits.
 */
object SetEndControlPolicy {
    /**
     * The controls to draw for a set of [kind] whose target has ([targetMet])
     * or has not been delivered, and which the app does ([complete] true), does
     * not ([complete] false) or CANNOT ([complete] null) know has finished.
     *
     * [complete] is nullable rather than defaulting to false because the two
     * are different states and only one of them may withhold a control. False
     * is "the set is not done yet"; null is "this set has no completion signal
     * at all" -- an ad-hoc hold started with no target, or a guided set the
     * plan gave no rep count, neither of which ever finishes on its own.
     * Rendering that absence as false would leave those sets with no exit but
     * a failure they did not have.
     *
     * [started] is whether the set's own clock or cadence has begun. False is
     * the lead-in -- the seconds before a hold's clock starts, and the
     * identical seconds before a guided cadence calls its first stroke. It is
     * separate from [complete] because "has not finished" and "has not begun"
     * are different states and the honest exit from them differs: an
     * unfinished set that the lifter stops IS a failure, and a set that never
     * started is not.
     *
     * A set rather than a boolean per control, so a control added later has to
     * be placed in every case rather than defaulting into all of them.
     */
    fun controls(kind: SetEndKind, targetMet: Boolean, complete: Boolean?, started: Boolean): Set<SetEndControl> =
        when {
            // First, because a set that has not begun cannot have delivered
            // its prescription and cannot have failed to. Ahead of the kind
            // test on purpose: the rule is about the SET, not about which
            // completion signal it has, so nothing here has to be kept in
            // step with [SetEndKind].
            !started -> setOf(SetEndControl.END_UNRATED)
            // `complete == false` and not `complete != true`: null is a set
            // whose completion the app cannot judge, and it falls through to
            // the ungated rules below rather than into the gate. Written as
            // an equality against false for exactly that reason -- the
            // negation reads the same at a glance and gates every ad-hoc hold
            // in the app.
            kind.gatesOnCompletion && complete == false -> setOf(SetEndControl.END_FAILED)
            targetMet -> setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE)
            else -> setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED)
        }
}
