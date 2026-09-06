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
 * WHICH LIMITS THE COUNTS ARE TAKEN AGAINST. The field KDocs below name
 * [DspConfig]'s constants because that is where the numbers come from, not
 * because that is what a run is compared with. Since issue #70
 * [RepSegmenter.segmentDetailed] takes them from
 * [RunThresholds.forSeriesMappedToLifter], so four of the five -- the speeds
 * [DspConfig.pauseBandMps] and [DspConfig.startThresholdMps] and the lengths
 * [DspConfig.minRomM] and [DspConfig.maxRunDisplacementM] -- are scaled by
 * `abs(LiftDirection.sensorToLifter)`. [DspConfig.minPhaseS] is a duration and
 * is not: mapping a series into another frame does not change when a sample
 * arrived. `sensorToLifter` is the declared `travelRatio` carrying the
 * inversion sign and the scale takes only its magnitude, so at travelRatio 1.0
 * all five are DspConfig's own values whichever way the sensor is mounted.
 *
 * Issue #138; issue #70 for the paragraph above.
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
 * Each names WHICH GATE emptied the rep list, and nothing beyond it, with one
 * exception named where it is declared: [MOUNT_NOT_DECLARED] says the
 * segmenter was never run at all. They are
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
 * A SET THE ANALYZER NEVER SAW IS STILL MOSTLY UNSAYABLE.
 * `RecordViewModel.runSetWrite` builds a placeholder `SetAnalysis` for a
 * timed set and for any set carrying fewer than eight samples, and that
 * object's `noRepsReason` is the default null. So a DYNAMIC set that captured
 * 1-7 samples still publishes an empty summary with no reason, which is the
 * same absence #138 is about arriving by a different route.
 *
 * THAT PARAGRAPH SAID "IT ALSO CANNOT BE REACHED BY A SET THE ANALYZER NEVER
 * SAW", AS A UNIVERSAL, AND IT IS DELETED RATHER THAN REWORDED. It was true
 * of every value that existed when it was written and is false now:
 * [MOUNT_NOT_DECLARED] is set inside `SetAnalyzer.analyze` before the
 * segmenter is reached, so exactly one population of unsegmented sets can
 * now say why it is blank. The placeholder sets above still cannot, and
 * that half of the sentence stands.
 *
 * No committed capture is named here as an example of it.
 * `field-rdl-3010-10rep-s36-set04` was, with one surviving rep against a
 * movement run displacing 123.64 m; issue #94's runaway correction took it to
 * ten reps against the ten the lifter counted -- ten as of
 * f8446fb8097c5c85d6c4bcf712d51c57b583491c; this branch's slow-eccentric
 * fallback moves the same capture to eleven -- so the example is deleted
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
     * [SetEnd]. Segmentation did not fail: the drives are real detections
     * off a real stream, and the set's own end cue is what excluded them.
     * This said "the one value that does not mean segmentation failed" and
     * that is deleted -- [BEFORE_WORK_START] and [MOUNT_NOT_DECLARED] do not
     * mean it either.
     */
    @SerialName("afterSetEndCue")
    AFTER_SET_END_CUE("afterSetEndCue"),

    /**
     * The segmenter resolved spans, the set's own end cue kept some of them,
     * and every one it kept finished before the set's WORK began. See
     * [WorkStart]. Issue #245.
     *
     * The head-of-stream twin of [AFTER_SET_END_CUE] and, like it, not a
     * segmentation failure: the drives are real movements of a real stream,
     * they are simply movements the lifter made during the prep countdown.
     *
     * A word rather than a null or a reuse of [AFTER_SET_END_CUE], because
     * both alternatives are false statements. A null would republish issue
     * #138's defect on a new population -- an empty rep list with nothing
     * saying why, indistinguishable from a manual set recorded with no sensor
     * -- and [AFTER_SET_END_CUE] would say the set ended before its detections
     * did, which is the opposite of what happened.
     *
     * NO COMMITTED CAPTURE REACHES THIS. It needs a set whose every kept
     * detection finished before its own work-start instant, which is a set the
     * lifter handled during the countdown and then did nothing measurable in;
     * none of the forty-two captures here is one, and this is written from the
     * rule rather than from an observation.
     */
    @SerialName("beforeWorkStart")
    BEFORE_WORK_START("beforeWorkStart"),

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

    /**
     * The analysis was pointed at a unit the set did not arm, and the
     * exercise's declared geometry describes a MOUNT -- so nothing on the
     * record says which geometry that unit needs. Issue #247.
     *
     * THE ONLY VALUE SET BEFORE THE SEGMENTER RUNS AT ALL, and the KDoc
     * above is corrected for it rather than left standing: the segmenter
     * never ran. That, and not "the one value that is not a segmentation
     * gate", is what is unique about it -- [AFTER_SET_END_CUE] and
     * [BEFORE_WORK_START] are not segmentation failures either.
     * [of] cannot return this and never will; it takes a census, and there is
     * no census when nothing was segmented. `SetAnalyzer.analyze` sets it
     * before the first span, which also makes this the first value reachable
     * by a set the segmenter never saw -- the gap this type's own KDoc
     * describes as "unsayable" is narrowed by exactly this one case and is
     * otherwise unchanged.
     *
     * WHY REFUSING BEATS GUESSING. `SensorCapturePolicy.analysedStream` moves
     * the analysis onto the partner when the armed unit delivered too few
     * frames. On a cable machine the declared geometry belongs to the ARMED
     * unit, and analysing the partner under it swaps the concentric and the
     * eccentric outright -- `FallbackMountGeometryTest` measures the swap on
     * a synthetic pair as 1.89 s and 0.98 s exchanged, at an unchanged rep
     * count. The tempting repair is to assume the partner is on the lifter's
     * side and invert the geometry for it. Field-38 is why that is not done:
     * the pushdown and the pulldown of that session carry the SAME
     * declaration -- `sensorOnStack` true, `sensorInverted` true -- and,
     * on the owner's word, different second-unit mounts. One declaration,
     * two mounts, so an inference from the declaration alone is a coin flip
     * and half of its outcomes publish an inverted record silently.
     *
     * So the set publishes no figures and keeps its capture. Every stream is
     * archived either way, and a reader who knows where the units were can
     * re-derive the set from the raw CSV under any geometry; a published
     * inversion cannot be told from a real one by anybody.
     *
     * NOT RETROACTIVE. The value is computed when a set is analysed and
     * frozen into its stored analysis, so no set already on disk gains it.
     */
    @SerialName("mountNotDeclared")
    MOUNT_NOT_DECLARED("mountNotDeclared"),
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
        fun of(
            census: SegmentationCensus,
            spansWithinSetEnd: Int,
            spansWithinWindow: Int = spansWithinSetEnd,
        ): NoRepsReason? = when {
            spansWithinWindow > 0 -> null
            spansWithinSetEnd > 0 -> BEFORE_WORK_START
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
