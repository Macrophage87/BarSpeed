package com.macrophage.barspeed.dsp

/**
 * Whether a detection is a rep of the set at all, judged on the set's OWN
 * reps rather than on any number tuned per movement. Issue #125.
 *
 * ## What this refuses, and what produced it
 *
 * Field session 37 (2026-09-02, app 0.1.48) published, on its set 10, a
 * seated-assisted pull-up whose last detection carried `rom_m` **1.746** on a
 * set whose other four detections ran 0.330 to 0.481 m, `peakConVel_mps`
 * 1.044 against their 0.435, and `peakPower_w` **552.4** against their 101.6.
 * The set's two headline figures were that one detection. Reproduced here
 * from the committed `field-assistedpullup-3010-s37-set10.csv`.
 *
 * THE CAUSE IS TRACED, not inferred, and the trace is now a MEASUREMENT
 * rather than a description. That capture carries exactly two accelerometer
 * samples above 4 g, at `sample_idx` 3692 and 3693, reading 4.048 g and
 * 16.191 g between neighbours under 1 g and 1.3 g, on a set whose gyro
 * magnitude never exceeds 5.494 dps. Replace both with the last in-range
 * reading and re-run the unmodified analyzer and the set resolves **four**
 * detections instead of five: the fifth does not shrink, it ceases to exist,
 * and the four that remain carry the same ranges the refusal keeps. Two
 * corrupt samples did not inflate a rep, they manufactured one.
 *
 * THE SAME EXPERIMENT ON THE NEIGHBOURING SET SEPARATES A MANUFACTURED
 * DETECTION FROM AN INFLATED REAL ONE.
 * `field-assistedpullup-3010-s37-set08.csv` carries one sample above 4 g,
 * reading 14.982 g at `sample_idx` 4079, and its detection 6 has the same
 * silhouette as set 10's phantom -- the last detection, the largest range,
 * the largest power. Substitute that one sample and re-run and the set still
 * resolves **seven** detections; detection 6 survives and only its figures
 * collapse, `rom_m` 0.878 -> 0.230 and `peakPower_w` 407.4 -> 69.4. Set 8's
 * is a real rep whose numbers a corrupt sample inflated. Set 10's is not a
 * rep.
 *
 * THAT PAIR DOES NOT SHOW CLAUSE 1 IS LOAD-BEARING, AND THIS PARAGRAPH USED
 * TO SAY IT DID. Set 8's detection 6 is kept by BOTH clauses at once: it
 * resolved an eccentric partner at `ecc_s` 1.68, and at 4.46x the median
 * range of its set's other detections it is also UNDER [RANGE_RATIO_BOUND],
 * so clause 2 alone already keeps it and the pair separates either way. The
 * case that shows clause 1 carrying weight on its own is
 * `field-ohp-prepinflated-s37-set04` rep 4, which reaches 4.82x with both
 * phases resolved -- above the bound, kept by clause 1 and by nothing else
 * -- and is recorded as [MAX_PAIRED_RANGE_RATIO_OBSERVED].
 *
 * **This rule is deliberately not the sample-level fix.** Refusing the SAMPLE
 * changes the velocity series and therefore re-partitions the whole set:
 * measured over the committed corpus, every capture but five carries at least
 * one sample above 4 g, and `field-ohp-prepinflated-s37-set03` resolves 11
 * detections untouched, 7 with every sample above 8 g or above 7 g replaced
 * by the last in-range reading, 9 at 6 g, 5 g and 4 g, and 8 at 3 g. A rule
 * at the sample level is a change to the MEASUREMENT and moves the published
 * figures of most of the corpus at once.
 * This one is a change to a DECISION over an already-computed rep list, it is
 * a pure function of that list, and it refuses exactly one detection anywhere
 * in the committed corpus under declared geometry. The sample-level defect is
 * real and is NOT fixed here -- see "what this does not reach" below.
 *
 * ## The rule
 *
 * A detection is refused when BOTH hold:
 *
 *  1. **It resolved no eccentric partner** -- [RepAnalysis.eccS] is null. A
 *     rep of a set returns to where it started, and the return stroke is the
 *     only corroboration the segmenter has that a drive covered the range it
 *     says it did. A drive with no return has none.
 *  2. **Its range exceeds [RANGE_RATIO_BOUND] times the median range of the
 *     set's OTHER detections.** The equipment's travel does not change within
 *     a set, so the set's own reps are the reference and no per-movement
 *     number is needed. This is the property [SetEnd] declines to use a
 *     threshold for, and for the same reason: an absolute range bound would
 *     need a figure per exercise. A RELATIVE one needs none.
 *
 * THE TWO CLAUSES ARE NOT EQUALLY LOAD-BEARING, and saying so is the point of
 * writing it down. Clause 2 carries the corpus: dropping it refuses every
 * drive-only detection there is, which is most of what a concentric-first
 * lift resolves at all.
 *
 * CLAUSE 1 IS LOAD-BEARING TOO, AND THIS PARAGRAPH USED TO SAY IT WAS NOT.
 * The earlier text read "no detection that resolved both phases reaches 4.5x
 * anywhere in the corpus, the largest being 4.16x, so removing clause 1
 * refuses the same one detection". That was measured over 33 captures and is
 * false over the corpus as it stands: `RepRefusalCorpusTest` walks every
 * committed capture and finds the largest two-phase ratio at
 * [MAX_PAIRED_RANGE_RATIO_OBSERVED], ABOVE [RANGE_RATIO_BOUND], so removing
 * clause 1 would refuse a detection that resolved both its phases.
 * `GeometryFallbackTest`'s direction-only case says the same thing from the
 * other side: degraded geometry produces paired detections past the bound.
 * The clause is what keeps this rule off a detection whose own return stroke
 * corroborates its range, which is the case where refusing would be most
 * wrong and least visible.
 *
 * ## Where the bound comes from
 *
 * [RANGE_RATIO_BOUND] is 4.5 and it is FITTED, to the corpus this repository
 * holds. It is not derived from physics and nothing here claims it is. What
 * is measured, and what `RepRefusalCorpusTest` re-measures on every push, is
 * the gap it sits in:
 *
 * - the largest ratio reached by any DRIVE-ONLY detection this rule KEEPS is
 *   [MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED], so the bound clears the nearest
 *   detection below it by a factor of 1.04;
 * - the one detection it refuses reaches 5.23, clearing the bound by a
 *   factor of 1.16 -- pinned in `ArtefactRepTest`, which reconstructs that
 *   set's list from the segmenter and so can still see a detection this rule
 *   has removed from what the set publishes;
 * - the largest ratio reached by any TWO-PHASE detection is
 *   [MAX_PAIRED_RANGE_RATIO_OBSERVED], which is ABOVE the bound and is kept
 *   by clause 1 and by nothing else.
 *
 * The bound errs toward ADMITTING a phantom rather than refusing a rep,
 * because a refused real rep moves `velocityLoss_pct` -- best rep to LAST rep
 * -- against the wrong rep, which is the defect issue #125 exists for
 * appearing in a new place.
 *
 * The cost of that direction is stated for the captures this repository
 * HOLDS, which is not the same as stated in full. On the same field-37
 * session, set 3's largest drive-only detection reaches 2.29x and is not
 * refused: it goes on publishing 507.0 W from a range of 1.223 m on a seated
 * overhead press. Set 1's largest drive-only detection reaches 2.00x --
 * `rom_m` 1.232 against a median-of-others 0.617 -- and is still nowhere
 * near any bound. A tighter bound reaches set 3's and starts taking paired
 * detections with it.
 *
 * ## Fewer than [MIN_DETECTIONS] and the null the count carries
 *
 * With three detections or fewer there is no median of others worth deriving
 * a bound from -- at two, "the median of the others" is one rep compared
 * against one rep. The rule does not run there and [refusedCount] answers
 * NULL, not 0: "no bound could be derived" and "a bound ran and refused
 * nothing" are different facts about a set, and a reader of a stored analysis
 * cannot recover the difference from anything else it carries. Same doctrine
 * as [SetAnalysis.detectionsAfterSetEndCue].
 *
 * ## What this does not reach, stated because it is the larger half
 *
 * A corrupt sample landing INSIDE a real rep's drive sets that rep's
 * `peakConVel_mps` and `peakPower_w` without moving its range or its mean, so
 * the detection is a rep by every test here and is kept. Two committed
 * captures show it. On `field-ohp-prepinflated-s37-set03`, rep 6 carries
 * `rom_m` 0.533 and `meanConPower_w` 135.1 -- ordinary for that set -- and
 * `peakPower_w` **783.2**, from an 11.601 g sample inside its drive. On
 * `field-assistedpullup-3010-s37-set08`, detection 6 publishes `peakPower_w`
 * 407.4 against a set whose other six run 48.7 to 69.1, and the substitution
 * experiment above says that figure is 69.4 without the one corrupt sample.
 * Nothing in this file touches either. They need the sample-level refusal
 * this rule deliberately is not, and that is raised as an adjacent defect
 * rather than folded in.
 *
 * ## What a refusal does to the COUNT
 *
 * Stated here because it is the consequence a reader of a set is most likely
 * to meet and it is not visible from this file's own signatures.
 * `SessionRepository` writes `actualReps = manualReps ?: analysis.reps.size`,
 * so a refusal lowers the stored automatic count on a set that has no manual
 * one, and leaves it alone on a set that has. `RecordViewModel.effectiveReps`
 * is `repsOverride ?: analysis.reps.size`, so the rest screen's pre-filled
 * count moves with it. `repMetricsComplete` compares the two and can move in
 * either direction. `stoppedEarly` and `failed` do NOT move.
 *
 * On field-37's set 10 the lifter's own count was 6, recorded manually --
 * `repsManual: true` in the session archive's own `meta.json`, which is
 * where `ArtefactRepTest`'s set-10 bullet reads it from too -- and
 * the analyzer resolved 5 before this rule and 4 after it. The refusal takes
 * the automatic count further from the lifter's 6 -- and to exactly the
 * number a stream without the two corrupt samples produces. The set's
 * under-count is a counting defect this rule neither causes nor repairs.
 *
 * NOTHING IS DELETED. A refused detection is counted and the count is
 * published; the set's raw IMU stream is persisted unchanged and every figure
 * above can be re-derived from it under any other rule.
 */
object RepRefusal {
    /**
     * The wire word for the one refusal this file makes: a detection with no
     * eccentric partner whose range is an outlier against the set's own reps.
     *
     * Named for BOTH clauses on purpose. A word naming only the range would
     * read as a range cap, which this is not -- a paired detection at
     * [MAX_PAIRED_RANGE_RATIO_OBSERVED] is kept.
     */
    const val UNPAIRED_RANGE_OUTLIER = "unpairedRangeOutlier"

    /**
     * Every word [reason] can answer. Mirrored by
     * `SessionExport.VALID_REFUSED_DETECTION_REASONS`, which this module
     * cannot see -- the dependency runs the other way. The equality IS
     * pinned: `RefusedDetectionAnalysisTest`'s "the refusal words are the
     * ones the export publishes" asserts it, from this module's own test
     * source set, which is the only place both sides are visible at once.
     */
    val REASONS = setOf(UNPAIRED_RANGE_OUTLIER)

    /**
     * The largest range ratio any detection that resolved BOTH phases reaches
     * across the committed corpus, under each capture's declared geometry.
     *
     * IT IS ABOVE [RANGE_RATIO_BOUND], on
     * `field-ohp-prepinflated-s37-set04` rep 4 -- `rom_m` 1.688 against a
     * median-of-others 0.35, with `ecc_s` 0.60. Clause 1 is the only thing
     * that keeps it, which is why this figure is recorded and pinned.
     */
    const val MAX_PAIRED_RANGE_RATIO_OBSERVED = 4.82

    /**
     * The largest range ratio any DRIVE-ONLY detection reaches without being
     * refused, across the committed corpus: `field-legcurl-1030-12rep-c`
     * rep 4, `rom_m` 1.674 against a median-of-others 0.386.
     *
     * The nearest thing below the bound, and a leg curl that ranged 1.674 m
     * is not a rep either. The bound admits it, and this figure is what says
     * by how little.
     */
    const val MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED = 4.34

    /**
     * The bound itself. It sits above
     * [MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED] and below the 5.23 of the one
     * detection refused; see "where the bound comes from".
     */
    const val RANGE_RATIO_BOUND = 4.5

    /**
     * Detections a set needs before a bound may be derived from it: this many
     * in total, so at least three OTHERS form the median any one is judged
     * against.
     */
    const val MIN_DETECTIONS = 4

    /**
     * Indices of [reps] this rule refuses, in ascending order. Empty when the
     * rule does not run.
     *
     * Judged against the ORIGINAL list throughout -- the medians are not
     * recomputed after a refusal, so the answer does not depend on the order
     * refusals are taken in and a set cannot cascade into emptiness.
     */
    fun refusedIndices(reps: List<RepAnalysis>): List<Int> {
        if (reps.size < MIN_DETECTIONS) return emptyList()
        return reps.indices.filter { i ->
            val rep = reps[i]
            rep.eccS == null && rep.romM > RANGE_RATIO_BOUND * medianRangeOfOthers(reps, i)
        }
    }

    /**
     * The reps of the set, with the refused detections removed and the
     * survivors renumbered from zero.
     *
     * Renumbered because [RepAnalysis.index] is the rep's position in what the
     * set publishes and a refusal can fall anywhere in the list, not only at
     * its end -- unlike the set-end cue bound, which removes a suffix and
     * leaves every retained index alone.
     */
    fun kept(reps: List<RepAnalysis>): List<RepAnalysis> {
        val refused = refusedIndices(reps).toSet()
        if (refused.isEmpty()) return reps
        return reps.filterIndexed { i, _ -> i !in refused }
            .mapIndexed { i, rep -> rep.copy(index = i) }
    }

    /**
     * How many detections this set refused, or null when no bound could be
     * derived from it. Never 0 for the second case -- see the class KDoc.
     */
    fun refusedCount(reps: List<RepAnalysis>): Int? =
        if (reps.size < MIN_DETECTIONS) null else refusedIndices(reps).size

    /** The word for why, or null when nothing was refused or nothing could be. */
    fun reason(reps: List<RepAnalysis>): String? = UNPAIRED_RANGE_OUTLIER.takeIf { (refusedCount(reps) ?: 0) > 0 }

    /**
     * The range ratio [refusedIndices] judges the detection at [index] on:
     * its range over the median range of the set's OTHER detections.
     *
     * Public because the corpus walk in `RepRefusalCorpusTest` measures the
     * two constants above with it -- [MAX_PAIRED_RANGE_RATIO_OBSERVED] and
     * [MAX_UNPAIRED_KEPT_RANGE_RATIO_OBSERVED]. [RANGE_RATIO_BOUND] is the
     * fitted bound and the walk does not measure it. A walk that recomputed
     * the ratio itself could measure a different quantity from the one the
     * rule applies.
     * Null when the set is too small for a bound to be derived from it.
     */
    fun rangeRatio(reps: List<RepAnalysis>, index: Int): Double? =
        if (reps.size < MIN_DETECTIONS) null else reps[index].romM / medianRangeOfOthers(reps, index)

    /**
     * The median range of every detection of [reps] EXCEPT [index], taking the
     * lower of the two middle values at even counts.
     *
     * The lower median rather than their mean: the mean of two would let a
     * single outlier among the others raise the reference and so hide a second
     * outlier, which is exactly the set this rule has to survive.
     */
    private fun medianRangeOfOthers(reps: List<RepAnalysis>, index: Int): Double {
        val others = reps.filterIndexed { i, _ -> i != index }.map { it.romM }.sorted()
        return others[(others.size - 1) / 2]
    }
}
