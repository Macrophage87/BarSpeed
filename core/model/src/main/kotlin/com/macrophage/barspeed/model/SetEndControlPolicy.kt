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
     * The controls to draw for a set whose target has ([targetMet]) or has not
     * been delivered.
     *
     * A set rather than a boolean per control, so a control added later has to
     * be placed in every case rather than defaulting into all of them.
     */
    fun controls(targetMet: Boolean): Set<SetEndControl> = if (targetMet) {
        setOf(SetEndControl.EFFORT_GRID, SetEndControl.FAILED_TILE)
    } else {
        setOf(SetEndControl.EFFORT_GRID, SetEndControl.END_UNRATED)
    }
}
