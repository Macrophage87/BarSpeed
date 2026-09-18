package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample

/**
 * Samples whose acceleration magnitude is above anything a lifted implement can
 * produce, and what the analysis is allowed to publish from a rep that contains
 * one. Issues #290 and #255.
 *
 * ## The defect
 *
 * One sample sets a MAXIMUM. `RepAnalysis.peakConVelMps` and
 * `RepAnalysis.peakPowerW` are `max` over a rep's drive window, so a single
 * reading the sensor cannot have measured becomes the rep's published peak and,
 * through `reps.maxOf`, the set's. field-42 set 2 is a 24.9476 kg seated
 * overhead press publishing `peakPower_w` 3606.3 and `peakConVel_mps` 2.516:
 * 3606.3 / (2.516 * 24.9476) is 57.5 m/s2, about 4.9 g of net drive, on a
 * tempo-`3010` press rated 4-8. field-43 set 5 is worse -- 4347.4 W at
 * 83.9 kg, from samples where the accelerometer RAILS.
 *
 * `RepRefusal` (#125) cannot reach it. That rule judges whole detections on
 * their RANGE against the set's median, and a rep inflated by one sample keeps
 * an ordinary range and an ordinary mean; only its peaks move. #290 measured
 * field-42 set 2 rep 4 at `rom_m` 0.617 against the set's own 0.51-1.29 spread.
 *
 * ## What this does, and the one thing it deliberately does not do
 *
 * It MARKS and WITHHOLDS. It does not repair.
 *
 * The raw stream is never altered -- that is the owner's rule, *"capture
 * faithfully, denoise offline"* -- and a repair in the ANALYSIS would be
 * allowed by it. The reason there is none is measured rather than argued, and
 * the measurement is in `ArtefactRuleAlternativesTest`: substituting an
 * out-of-range sample BEFORE the integration, by either of the two methods
 * (hold the last in-range reading, or interpolate between the neighbours),
 * moves the rep COUNT on nine of eleven committed captures that carry one,
 * and does not fix the deadlifts at all.
 *
 * Both halves of that matter:
 *
 * - The count moves because the substitution feeds the integrator, the
 *   integrator feeds the segmenter, and the segmenter decides what a rep is.
 *   field-43 set 4 goes from 9 detections to 5 under EITHER variant; field-42
 *   set 7 from 6 to 3 under hold-last; field-42 set 11 from 9 to 7. The two it
 *   does not move are field-42 set 2 and field-42 set 13. A rule that repairs
 *   a peak by moving the rep count has traded a wrong number for a wrong
 *   count, and the count is what the lifter checks.
 * - It does not fix the railed case. On field-43 set 5 the accelerometer is at
 *   its own full scale, so the sample carries no information about how large
 *   the real acceleration was; holding the last in-range reading over the
 *   2-to-4-sample run injects a sustained bias instead, and the set still
 *   publishes 974.5 W (hold) or 1336.6 W (interpolate) against 4347.4 W raw.
 *   On field-43 set 6 substitution makes the published peak WORSE, 2386.5 W to
 *   2869.1 W.
 *
 * So the figure is withheld instead. That is the owner's other rule: *"a figure
 * the analysis cannot bound is withheld or flagged, never published as if
 * measured."* Every other figure -- the rep count, `rom_m`, the means,
 * `velocityLoss_pct`, tempo compliance -- is bit-identical to what the same
 * capture published before this existed, because nothing upstream of them
 * moved.
 *
 * ## The bound, and where it comes from
 *
 * [BOUND_G] is 4.0 g of TOTAL support acceleration, which is the magnitude
 * [FrameTransform.accMagnitudeG] reads: gravity included, so a resting sensor
 * reads 1.0.
 *
 * - A lifted implement at rest reads 1 g. Net drive on a tempo-prescribed set
 *   stays well under 1 g -- every set in #290's table was prescribed `3010` or
 *   `4010` -- so total support acceleration stays under about 2 g. That is the
 *   ceiling the exercise imposes, not one this file chose.
 * - 4 g is twice it, so the bound admits three g of net drive: three times
 *   gravity, on a bar being pressed to a metronome. Nothing a lifter does to a
 *   loaded bar reaches it.
 * - [SENSOR_RANGE_G] is the other end. 16 g is the WT901BLECL's full scale,
 *   measured rather than read off a datasheet: field-43 set 5 carries samples
 *   at 15.999 g on both units, which is the reading saturating. A sample AT the
 *   range is worse than a large one, because it does not even bound the real
 *   acceleration from below.
 *
 * WHAT THE BOUND DOES NOT SAY. It does not say the sample is corrupt. A
 * deadlift's floor contact is a REAL mechanical event and it is above this
 * bound; what it is not is LIFT KINEMATICS, and a peak velocity or a peak power
 * taken across it describes the plate hitting the floor rather than the
 * lifter's drive. #255 records the other case -- a 16.191 g reading on a set
 * whose gyro never exceeds 5.494 dps, where transport corruption, decode
 * corruption and a mechanical event are all consistent with the bytes -- and
 * which of the two produced any given sample is a `[Field]` question this
 * cannot answer. The same treatment is correct either way: neither is the
 * lifter's drive.
 *
 * ## The threshold for withholding is ONE sample, and it is derived
 *
 * [countIn] is the artefact count over a rep's own span -- see [spanOf] -- and
 * [peaksWithheld] withholds on one.
 * That is not a fitted threshold: a peak is a MAXIMUM over its window, so one
 * sample above every real reading is sufficient to be the answer. A mean dilutes
 * an outlier by the window length; a max does not dilute it at all. Asking for
 * "mostly artefact" before withholding would leave the single-sample case --
 * the entire defect #290 and #255 report -- in place.
 *
 * WHAT IT DOES NOT GUARANTEE, and this is the half a reader must act on. The
 * rule withholds the peaks of the reps whose own span CONTAINS an artefact. An
 * artefact's residue reaches further than that -- [corruptedSpan] derives how
 * far, exactly, `ArtefactBoundTest` pins the interval it returns at both ends
 * of a stream, and `ArtefactRuleAlternativesTest`'s *"withholding over the
 * exact residue interval takes every peak on eight captures"* is the cost
 * table -- so a peak this rule keeps is not a peak it certifies. Five published figures in the committed
 * corpus survive it and are named rather than glossed:
 * `field-assistedpullup-3010-s37-set08`'s 407.4 W (#255's own headline: its one
 * 14.982 g sample is at index 4079, inside rep 5's eccentric, and the inflated
 * rep is rep 6, whose drive opens at 4083),
 * `field-deadlift-straight-5rep-s43-set04`'s 1203.1 W,
 * `field-deadlift-straight-5rep-s43-set06`'s 2386.5 W,
 * `field-pullup-3010-8rep-s42-set11`'s 634.3 W and
 * `field-cablerow-3010-8rep-s42-set09`'s 651.7 W. Withholding on
 * [corruptedSpan] instead reaches all five and withholds every peak on eight of
 * the eleven captures as well; that trade is raised, not taken.
 *
 * The other visible residue on these captures is the inflated per-rep `rom_m`,
 * which is issue #291 and is not this rule's to fix.
 */
object AccelArtefact {
    /**
     * Total support acceleration, in g, above which a sample is not lift
     * kinematics. See the class KDoc for the derivation.
     */
    const val BOUND_G = 4.0

    /**
     * The sensor's full scale, in g, measured from field-43 set 5's railed
     * samples rather than from a datasheet. A sample at or above this does not
     * bound the real acceleration even from below.
     */
    const val SENSOR_RANGE_G = 16.0

    /** Whether this one sample is above [BOUND_G]. */
    fun exceedsBound(sample: ImuSample): Boolean = FrameTransform.accMagnitudeG(sample) > BOUND_G

    /** Indices of every sample above [BOUND_G], in stream order. */
    fun indices(samples: List<ImuSample>): List<Int> = samples.indices.filter { exceedsBound(samples[it]) }

    /** How many samples of the whole stream are above [BOUND_G]. */
    fun count(samples: List<ImuSample>): Int = samples.count { exceedsBound(it) }

    /**
     * The whole index range a rep covers: from the first sample of its earlier
     * phase to the last sample of its later one, the turnaround included.
     *
     * `RepSpan` orders its two phases by the LIFT, not by index -- a
     * concentric-first lift takes the drive first -- so this takes the min and
     * the max rather than assuming which comes first. A rep counted on the
     * drive alone carries a placeholder eccentric span inside the drive's own
     * range, which contributes nothing.
     */
    fun spanOf(span: RepSpan): IntRange =
        minOf(span.eccStartIdx, span.conStartIdx)..maxOf(span.eccEndIdx, span.conEndIdx)

    /**
     * The samples an artefact at [index] could have reached: the ZUPT
     * inter-anchor interval it sits in, given [anchorIndices] ascending.
     *
     * **THE ALTERNATIVE RULE, MEASURED AND NOT SHIPPED.** `internal` because
     * its only readers are `ArtefactBoundTest`, which pins the interval case by
     * case, and `ArtefactRuleAlternativesTest`, which prices it; no production
     * path calls it.
     * It is kept because it is the evidence for a decision that would otherwise
     * be a bare assertion: this is the EXACT extent of an artefact's residue
     * under the ZUPT stage, and [peakEligible] deliberately uses the narrower
     * [spanOf] instead. Withholding on this interval withholds EVERY peak on
     * eight of the eleven committed captures that carry an artefact -- these
     * captures accept few anchors, so the interval is often the whole stream --
     * which trades a wrong number for no number at all across most of the
     * corpus. That is a product decision and it is raised rather than taken;
     * `ArtefactRuleAlternativesTest` carries the per-capture measurement.
     *
     * DERIVED FROM WHAT `applyZupt` DOES, not fitted to the corpus. Raw velocity
     * is a CUMULATIVE integral, so an out-of-range acceleration adds a step to
     * every later raw sample; the correction then subtracts an offset that is
     * linear between consecutive accepted anchors, with each anchor's value
     * read off that same raw integral. Three cases follow, and only the middle
     * one leaves a residue:
     *
     * - Before the anchor preceding the artefact: neither the raw integral nor
     *   the offset carries the step. Clean.
     * - Between that anchor and the next: the raw samples after the artefact
     *   carry the step and the offset ramps from a value without it to a value
     *   with it, so the step is only partly removed. CORRUPTED -- and the whole
     *   interval is, not just the part after the artefact, because the ramp
     *   subtracted from the samples BEFORE it is built from the later anchor
     *   that does carry the step.
     * - After the next anchor: both anchor values carry the step, so the offset
     *   carries it in full and it cancels. Clean.
     *
     * After the LAST anchor the offset is constant, so an artefact there
     * corrupts every sample to the end of the stream; that is the open interval
     * this returns when [index] lies past the last anchor.
     *
     * TWO THINGS IT DOES NOT COVER, stated rather than left to be discovered.
     * `RunawayDrift` runs AFTER the ZUPT pass and subtracts a mean over runs
     * that can straddle an anchor, so on a capture it corrects, the residue can
     * be spread outside the interval this names -- `RunawayDriftTest` pins
     * which captures need a pass and how many. And nothing here bounds the
     * residue's SIZE: the interval says where an artefact could have reached,
     * never how far it moved anything.
     */
    internal fun corruptedSpan(index: Int, anchorIndices: IntArray, size: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        if (anchorIndices.isEmpty()) return 0..(size - 1)
        var lo = 0
        var hi = size - 1
        for (a in anchorIndices) {
            if (a > index) {
                hi = a
                break
            }
            lo = a
        }
        return lo..hi
    }

    /** How many of [artefactIndices] lie inside [range]. */
    fun countIn(artefactIndices: List<Int>, range: IntRange): Int = artefactIndices.count { it in range }

    /**
     * Whether a rep whose own span carries [artefactSamples] of them may count
     * toward the set's peak. One is enough to withhold; see the class KDoc.
     */
    fun peaksWithheld(artefactSamples: Int): Boolean = artefactSamples > 0

    /**
     * The reps a SET's peak figures may be taken over: those whose own span
     * carries no artefact sample.
     *
     * THE ONE STATEMENT OF THE RULE, and the reason it is a function rather
     * than a `filter` at each call site. FIVE places take a SET-LEVEL peak over
     * a rep list. This KDoc said FOUR and was wrong; the sentence naming four is
     * deleted rather than adjusted, because the missing one is the site that
     * kept printing "Best 2.52 m/s" two lines above a corrected "Drive power:
     * peak 329 W":
     *
     * - `Exporters.setExport`'s `summary.peakConVel_mps`,
     * - `Exporters.setExport`'s `summary.peakPower_w`,
     * - `RecordScreen.powerSummary`'s drive-power line,
     * - `SessionDetailScreen.powerSummary`'s drive-power line,
     * - `RecordScreen.PeakVelocityChart`'s *"Best %.2f m/s"*, whose companion
     *   drawdown is [terminalPeakLossPct].
     *
     * Each would otherwise have held a copy of the rule. A set's published peak
     * and the peak the screen prints disagreeing about which reps they cover is
     * the *duplicate documentation drifts* class in arithmetic, and it had
     * already happened between the fourth site and the fifth.
     *
     * NOT A SET-LEVEL PEAK, and deliberately outside that list: the bar COLOURS
     * on `RecordScreen.PeakVelocityChart` and on `SessionDetailScreen`'s
     * explosive-lift chart. `velocityLossColor` shades each bar against the
     * maximum of the values it is handed, and those values are EVERY rep,
     * because one bar is drawn per rep. Narrowing that reference while still
     * drawing the withheld rep's bar would shade the tallest bar on screen as a
     * large loss against a smaller best, so the picture would contradict itself.
     * The bars are the per-rep figures as measured; the line under them is the
     * set-level claim, and the claim is what this narrows.
     *
     * A rep carrying `null` here -- an analysis stored before this rule existed
     * -- is KEPT. Absence is not zero: those reps were measured under a rule
     * that asked nothing about artefacts, and dropping them would make an old
     * set publish no peak at all rather than the peak it has always published.
     */
    fun peakEligible(reps: List<RepAnalysis>): List<RepAnalysis> =
        reps.filterNot { peaksWithheld(it.artefactSamples ?: 0) }

    /**
     * How far the set's LAST rep fell short of the best peak drive velocity the
     * set may publish, as a percentage of it: the second figure on the post-set
     * peak-velocity chart's summary line, `RecordScreen.PeakVelocityChart`'s
     * *"Best %.2f m/s - last rep -%.0f%% off best."* Issues #290 and #255.
     *
     * BOTH FIGURES OVER THE SAME POPULATION, which is why this is a function
     * and not two expressions on a Composable. The best is taken over
     * [peakEligible], so it agrees with `summary.peakConVel_mps` and with the
     * drive-power line drawn immediately above it.
     *
     * NULL WHERE THE SET'S OWN LAST REP IS WITHHELD, which is a decision rather
     * than a guard, and the one a reader should check. That rep's peak is a
     * figure this rule refuses to publish, so it is not one a drawdown can be
     * measured off -- and leaving it in the numerator against a narrowed
     * denominator prints a NEGATIVE percentage, because a set that ends on a
     * marked rep often ends on the largest peak in the set. field-42 set 7 is
     * exactly that: 1.084 m/s against an eligible best of 0.948.
     * `ArtefactBoundTest` pins both halves and `ArtefactPeakWithholdingTest`
     * carries the per-capture column, on which three of the eleven captures are
     * in this state.
     *
     * Also null where the chart already draws nothing: no rep at all, or a best
     * that is not positive, because a loss measured against zero is not a
     * percentage. A rep carrying `null` is KEPT, on [peakEligible]'s rule, so an
     * archived set still gets its figure.
     */
    fun terminalPeakLossPct(reps: List<RepAnalysis>): Double? {
        val last = reps.lastOrNull() ?: return null
        if (peaksWithheld(last.artefactSamples ?: 0)) return null
        val best = peakEligible(reps).maxOfOrNull { it.peakConVelMps } ?: return null
        if (best <= 0) return null
        return (1.0 - last.peakConVelMps / best) * 100.0
    }
}
