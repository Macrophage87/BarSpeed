package com.macrophage.barspeed.dsp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A read-only census of one pass of [RepSegmenter] over one set, produced by
 * [RepSegmenter.segmentDetailed] alongside the spans themselves.
 *
 * It exists so a set that resolved NO reps can say which stage of the
 * segmenter emptied it. Every field is a count taken while the shipped code
 * ran, not a second walk over the same data: there is one implementation of
 * the classification and the pairing, and this is what it observed.
 *
 * Issue #138.
 */
data class SegmentationCensus(
    /**
     * Contiguous runs of the drift-corrected series whose velocity is outside
     * [DspConfig.pauseBandMps], before any demotion. Zero means the set never
     * left the pause band.
     */
    val movementRuns: Int,
    /**
     * Movement runs displacing further than [DspConfig.maxRunDisplacementM].
     *
     * THE THREE DEMOTION COUNTS ARE INDEPENDENT AND DO NOT SUM. `classifyRuns`
     * demotes on a single three-way `||`, so one run failing two terms is
     * counted under both, and `movementRuns - qualifyingRuns` is the number
     * actually demoted. Nothing here attributes a demotion to one cause.
     */
    val overDisplacementCap: Int,
    /** Movement runs never peaking above [DspConfig.startThresholdMps]. */
    val belowStartThreshold: Int,
    /** Movement runs lasting less than [DspConfig.minPhaseS]. */
    val shorterThanMinPhase: Int,
    /** Movement runs surviving all three demotion terms. */
    val qualifyingRuns: Int,
    /**
     * Drives the pairing walk reached and rejected because the drive
     * displaced less than [DspConfig.minRomM].
     *
     * THE NAME SAYS "PAIRS" AND ON ONE OF THE TWO WALKS THERE IS NO PAIR. On
     * the eccentric-first walk the matching drive has already been found when
     * the ROM test runs, so a pair was formed and then discarded. On the
     * concentric-first walk THE DRIVE ALONE IS THE REP: `RepSegmenter` tests
     * the drive run's displacement before it searches for a following
     * eccentric, so the rejection precedes any pair and none is formed. Both
     * increments land in this one count, and the field name overstates the
     * second case.
     *
     * See that constant's own KDoc: the floor filters on reconstruction
     * quality while claiming to filter on rep size, so a set emptied here is
     * one whose displacement reconstruction produced reps too small to be
     * real, not necessarily one where the lifter moved a short way.
     */
    val pairsBelowMinRom: Int,
    /** Spans the segmenter returned, before any set-end-cue bound is applied. */
    val spans: Int,
)

/**
 * Why a set resolved no reps, as a single value fit to be published.
 *
 * Issue #138: a healthy stream -- contiguous `sample_idx`, no gap, the whole
 * set window covered -- can yield `reps: []` and a `summary` with every key
 * absent, and the export states that only by omission. A set whose integrator
 * ran away is then byte-identical in the archive to a manual set recorded with
 * no sensor at all. This is the fact that tells them apart.
 *
 * ## What these names claim
 *
 * Each names WHICH GATE emptied the rep list, and nothing beyond it. They are
 * statements about the pipeline, not about the lifter or the implement: a set
 * reporting [RUNS_EXCEED_DISPLACEMENT_CAP] displaced further than any real
 * phase can, which `DspConfig.maxRunDisplacementM` reads as unanchored
 * integration drift, but nothing in this repository has observed the bar to
 * confirm that reading. Nothing here is evidence about what the sensor or the
 * lifter did.
 *
 * ## What it does NOT cover
 *
 * ONLY EMPTINESS. [of] returns null the moment one rep survives, so a set
 * resolving 1 of 10 carries no reason at all. Under-resolution reaching zero
 * is what this answers; under-resolution stopping short is the same defect
 * and is still unsayable. `BlankAnalysisTest` pins that limit.
 *
 * IT ALSO CANNOT BE REACHED BY A SET THE ANALYZER NEVER SAW.
 * `RecordViewModel.runSetWrite` builds a placeholder `SetAnalysis` for a
 * timed set and for any set carrying fewer than eight samples, and that
 * object's `noRepsReason` is the default null. So a DYNAMIC set that captured
 * 1-7 samples still publishes an empty summary with no reason, which is the
 * same absence #138 is about arriving by a different route.
 *
 * No committed capture is named here as an example of it.
 * `field-rdl-3010-10rep-s36-set04` was, with one surviving rep against a
 * movement run displacing 123.64 m; issue #94's runaway correction took it to
 * ten reps against the ten the lifter counted, so the example is deleted
 * rather than repointed at another capture.
 *
 * It also cannot be answered for a set already recorded. The value is computed
 * when the set is analysed and frozen into its stored analysis, the way
 * `tempoCompliance` is; the export does not re-run the segmenter. Every set
 * recorded before this existed keeps publishing an empty summary, and that is
 * a permanent state for those rows rather than something a later export fills
 * in.
 */
@Serializable
enum class NoRepsReason(val wireName: String) {
    /**
     * The segmenter resolved spans and the set's own end cue excluded all of
     * them: every detected drive began after the app stopped prescribing. See
     * [SetEnd]. This is the one value that does not mean segmentation failed.
     */
    @SerialName("afterSetEndCue")
    AFTER_SET_END_CUE("afterSetEndCue"),

    /**
     * No sample of the drift-corrected series left [DspConfig.pauseBandMps].
     * A sensor that was on and did not move, which is what
     * `field-still-0rep` is.
     */
    @SerialName("noMovement")
    NO_MOVEMENT("noMovement"),

    /**
     * More than half the set's movement runs displaced further than
     * [DspConfig.maxRunDisplacementM] and were discarded.
     *
     * THE MAJORITY TEST IS A CHOICE AND IS FITTED TO ONE CAPTURE. A plain
     * "any run over the cap" would fire on `field-seated-ohp-2rep`, where one
     * run of seven is over the cap and the set is emptied by having no DOWN to
     * pair -- a different fact that would be reported as this one. A majority
     * separates the two on this corpus and nothing derives the fraction. Both
     * sides of it are pinned in `BlankAnalysisReasonTest`.
     */
    @SerialName("runsExceedDisplacementCap")
    RUNS_EXCEED_DISPLACEMENT_CAP("runsExceedDisplacementCap"),

    /**
     * No movement run survived demotion, and at least as many failed on peak
     * speed as on duration -- a tie reads as too slow.
     */
    @SerialName("runsBelowStartThreshold")
    RUNS_BELOW_START_THRESHOLD("runsBelowStartThreshold"),

    /** No movement run survived demotion, and more of them failed on duration than on peak speed. */
    @SerialName("runsTooBrief")
    RUNS_TOO_BRIEF("runsTooBrief"),

    /**
     * Movement runs survived and none of them paired into a rep -- the set
     * resolved strokes in one direction only, or the pairing walk never found
     * the second phase beside the first.
     */
    @SerialName("phasesUnpaired")
    PHASES_UNPAIRED("phasesUnpaired"),

    /**
     * At least one drive the pairing walk reached displaced less than
     * [DspConfig.minRomM], and nothing paired.
     *
     * THIS IS A PRESENCE TEST, unlike [RUNS_EXCEED_DISPLACEMENT_CAP] above
     * it, which was deliberately made a MAJORITY so that a minority cause
     * would not be named as the reason. The asymmetry is not derived: one
     * rejected drive among several merely unpaired ones is enough to report
     * this. No capture in this corpus reaches this value at all, so nothing
     * measures whether a majority would read better here too.
     */
    @SerialName("driveBelowMinRom")
    DRIVE_BELOW_MIN_ROM("driveBelowMinRom"),
    ;

    companion object {
        /**
         * The reason a set published no reps, or null when it published some.
         *
         * [spansWithinSetEnd] is the span count AFTER the set's own end cue
         * has excluded anything that began late; [SegmentationCensus.spans] is
         * the count before. They differ only on a cue-bounded set.
         *
         * The tests are asked IN PIPELINE ORDER, so the value names the
         * earliest stage at which the count was already zero, with one
         * deliberate exception: the displacement cap is asked before the
         * qualifying-run count, because a set whose movement is mostly
         * discarded as too long reaches "nothing left to pair" as a
         * CONSEQUENCE, and reporting the consequence would name the near
         * neighbour instead of the cause. That is a choice; see
         * [RUNS_EXCEED_DISPLACEMENT_CAP] for the capture that forces it to be
         * a majority rather than a presence.
         */
        fun of(census: SegmentationCensus, spansWithinSetEnd: Int): NoRepsReason? = when {
            spansWithinSetEnd > 0 -> null
            census.spans > 0 -> AFTER_SET_END_CUE
            census.movementRuns == 0 -> NO_MOVEMENT
            2 * census.overDisplacementCap > census.movementRuns -> RUNS_EXCEED_DISPLACEMENT_CAP
            census.qualifyingRuns == 0 ->
                if (census.belowStartThreshold >= census.shorterThanMinPhase) {
                    RUNS_BELOW_START_THRESHOLD
                } else {
                    RUNS_TOO_BRIEF
                }
            census.pairsBelowMinRom > 0 -> DRIVE_BELOW_MIN_ROM
            else -> PHASES_UNPAIRED
        }

        /** Every published value, in declaration order. */
        val wireNames: Set<String> = entries.map { it.wireName }.toSet()
    }
}
