package com.macrophage.barspeed.model

/**
 * Whether a finished set's sensor stream is handed to the rep segmenter, which
 * is what writes every per-rep velocity, ROM, tempo and power figure the set
 * publishes.
 *
 * Lifted out of `RecordViewModel`'s set-end `when` (#63), where a timed set was
 * the only shape kept off the segmenter: a timed set is measured on the clock
 * and builds its own verdicts from the clock, so it never reaches this
 * question with a stream to segment.
 *
 * WHAT THIS DOES NOT DECIDE: whether there is a stream at all (too few frames
 * is `SensorCapturePolicy`'s bound, read after this), and who COUNTED the set,
 * which is [CountingPolicy.counterFor]'s. The raw stream is persisted per set
 * either way, so a set kept off the segmenter here can still be analysed
 * offline; nothing captured is discarded by this answer.
 */
object RepAnalysisPolicy {
    /**
     * True when the segmenter is handed a set of this shape: a set measured on
     * reps rather than on the clock, of a kind whose reps are strokes of the
     * load.
     *
     * A CARRY is refused in either shape. Timed, it is measured on the clock
     * as before. Prescribed in reps, it is counted by the lifter
     * ([CountingPolicy.counterFor] gives it [RepCounter.MANUAL]) and its
     * stream used to be segmented anyway: the segmenter publishes per-rep
     * velocity, ROM and power for whatever runs it finds, and on a walker's
     * gait nothing captured says what those runs are. The figures are
     * withheld rather than published unbounded; the count is the lifter's.
     */
    fun segmentsReps(isTimed: Boolean, kind: ExerciseKind): Boolean = !isTimed && kind != ExerciseKind.CARRY

    /**
     * The note a set kept off the segmenter by [segmentsReps] records, in place
     * of the figures it does not publish: a carry prescribed in reps. Never
     * reached on a timed set, which records its clock verdicts instead.
     */
    const val WITHHELD_NOTE: String =
        "A carry is not measured as reps: no velocity, range or power is published for it. " +
            "The sensor stream is kept with the set."
}
