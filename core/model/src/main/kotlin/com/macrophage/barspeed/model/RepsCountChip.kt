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
     * The chip's text, or null where nothing counted reps.
     *
     * One chip per counter, named for it. The screen used to draw
     * `MANUAL COUNT` whenever the row's `repsManual` column was true, and that
     * column is also true on a guided set -- the metronome's figure is stored
     * the way a tally is -- so every set the metronome counted read in
     * History as one the lifter had tapped. The export's `repsSource` already separates the
     * three; this reads the same word (#325).
     *
     * Null only where [RepsSourcePolicy] returns null: a timed set, where no
     * counter stands behind the rep figure and the card draws none.
     */
    fun label(source: RepsSource?): String? = when (source) {
        RepsSource.SENSOR -> "SENSOR COUNT"
        RepsSource.MANUAL -> "MANUAL COUNT"
        RepsSource.METRONOME -> "METRONOME COUNT"
        RepsSource.CORRECTED -> "CORRECTED COUNT"
        RepsSource.ANALYSIS -> "ANALYSIS COUNT"
        null -> null
    }
}
