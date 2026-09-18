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
 * `VelocityEstimator.anchorAcceptable` decides which quiet windows may become
 * anchors, and its ERASED-DISPLACEMENT cap is this pipeline's one statement
 * about how much of a reading may be drift: `0.5 * dv * dt`, the area of the
 * ramp the correction subtracts between two consecutive accepted anchors, may
 * not exceed `DspConfig.minRomM` -- the least distance the pipeline is willing
 * to call a rep, 0.10 m at the shipped defaults.
 *
 * THE CAP HOLDS ONLY FOR THE ANCHORS `anchorAcceptable` ACCEPTED, WHICH IS NOT
 * ALL OF THEM. `applyZupt` takes a flat window on
 * `stable && (nearPrev || starved)`, where `starved` means nothing has been
 * acceptable for longer than its `ANCHOR_STARVATION_S`, 6.0 s: after a starved
 * stretch the next flat window anchors with `nearPrev` FALSE, and the ramp back
 * to it erases whatever it erases. `applyZupt`'s own note calls that escape the
 * one route by which a slow phase can still be erased, 0 to 7 times per
 * capture, and keeps it because gating it takes the corpus from 19 to 58 in
 * absolute rep-count error. `VelocitySeries.anchorCapped` records which route
 * each anchor took, and it is what lets this file tell the two apart -- the
 * anchor indices alone cannot, so before that record existed this rule could
 * not either.
 *
 * The cap is spent PER INTER-ANCHOR INTERVAL, not per rep. So a rep's
 * displacement is bounded only when:
 *
 * - an accepted anchor lies at or before the rep's span start, AND
 * - an accepted anchor lies at or after its span end, AND
 * - no OTHER rep's span lies wholly between those two anchors, AND
 * - every anchor from the first of those to the second was accepted with the
 *   caps MET -- `anchorCapped` true -- so each interval the rep's span crosses
 *   erased at most `minRomM`.
 *
 * The bound is therefore `minRomM` per interval crossed: 0.10 m for a rep
 * sitting inside one interval, 0.10 m times k for a rep whose span crosses k of
 * them. It is not a single 0.10 m for the rep, and this file does not claim one.
 *
 * Where several reps share one interval, 0.10 m of licensed drift removal is
 * all the analysis has applied across every one of them, and it bounds none of
 * them individually. Where no anchor follows the rep at all the offset after the
 * last anchor is a constant, which is the case `AccelArtefact.corruptedSpan`
 * describes as reaching to the end of the stream.
 *
 * WHAT THE CAP DOES NOT COVER, and what is therefore not narrowed here:
 *
 * `applyZupt` also clamps every quiet sample under `DspConfig.pauseBandMps` to
 * zero, and its own comment says `anchorAcceptable` does not guard that loop. A
 * rep whose every interval is capped can still have had quiet samples inside
 * its span zeroed by that clamp. This file makes no claim about it; it is a
 * separate remainder of #291.
 *
 * `VelocityEstimator.estimate` is `RunawayDrift.corrected(estimateAnchored(...))`
 * -- a second stage runs AFTER the anchors are decided, and this rule reads
 * only the anchors. `RunawayDrift.corrected` subtracts the mean velocity of
 * every same-sign run displacing beyond `DspConfig.maxRunDisplacementM`, 2.0 m,
 * from `estimateAnchored`'s output, with no stated limit on how much that
 * removes -- it is not spent from `minRomM`'s budget and this file's cap says
 * nothing about it. Measured over the thirteen captures this rule is measured
 * on (`ArtefactCorpus.cases` plus `field-ohp-3010-8rep-s37-set01` and
 * `field-assistedpullup-3010-s37-set10`): eleven of the thirteen have at least
 * one run `RunawayDrift` rewrites, all but `field-assistedpullup-3010-s37-set08`
 * and `field-assistedpullup-3010-s37-set10`. So a rep whose every interval is
 * capped can still have had metres removed by this second stage with nothing
 * here bounding it -- a second remainder of #291, beside the pause-band clamp.
 *
 * NOT A FITTED THRESHOLD AND NOT A NEW CONSTANT. Every term is read off
 * `anchorAcceptable`, `applyZupt`'s own acceptance expression and `DspConfig`;
 * nothing here was tuned against the corpus.
 *
 * ## What it withholds, measured on the thirteen committed captures this rule
 * has been checked against, and separately on three more
 *
 * NO rep of any of the eleven `ArtefactCorpus.cases` is bounded, and neither is
 * any rep of `field-ohp-3010-8rep-s37-set01` (eleven detections, `ArtefactRepTest`)
 * or `field-assistedpullup-3010-s37-set10` (`ArtefactRefusalWiringTest`) --
 * thirteen captures measured, thirteen with no bounded rep. Nor is any rep of
 * the three leg-curl fixtures `RomDispersionTest` reads: `field-legcurl-1030-
 * 12rep`, `-b` and `-c` publish 12, 13 and 11 detections and every one comes
 * back `false`, on the rail this file's own "What this does NOT claim" section
 * names as the one machine with an independently known travel to check a
 * figure against. `RomBoundCorpusTest` carries the
 * per-capture column, and `AnchorRouteTest` carries the measurement that made
 * the fourth clause necessary: a ROUTE-BLIND version of this rule -- the first
 * three clauses without the `anchorCapped` one -- admitted exactly three spans
 * in the whole corpus, field-43 set 4's rep 7, field-37 set 8's rep 5 and
 * field-42 set 11's twelfth span, and every one of the three sat in an interval
 * a STARVATION-route anchor closed. Measured over those intervals, the
 * displacement the correction erased was 1.0180 m, 5.0198 m and 2.3153 m
 * against the 0.10 m the derivation above cites. The reps the route-blind rule
 * admitted were exactly the ones whose erasure it could state no limit on, and
 * it published them at `rom_m` 0.351 m and 0.200 m.
 *
 * That is the finding, not a side effect of it: on a real working set the bar
 * never goes quiet enough for an ACCEPTABLE anchor, so the whole working window
 * is one uncorrected interval and the anchor that eventually closes it is taken
 * by starvation. `romSpread_pct` therefore goes ABSENT on every capture in this
 * corpus rather than reading 98.1 %, and under the fourth clause `meanRom_m`
 * goes absent on all eleven as well, where the route-blind rule published it on
 * two of them.
 *
 * ## What this does NOT claim
 *
 * A bounded rep is not a correct one. The cap bounds the travel the CORRECTION
 * removed, never the residual an uncorrected non-linear bias leaves, and nothing
 * in this repository has an independently known travel to check a figure
 * against except the leg-curl rail (`RomDispersionTest`). Bounded means every
 * interval this rep's span crossed removed at most the licensed budget and
 * removed it for this rep alone; it does not mean the distance is right.
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
     * produced, the anchors the ZUPT pass accepted (ascending) and the route
     * each of those anchors took -- [VelocitySeries.anchorCapped], one entry per
     * anchor.
     *
     * [allSpans] is the WHOLE segmented list rather than the list a caller has
     * already bounded at the cue or the work start: a detection that was
     * excluded from publication still lay inside the interval and still
     * consumed its drift budget, so leaving it out would call a shared interval
     * exclusive.
     */
    fun bounded(span: RepSpan, allSpans: List<RepSpan>, anchorIndices: IntArray, anchorCapped: BooleanArray): Boolean {
        // A route record that does not describe this anchor list is an UNKNOWN
        // route, and unknown withholds: `VelocitySeries.anchorCapped` defaults
        // to empty, so a hand-built series bounds nothing rather than
        // everything.
        if (anchorCapped.size != anchorIndices.size) return false
        val lo = startOf(span)
        val hi = endOf(span)
        val firstAt = anchorIndices.indices.filter { anchorIndices[it] <= lo }.maxOrNull() ?: return false
        val lastAt = anchorIndices.indices.filter { anchorIndices[it] >= hi }.minOrNull() ?: return false
        // Every interval between those two anchors must have had its erasure
        // capped, and an anchor's flag describes the interval ENDING at it, so
        // the first anchor's own flag is not read here -- whatever closed the
        // interval before the rep began removed nothing from inside it.
        for (at in firstAt + 1..lastAt) if (!anchorCapped[at]) return false
        val before = anchorIndices[firstAt]
        val after = anchorIndices[lastAt]
        return allSpans.none { other ->
            val otherLo = startOf(other)
            val otherHi = endOf(other)
            (otherLo != lo || otherHi != hi) && otherLo >= before && otherHi <= after
        }
    }

    /** [bounded] for every span of a set, in the order the spans arrived. */
    fun boundedFlags(allSpans: List<RepSpan>, anchorIndices: IntArray, anchorCapped: BooleanArray): List<Boolean> =
        allSpans.map { bounded(it, allSpans, anchorIndices, anchorCapped) }

    private fun startOf(span: RepSpan) = minOf(span.eccStartIdx, span.conStartIdx)

    private fun endOf(span: RepSpan) = maxOf(span.eccEndIdx, span.conEndIdx)
}
