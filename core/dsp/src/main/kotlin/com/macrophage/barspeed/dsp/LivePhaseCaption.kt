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
 */
object LivePhaseCaption {
    /**
     * The caption for [phase] on a lift moving as [direction], with
     * [labelWithdrawn] the tracker's [LiveSetState.phaseLabelWithdrawn].
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun of(phase: Phase, direction: LiftDirection, labelWithdrawn: Boolean): String? = when (phase) {
        Phase.IDLE -> "Ready"
        Phase.ECCENTRIC -> "Lowering"
        Phase.BOTTOM_PAUSE -> "Bottom pause"
        Phase.CONCENTRIC -> "Driving up"
        Phase.TOP_PAUSE -> "Lockout"
    }
}
