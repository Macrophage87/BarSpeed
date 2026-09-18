package com.macrophage.barspeed.dsp

/**
 * Whether the analysis can BOUND a rep's displacement, and therefore whether
 * the set may publish a claim derived from it. Issue #291.
 *
 * ## The defect
 *
 * `rom_m` is `RepSegmenter.displacement` over the rep's drive run, and
 * `SetAnalyzer.romSpreadPct` turns those figures into the post-set range chip.
 * On the committed field corpus a bench press publishes a 1.592 m rep and a
 * 98.1 % spread, a seated cable row publishes 1.880 m and an assisted pull-up
 * 1.938 m, against one-way travels of about 0.45, 0.5 and 0.6 m.
 * `RomDriftBaselineTest` pins all of it.
 *
 * ## Why there is no repair, measured rather than argued
 *
 * A rep begins and ends at rest, so the obvious fix is to detrend its velocity
 * across its own span. `RomDriftBaselineTest` computes two forms of that and
 * they disagree: on field-42 set 7's worst rep a two-point detrend reads
 * 1.544 m and a net-zero symmetrisation 1.780 m against a published 1.592 m; on
 * field-42 set 9's worst rep the same two read 0.666 m and 1.880 m against a
 * published 1.880 m. Two arithmetics whose answers move in opposite directions
 * on two reps are not a rule, and neither brings either rep under its lift's
 * travel.
 *
 * The reason is in the same test. Set 7's worst rep lowers 1.977 m and presses
 * 1.592 m back, so BOTH phases are inflated together and there is no offset
 * left to remove. Set 9's worst rep is moving at 1.031 m/s where its span
 * begins and -1.363 m/s where it ends, so it has no two rest points for a
 * detrend to be anchored on at all.
 *
 * So this file does what `AccelArtefact` does for an impossible acceleration
 * sample: it MARKS and WITHHOLDS, and does not repair. The raw stream is
 * untouched, every velocity and every rep count is untouched, and what narrows
 * is the SET-LEVEL claim. That is the owner's rule -- *"a figure the analysis
 * cannot bound is withheld or flagged, never published as if measured"* -- and
 * `AccelArtefact.peakEligible`'s own note states the same division: the per-rep
 * figures are what their windows measured, and the line underneath them is the
 * claim.
 *
 * ## The bound, and where it comes from
 *
 * The ZUPT pass is the only stage that pins the velocity integral to zero.
 * `VelocityEstimator.anchorAcceptable` decides which quiet windows become
 * anchors, and its ERASED-DISPLACEMENT cap is the only statement this pipeline
 * makes about how much of a reading may be drift: `0.5 * dv * dt`, the area of
 * the ramp the correction subtracts between two consecutive accepted anchors,
 * may not exceed `DspConfig.minRomM` -- the least distance the pipeline is
 * willing to call a rep, 0.10 m at the shipped defaults.
 *
 * That cap is spent PER INTER-ANCHOR INTERVAL, not per rep. So a rep's
 * displacement is bounded only when the interval it sits in was spent on it
 * alone:
 *
 * - an accepted anchor lies at or before the rep's span start, AND
 * - an accepted anchor lies at or after its span end, AND
 * - no OTHER rep's span lies wholly between those two anchors.
 *
 * Where several reps share one interval, 0.10 m of licensed drift removal is
 * all the analysis has applied across every one of them, and it bounds none of
 * them individually. Where no anchor follows the rep at all the offset after the
 * last anchor is a constant, which is the case `AccelArtefact.corruptedSpan`
 * describes as reaching to the end of the stream.
 *
 * NOT A FITTED THRESHOLD AND NOT A NEW CONSTANT. Every term is read off
 * `anchorAcceptable` and `DspConfig`; nothing here was tuned against the corpus.
 *
 * ## What it withholds, measured on the eleven committed captures
 *
 * At most ONE rep per set is bounded, and on NINE of the eleven captures none
 * is: field-42 sets 2, 5, 7, 9, 11 and 13, field-43 sets 5 and 6 and field-37
 * set 3 publish no bounded rep, and field-43 set 4 and field-37 set 8 publish
 * exactly one, at `rom_m` 0.351 and 0.200. `RomBoundCorpusTest` carries the
 * per-capture column and the one case where a set has a bounded SPAN and no
 * bounded REP: field-42 set 11, whose only exclusive interval holds a detection
 * its own Done cue excluded.
 *
 * That is the finding, not a side effect of it: on a real working set the bar
 * never goes quiet enough for an anchor to be accepted, so the whole working
 * window is one uncorrected interval. `romSpread_pct` therefore goes ABSENT on
 * every capture in this corpus rather than reading 98.1 %, which is the chip
 * going dark instead of lying.
 *
 * ## What this does NOT claim
 *
 * A bounded rep is not a correct one. The cap bounds the travel the CORRECTION
 * removed, never the residual an uncorrected non-linear bias leaves, and nothing
 * in this repository has an independently known travel to check a figure
 * against except the leg-curl rail (`RomDispersionTest`). Bounded means the
 * pipeline spent its whole drift budget on this one rep; it does not mean the
 * distance is right.
 *
 * It also says nothing about power. #291 claims `rom_m` multiplies into
 * `meanConPower_w` and `peakPower_w`; it does not -- those are
 * `load * (g + a/ratio) * (v/ratio)` over the drive window with no displacement
 * term, recomputed and reproduced exactly in `RomDriftBaselineTest`. They are
 * corrupted by the same VELOCITY, which is a separate remainder and is not
 * narrowed here.
 */
object RomBound {
    /**
     * Reps a set's displacement-derived claims may be taken over: those whose
     * own span had the drift correction's whole licensed budget spent on it.
     *
     * A rep carrying `null` is KEPT, on `AccelArtefact.peakEligible`'s rule:
     * absence is not falsity, and an analysis stored before this rule existed
     * was measured under one that asked nothing about anchors. Dropping those
     * would make an archived set lose a figure it has always published.
     */
    fun boundedReps(reps: List<RepAnalysis>): List<RepAnalysis> = reps.filter { it.romBounded ?: true }

    /**
     * The least number of bounded reps a DISPERSION figure may be taken over.
     *
     * Two, which is `SetAnalyzer.romSpreadPct`'s own existing minimum and not a
     * second number: a deviation over one rep is zero by construction and would
     * read as reps that agreed perfectly.
     *
     * A MEAN is not gated on this and needs only one, which is why
     * `Exporters`' `meanRom_m` does not read it: a mean over one rep is a
     * well-defined figure about that rep. Publishing neither below two would
     * delete `meanRom_m` from every one-rep set for no reason the evidence
     * supports.
     */
    const val MIN_BOUNDED_REPS = 2

    /**
     * Whether [span]'s displacement is bounded, given every span the segmenter
     * produced and the anchors the ZUPT pass accepted, both ascending.
     *
     * [allSpans] is the WHOLE segmented list rather than the list a caller has
     * already bounded at the cue or the work start: a detection that was
     * excluded from publication still lay inside the interval and still
     * consumed its drift budget, so leaving it out would call a shared interval
     * exclusive.
     */
    fun bounded(span: RepSpan, allSpans: List<RepSpan>, anchorIndices: IntArray): Boolean {
        val lo = startOf(span)
        val hi = endOf(span)
        val before = anchorIndices.filter { it <= lo }.maxOrNull() ?: return false
        val after = anchorIndices.filter { it >= hi }.minOrNull() ?: return false
        return allSpans.none { other ->
            val otherLo = startOf(other)
            val otherHi = endOf(other)
            (otherLo != lo || otherHi != hi) && otherLo >= before && otherHi <= after
        }
    }

    /** [bounded] for every span of a set, in the order the spans arrived. */
    fun boundedFlags(allSpans: List<RepSpan>, anchorIndices: IntArray): List<Boolean> =
        allSpans.map { bounded(it, allSpans, anchorIndices) }

    private fun startOf(span: RepSpan) = minOf(span.eccStartIdx, span.conStartIdx)

    private fun endOf(span: RepSpan) = maxOf(span.eccEndIdx, span.conEndIdx)
}
