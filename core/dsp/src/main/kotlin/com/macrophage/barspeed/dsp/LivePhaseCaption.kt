package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase

/**
 * The word the in-set tempo ring draws over [LiveSetState.phase], or null
 * where it draws none.
 *
 * Lifted out of `RecordScreen`, where it was a private `phaseLabel(phase)`
 * that no test reached, so the words can be pinned against the two things
 * that decide whether they are true: which way this lift's drive goes, and
 * whether the tracker has withdrawn the label (#95).
 *
 * The tracker names a MOVEMENT for what the lifter is doing -- the drive is
 * [Phase.CONCENTRIC] whichever way it moves -- and a PAUSE for where the bar
 * is, [Phase.TOP_PAUSE] at the positive end of the stroke (#134). So:
 *
 * - A VERTICAL lift whose drive goes UP reads as it always has: "Lowering",
 *   "Bottom pause", "Driving up", and "Lockout" at the top, where the drive
 *   ends.
 * - A VERTICAL lift whose drive goes DOWN -- a pushdown, a pulldown, a leg
 *   curl -- reads "Driving down" and "Raising", and its pauses by position:
 *   the top is where the drive STARTS, so it is not called a lockout.
 * - A HORIZONTAL lift has no up. The tracker orients it so the drive is
 *   positive; its strokes read "Driving" and "Returning" and either pause
 *   "Pause".
 *
 * The plane and drive read are the LIFTER'S ([LiftDirection.plane],
 * [LiftDirection.concentricUp]), not the sensor's: a row measured off the
 * stack is still a horizontal lift to the person doing it.
 *
 * A withdrawn label draws no caption. [Phase.IDLE] is also the state before an
 * eccentric-first lift's first stroke, which keeps "Ready"; mid-set, with the
 * label withdrawn, "Ready" would say the set had not started.
 */
object LivePhaseCaption {
    /**
     * The caption for [phase] on a lift moving as [direction], with
     * [labelWithdrawn] the tracker's [LiveSetState.phaseLabelWithdrawn].
     */
    fun of(phase: Phase, direction: LiftDirection, labelWithdrawn: Boolean): String? {
        if (labelWithdrawn) return null
        val horizontal = direction.plane == MovementPlane.HORIZONTAL
        val up = direction.concentricUp
        return when (phase) {
            Phase.IDLE -> "Ready"
            Phase.ECCENTRIC -> when {
                horizontal -> "Returning"
                up -> "Lowering"
                else -> "Raising"
            }
            Phase.CONCENTRIC -> when {
                horizontal -> "Driving"
                up -> "Driving up"
                else -> "Driving down"
            }
            Phase.BOTTOM_PAUSE -> if (horizontal) "Pause" else "Bottom pause"
            Phase.TOP_PAUSE -> when {
                horizontal -> "Pause"
                up -> "Lockout"
                else -> "Top pause"
            }
        }
    }
}
