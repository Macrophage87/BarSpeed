package com.macrophage.barspeed.model

/**
 * Whose count a set's published `reps` figure is.
 *
 * The WIRE WORD is stored and compared, never the ordinal, so reordering this
 * enum cannot reinterpret an export. [SessionExport.VALID_REPS_SOURCES] is the
 * published vocabulary and is derived from these entries rather than written
 * out a second time.
 */
enum class RepsSource(val wireName: String) {
    /**
     * The sensor's live count, as the lifter heard it, with no correction
     * afterwards.
     */
    SENSOR("sensor"),

    /** The lifter's own tally, tapped rep by rep. */
    MANUAL("manual"),

    /**
     * The cadence guide's count, which the metronome made on its own schedule
     * whether or not the lifter followed it.
     */
    METRONOME("metronome"),

    /**
     * The sensor counted, and the lifter then disagreed -- in the set with
     * `+1 REP`, or afterwards from the rest screen.
     *
     * `liveReps` is published beside it and holds what the sensor said, so the
     * correction and the figure it corrected are both readable.
     */
    CORRECTED("corrected"),

    /**
     * The batch segmenter's count, taken at the end of the set because no live
     * counter had run.
     *
     * Every sensor-counted set recorded before this key existed is in this
     * state: the app spoke a live count on explosive lifts and stored the
     * segmenter's instead, and no column distinguishes the two on such a row.
     */
    ANALYSIS("analysis"),
}

/**
 * Which counter's figure a stored row's rep count is, derived at export.
 *
 * ## Why this is derived and what the derivation needs
 *
 * The word itself is NOT stored. It does not need to be, once the sensor's own
 * live count is: given `liveReps`, `repsManual` and the set's shape, every one
 * of the five words above follows, including for rows written before any of
 * this existed.
 *
 * What could NOT be derived from what the row held before is the difference
 * between a lifter's tally and a correction of a sensor count, because
 * `repsManual` is true in both cases -- one column with two jobs, which its own
 * KDoc has always said: *"True when actualReps was entered or corrected by the
 * lifter"*. `liveReps` separates them: a row with a live count and
 * `repsManual` true is a correction, and a row with no live count and
 * `repsManual` true is a tally.
 *
 * ## The two collapses, stated rather than hidden
 *
 * A corrected MANUAL set reads `manual` and a corrected METRONOME set reads
 * `metronome`. Both are honest about whose figure the count is -- a corrected
 * tally is still the lifter's statement -- and neither can be told from an
 * uncorrected one, because nothing on the row records that the rest-screen
 * control was used. A row written before the live count existed reads
 * `analysis` whenever `repsManual` is false, which is what such a row's
 * `actualReps` was: `set.manualReps ?: set.analysis.reps.size`.
 */
object RepsSourcePolicy {
    /**
     * The word, or null where nothing counted reps.
     *
     * [timed] is a set measured in seconds, where the published `reps` figure
     * is whatever the segmenter made of one long movement and no counter
     * stands behind it. Null is therefore not a sixth word and not an
     * omission: it is the absence of a counter, and it is the ONLY thing null
     * means.
     *
     * [hasTempo] separates the guide's count from the lifter's. It is read off
     * the row's frozen `tempo` string, which is what the set was prescribed,
     * rather than re-derived from a plan that can have been edited since.
     */
    fun published(liveReps: Int?, repsManual: Boolean, timed: Boolean, hasTempo: Boolean): RepsSource? = when {
        timed -> null
        liveReps != null && repsManual -> RepsSource.CORRECTED
        liveReps != null -> RepsSource.SENSOR
        !repsManual -> RepsSource.ANALYSIS
        hasTempo -> RepsSource.METRONOME
        else -> RepsSource.MANUAL
    }

    /** The same answer as the published word, for a caller that wants the string. */
    fun publishedWord(liveReps: Int?, repsManual: Boolean, timed: Boolean, hasTempo: Boolean): String? =
        published(liveReps, repsManual, timed, hasTempo)?.wireName
}
