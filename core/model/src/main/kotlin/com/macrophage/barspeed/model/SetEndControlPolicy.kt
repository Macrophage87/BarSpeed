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
 * gate nothing can measure. This one has already shipped wrong once.
 *
 * Characterized, not yet changed. This is what `EndSetControl` in RecordScreen
 * has always done -- the effort grid once the set met its target, END SET EARLY
 * before that -- lifted out unaltered so the change to it can be seen as a
 * change to one expression rather than read out of a 1,700-line screen.
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
        setOf(SetEndControl.END_UNRATED)
    }
}
