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
     * control of its own, and it is the only exit a guided or timed set has
     * before it is complete (#186).
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
 * The effort grid is offered either way, and that is issue #137. Gating it on
 * the target left the RPE record holding only the sets that hit their target,
 * so every failed set was absent from it by construction -- and the set the
 * lifter stopped is where the fatigue information is. The record read easier
 * the harder the session got.
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
 * Neither case nags. Both leave one tap that ends the set storing no RPE,
 * because a lifter walking away mid-set has to be able to leave, and because
 * absence has to stay a state the record can hold: an unrated set is not a
 * zero.
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
     * A set rather than a boolean per control, so a control added later has to
     * be placed in every case rather than defaulting into all of them.
     */
    @Suppress("UNUSED_PARAMETER")
    fun controls(kind: SetEndKind, targetMet: Boolean, complete: Boolean?): Set<SetEndControl> =
        // [kind] and [complete] are not read yet: the gate itself lands in the
        // commit whose red differentials were pushed for it. The signature
        // exists first so those differentials can be written against the final
        // one and fail on an assertion rather than on a compile error, which
        // is not evidence of anything.
        if (targetMet) {
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE)
        } else {
            setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED)
        }
}
