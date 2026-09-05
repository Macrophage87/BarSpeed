package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase

/**
 * The thirteen captures that carry a rep-mark track, with the geometry each
 * set declared.
 *
 * Every direction is read from that session's own `meta.json` --
 * `startsWith`, `concentric`, `plane`, `sensorOnStack`, `sensorInverted`,
 * `travelRatio` -- and the per-set provenance is tabled in [RepMarkTrackTest].
 * Shared between [LiveRepCallTest] and the corpus scoring pushed after it
 * because the two score the SAME producer over the SAME captures; that is the
 * case a shared list is right for, unlike [BatchCueCoverageTest] and
 * [CuedRepCoverageTest], which deliberately keep separate window rules because
 * they score different producers.
 */
internal object LiveRepCallCorpus {
    private val ECC = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val CON = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Session 38 set 14: drive DOWN, sensor on the stack, inverted. */
    private val PULLDOWN = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    val ALL = listOf(
        "field-backsquat-4011-6rep-s36-set01" to ECC,
        "field-rdl-3010-10rep-s36-set05" to ECC,
        "field-legpress-single-2011-8rep-s36-set07" to CON,
        "field-ohp-3010-6rep-s37-set02" to CON,
        "field-ohp-prepinflated-s37-set03" to CON,
        "field-ohp-prepinflated-s37-set04" to CON,
        "field-bench-3010-6rep-s37-set05" to ECC,
        "field-bench-3010-6rep-s37-set06" to ECC,
        "field-pullup-3010-8rep-s37-set09" to CON,
        "field-inclinepress-3010-12rep-s38-set02" to ECC,
        "field-ohp-3010-8rep-s38-set04" to CON,
        "field-ohp-3010-8rep-s38-set05" to CON,
        "field-latpulldown-1120-12rep-s38-set14" to PULLDOWN,
    )
}
