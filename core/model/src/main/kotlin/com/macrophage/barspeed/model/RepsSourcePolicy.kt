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
     * A counter counted, and the lifter then disagreed. On a sensor set: in
     * the set with `+1 REP`, or afterwards from the rest screen, and
     * `liveReps` is published beside it holding what the sensor said. On a
     * guided set (#336): the lifter recorded a count other than the one the
     * cadence guide called, and `guideReps` (export 1.26) holds the guide's
     * count, its marks the calls themselves. Either way the correction and the figure it corrected are both
     * readable.
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
 * `repsManual` is true in both cases -- one column with two jobs, which its
 * KDoc said as *"True when actualReps was entered or corrected by the
 * lifter"* until #132 rewrote it to name the third job it always had, the
 * cadence guide's count. `liveReps` separates the first two: a row with a live count and
 * `repsManual` true is a correction, and a row with no live count and
 * `repsManual` true is a tally.
 *
 * ## The three collapses, stated rather than hidden
 *
 * A corrected MANUAL set reads `manual`: a corrected tally is still the
 * lifter's statement, and nothing on the row records that the rest-screen
 * control was used. A corrected METRONOME set reads `corrected` where the
 * set's archived rep marks say what the guide called and the recorded count
 * differs from it (#336); it still reads `metronome` where no marks are
 * stored, the collapse that remains. A row written before the live count
 * existed reads `analysis` whenever `repsManual` is false, which is what such
 * a row's `actualReps` was: `set.manualReps ?: set.analysis.reps.size`.
 *
 * The third is [guideCounted]'s: a row with NO STORED GEOMETRY cannot say what
 * kind of exercise it was, so its tempo is read as the guide. That is right for
 * almost every such row and wrong for one shape -- an explosive lift carrying a
 * tempo, which no cadence paces -- and where the geometry IS stored that shape
 * reads `manual`, which is what it is.
 */
object RepsSourcePolicy {
    /**
     * The word, or null where nothing counted reps.
     *
     * [timed] is a set measured in seconds, where no counter stands behind
     * the row's rep count and [publishedReps] withholds it (1.23, #71). Null
     * is therefore not a sixth word and not an omission: it is the absence of
     * a counter, and it is the ONLY thing null means. The clause that stood
     * here said a timed set's published `reps` is whatever the segmenter made
     * of one long movement; no such figure is published any more, and it is
     * DELETED rather than reworded.
     *
     * [guideCounted] separates the guide's count from the lifter's, and it is
     * WHETHER A CADENCE RAN rather than whether a tempo was written down --
     * [guideCounted] the function is what a caller holding a row derives it
     * with.
     */
    fun published(liveReps: Int?, repsManual: Boolean, timed: Boolean, guideCounted: Boolean): RepsSource? = when {
        timed -> null
        liveReps != null && repsManual -> RepsSource.CORRECTED
        liveReps != null -> RepsSource.SENSOR
        !repsManual -> RepsSource.ANALYSIS
        guideCounted -> RepsSource.METRONOME
        else -> RepsSource.MANUAL
    }

    /**
     * The `reps` figure a set publishes, or null where it publishes none
     * (1.23, #71).
     *
     * Null on a TIMED set, on the same marker [published] reads: nothing
     * counts reps on a hold or a carry, and the row's NOT NULL column holds
     * the batch segmenter's count there (SessionRepository.recordSet:
     * manualReps ?: liveReps ?: analysis.reps.size, the first two null when
     * nothing counts), which no counter produced. Every other set publishes
     * its stored figure, 0 included -- on a set counted in reps, 0 is a
     * count.
     */
    fun publishedReps(actualReps: Int, timed: Boolean): Int? = if (timed) null else actualReps

    /**
     * The word for a row whose CADENCE GUIDE's own count is known beside its
     * recorded one (#336).
     *
     * [recordedReps] is the row's `actualReps`. [guideCalls] is how many reps
     * the guide called while the set was performed -- the length of the set's
     * archived rep-mark stream, which on a guided set holds one mark per call
     * the guide counted and nothing else, since a `+1 REP` tap does nothing
     * there -- or null where no such stream is stored.
     *
     * Where the four-input form would say [RepsSource.METRONOME] and the
     * guide's count is known and differs from the recorded one, the recorded
     * figure is the lifter's correction of the guide's count -- field-47 set
     * 4, stopped at 8 while the guide called 10 -- and the word is
     * [RepsSource.CORRECTED], as a corrected sensor count is. The guide's
     * figure stays readable beside it in the archive's rep marks. Every other
     * row reads as the four-input form says: a guided set at the guide's own
     * count, a guided row with no stored marks (the collapse that remains),
     * and every set no guide counted, whose marks are taps or absent.
     */
    fun published(
        liveReps: Int?,
        repsManual: Boolean,
        timed: Boolean,
        guideCounted: Boolean,
        recordedReps: Int,
        guideCalls: Int?,
    ): RepsSource? {
        val word = published(liveReps, repsManual, timed, guideCounted)
        val guideOverruled = guideCalls != null && guideCalls != recordedReps
        return if (word == RepsSource.METRONOME && guideOverruled) RepsSource.CORRECTED else word
    }

    /**
     * The guide's own called count, published beside the word as
     * `guideReps` (export 1.26, #336), or null where none is published.
     *
     * The guided twin of `liveReps`: a sensor set the lifter corrected keeps
     * the sensor's figure beside the corrected one, and a guided set the
     * lifter corrected -- [published] says `corrected` -- keeps the guide's.
     * Published on every set the guide counted whose marks are stored, the
     * uncorrected ones included, so a reader can tell a set the guide called
     * through from one it was never asked about; null on every set no guide
     * counted, on a timed set, and where no marks are stored.
     *
     * At this commit it publishes nothing; the next commits pin and write it.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter", "FunctionOnlyReturningConstant")
    fun publishedGuideReps(
        liveReps: Int?,
        repsManual: Boolean,
        timed: Boolean,
        guideCounted: Boolean,
        guideCalls: Int?,
    ): Int? = null

    /** The same answer as the published word, for a caller that wants the string. */
    fun publishedWord(liveReps: Int?, repsManual: Boolean, timed: Boolean, guideCounted: Boolean): String? =
        published(liveReps, repsManual, timed, guideCounted)?.wireName

    /**
     * Whether the CADENCE GUIDE counted a set of this shape -- the input
     * [published] needs, which a stored tempo string is not.
     *
     * A tempo on the row says what the set was PRESCRIBED, never that anything
     * played it. An EXPLOSIVE lift carrying a tempo is given no cadence at all
     * -- it is judged on peak velocity and is deliberately unpaced -- so its
     * count is the lifter's own taps, and reading the tempo alone publishes
     * `metronome` for a set no guide ever counted.
     *
     * ONE STATEMENT OF WHO THE GUIDE COUNTS, not a second copy of it:
     * [CountingPolicy.counterFor] is asked, so the word the export publishes
     * and the counter the set actually ran under cannot drift apart. The
     * `imuConnected = false` argument is not a claim about the device -- the
     * metronome branch is decided before the sensor is consulted, and a set the
     * sensor counted is settled by its live count one branch earlier in
     * [published].
     *
     * [kind] IS NULLABLE, and null falls back to the tempo. That is the one
     * collapse left: a row carrying no stored geometry does not say what kind of
     * exercise it was, so a tempo'd row from before that column reads as guided,
     * which is what such a row almost always was.
     */
    fun guideCounted(hasTempo: Boolean, isTimed: Boolean, kind: ExerciseKind?): Boolean = when (kind) {
        null -> hasTempo
        else -> CountingPolicy.counterFor(hasTempo, isTimed, kind, imuConnected = false) == RepCounter.METRONOME
    }
}
