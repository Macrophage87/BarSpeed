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
 * WHOSE UP. [LiftDirection.concentricUp] is the DECLARED drive, not a
 * reading of the lifter: `ExerciseDef.concentricUp` is never inferred from
 * the exercise id and stays true until a plan says otherwise. Up and down
 * are said only where the tracker's frame is the declaration's:
 *
 * - On the stack and NOT inverted ([LiftDirection.sensorOnStack] true,
 *   [LiftDirection.sensorInverted] false) the tracker reads the STACK's
 *   travel. That is every stack-table pushdown, pulldown or leg curl whose
 *   plan left `concentric` at the default, where the stack rises as the
 *   lifter pulls the handle down. No word there says up or down: the strokes
 *   read "Driving" and "Returning" and either pause "Pause", as on a
 *   horizontal lift. A #280 rebuild ON the stack changes nothing here. A
 *   rebuild OFF the stack from this declaration is the first "not covered"
 *   case below: the screen still passes the declaration, so the words stay
 *   neutral, but on a drive-down lift left at the default the rebuilt
 *   tracker's [Phase.CONCENTRIC] is the lifter's return, and the ring reads
 *   it "Driving". On a drive-up lift rebuilt off the stack the handle rises
 *   with the drive, and the words stay true.
 * - Inverted, the tracker reads the stack's travel turned round, so a drive
 *   declared down on the stack (rule 3 of `SetGeometryPolicy.stackInversion`)
 *   reads "Driving down". That is the lifter's stroke only where the unit
 *   does ride the stack. With the unit on the rope under a stack-table mount
 *   (#334's capture), the inversion is applied to a frame that needed none,
 *   and the strokes read reversed: the "declaration is wrong" case below.
 *
 * Not covered, and not this caption's to fix: where the declaration is
 * wrong about a frame it does read -- a drive-down lift measured OFF the
 * stack with `concentric` left at the default, or an assist stack whose
 * counterweight falls as the lifter rises -- the tracker names the strokes
 * the other way round, and the caption names the stroke the tracker names.
 * Stack-mounted drive-up lifts (a cable curl, a leg extension) lose their
 * "up" with this rule; "Driving" stays true of them.
 *
 * A withdrawn label draws no caption. [Phase.IDLE] is also the state before an
 * eccentric-first lift's first stroke, which keeps "Ready"; mid-set, with the
 * label withdrawn, "Ready" would say the set had not started. A withheld live
 * count (#280) draws none either: the screen publishes an empty
 * [LiveSetState] for the rest of the set, and its IDLE is not the set's
 * opening one.
 */
object LivePhaseCaption {
    /**
     * The caption for [phase] on a lift moving as [direction], with
     * [labelWithdrawn] the tracker's [LiveSetState.phaseLabelWithdrawn] and
     * [countWithheld] the screen's `liveCountWithheld`: true once #280's
     * fallback has given up the live count for the rest of the set, which
     * drops the tracker and leaves [phase] at the [Phase.IDLE] of an empty
     * [LiveSetState].
     */
    fun of(phase: Phase, direction: LiftDirection, labelWithdrawn: Boolean, countWithheld: Boolean): String? {
        if (labelWithdrawn || countWithheld) return null
        val stackFrame = direction.sensorOnStack && !direction.sensorInverted
        val neutral = direction.plane == MovementPlane.HORIZONTAL || stackFrame
        val up = direction.concentricUp
        return when (phase) {
            Phase.IDLE -> "Ready"
            Phase.ECCENTRIC -> when {
                neutral -> "Returning"
                up -> "Lowering"
                else -> "Raising"
            }
            Phase.CONCENTRIC -> when {
                neutral -> "Driving"
                up -> "Driving up"
                else -> "Driving down"
            }
            Phase.BOTTOM_PAUSE -> if (neutral) "Pause" else "Bottom pause"
            Phase.TOP_PAUSE -> when {
                neutral -> "Pause"
                up -> "Lockout"
                else -> "Top pause"
            }
        }
    }
}
