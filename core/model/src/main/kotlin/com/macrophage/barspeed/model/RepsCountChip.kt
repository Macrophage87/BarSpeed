package com.macrophage.barspeed.model

/**
 * The history card's count chip: whose count the set's rep figure is (#325).
 *
 * Pure, and here rather than in `SessionDetailScreen`, because no test on the
 * CI path reaches a composable. The input is the [RepsSource] the export
 * publishes for the same row -- [RepsSourcePolicy] derives it -- so the card
 * and the export's `repsSource` cannot name two different counters for one
 * set.
 */
object RepsCountChip {
    /**
     * The chip's text, or null where the card draws no count chip.
     *
     * This body is the screen's current wording, lifted unchanged so the
     * decision can be tested: the screen drew `MANUAL COUNT` whenever the row's
     * `repsManual` column was true, which on a set counted in reps is the
     * three sources that column stands behind. The screen does not read this
     * yet.
     */
    fun label(source: RepsSource?): String? = when (source) {
        RepsSource.MANUAL, RepsSource.CORRECTED, RepsSource.METRONOME -> "MANUAL COUNT"
        RepsSource.SENSOR, RepsSource.ANALYSIS, null -> null
    }
}
