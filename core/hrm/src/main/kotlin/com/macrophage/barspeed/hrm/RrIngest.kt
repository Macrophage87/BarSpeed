package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample

/**
 * Which beats a heart-rate notification brought.
 *
 * This exists because the decision has to be somewhere a test can reach. Its
 * only caller is `RecordViewModel`, in `:app`, which has no test source set at
 * all -- a rule written there is verified by reading it and by nothing else, and
 * inverting it would red nothing in CI. Here it is pure, and the batch form is a
 * fold of the per-notification form rather than a second implementation, so a
 * fixture-driven test exercises the code the app runs.
 *
 * THE RULE: a notification whose R-R list is identical to that of the last
 * notification that carried one brought no new beat. Otherwise every interval
 * it carries is new. Issue #81.
 *
 * Bit-identity with the immediate predecessor, and nothing else -- no
 * threshold, no constant, no rate term. It is the least aggressive rule that
 * removes an impossibility: on the worn control the reported intervals sum to
 * more time than the sets lasted, and a heart cannot beat for longer than the
 * time available. RMSSD is a function of SUCCESSIVE DIFFERENCES and every
 * repeat contributes one of exactly zero, so the session figure was deflated.
 * The impossibility is pinned by `FieldHrDuplicationTest.as ingested the
 * reported intervals claim more time than the sets lasted`.
 *
 * The SIZE of the deflation in the published session figure is pinned by no
 * test at all, and that is worth saying rather than implying. The sixteen
 * expectations in `HrvWornControlDischargeTest` are per-SET figures and they
 * hold only where each lands after the rule, not the distance it moved; the
 * published figure is a different quantity, computed over the session. It is
 * stated with its denominator in the schema's version log and nowhere else, so
 * treat that entry as the record and this sentence as a pointer to it.
 *
 * WHAT IT COSTS, stated because it is large and does not shrink with care: on
 * the worn control the rule removes 327 notifications and a large share of them
 * are genuine consecutive beats that happened to carry equal intervals. Two
 * estimators disagree about how large and neither is authoritative. Both, and
 * the counts they divide, are pinned by `FieldHrDuplicationTest.about 141 of
 * the 327 are genuine consecutive beats, not repeats`, `.what the rule costs
 * rises steeply with heart rate` and `.the worn control carries 327
 * adjacent-identical notifications of 1930`.
 *
 * Two beats reported in successive notifications with the same interval cannot
 * be told from one beat reported twice, by this or by anything else operating
 * on this stream. The rule is far blunter than the corrected statistic makes it
 * look, and the reason the statistic survives being this blunt is arithmetic:
 * RMSSD depends on the diff COUNT, which the elapsed-time budget pins as
 * repeats-minus-lost-beats regardless of how the 327 divide. Do not read the
 * accuracy of the number as precision in the rule.
 *
 * WHERE IT PAYS AND WHERE IT DOES NOT. The error it introduces is bounded by
 * the tie rate, which goes as q/(sigma*sqrt(2)) and so falls as VARIABILITY
 * rises. The error it removes goes as the re-sent fraction, which rises as the
 * HEART RATE falls against a fixed cadence. Two different variables, and it
 * matters that they are: at and above the cadence there is almost nothing left
 * to re-send and the rule is close to pure cost, so it pays on a rest-heavy
 * session and does not on a short, hard one. A single aggregate hides that
 * entirely. The profile by heart rate is pinned by
 * `FieldHrDuplicationTest.what the rule costs rises steeply with heart rate`,
 * with its grouping convention and its estimator named there.
 *
 * The claim for the resting end rests on two things, and when it was written
 * only one of them was measured. Both are now, and the second came out
 * against it. Measured all along: the density law's level, pinned by
 * `FieldHrDuplicationTest.lag one is enriched three and a half fold over every
 * other lag` against a parameter-free Laplace prediction, and cross-checked by
 * `.above 120 bpm, where re-sends are near-suppressed, the tie rate matches the
 * law`. FORMERLY ASSUMED, NOW MEASURED, AND THE ASSUMPTION WAS WRONG: that
 * resting variability is several times this capture's. Across the two 0.1.40
 * captures, paired against the set each rest period precedes, the median
 * rest-to-set ratio is 1.26 and four of twenty rest streams are LESS variable
 * than their own set. The benefit at a resting rate follows from the HEART
 * RATE and the residual cost from SIGMA; they are not the same kind of claim
 * and must not be quoted as though both came from the rate.
 *
 * THE CONCLUSION SURVIVES BECAUSE IT WAS ALREADY STATED AGAINST THE
 * PESSIMISTIC CASE: the benefit dominates across the range of sigma
 * considered, INCLUDING where resting variability equals this capture's. The
 * measurement lands inside that range rather than outside it, so what changes
 * is the wording and not the finding -- but the wording was a claim, and it
 * was false. Pinned by `FieldHrRestingBandTest.inter-set rest is not several
 * times more variable than the set it precedes`.
 *
 * No WORN capture held here reaches a RESTING heart rate, which is a different
 * statement from reaching a slow one. Session 26's range and the absence of
 * any sample below 70 bpm are pinned in `FieldHrTrustDischargeTest.the worn
 * control contains no resting heart rate at all`; the two 0.1.40 captures do
 * go below it, to 67 bpm across 16 of their 8,044 samples, pinned in
 * `FieldHrRestingBandTest.the captures reach 67 bpm and no lower`. That is
 * eleven beats below session 26's floor, not the thirty-two that would reach
 * the figure the unworn strap published. The unworn capture goes lower still,
 * but it is a strap on a table reporting a detector holding its own last
 * value, so it is not evidence about a resting heart at all. Issue #83.
 *
 * WHY A RULE FOR THIS CANNOT LIVE IN [Hrv]: two equal values inside ONE
 * notification are two beats the strap queued; two equal values in SUCCESSIVE
 * notifications may be one beat reported twice. Only the notification boundary
 * separates those cases, and [Hrv] is handed a flat `List<Double>` with the
 * boundaries already gone. This is a property of the FORMAT and of [HrSample] --
 * `rrIntervalsMs` is a list, and the BLE characteristic defines it as every
 * interval since the last notification. It is NOT something this corpus
 * demonstrates: 0 of the 10,228 notifications across all 60 committed
 * captures carry more than one interval, so the multi-interval case is unexercised by
 * every capture held here and is a `[Field]` question, not a finding.
 *
 * WHAT ARRIVAL TIME DOES NOT DO, recorded because issue #81 proposes it and it
 * does not survive contact with the capture: adjacent notifications are one
 * cadence tick apart whether or not a beat happened in between -- that is what
 * makes them adjacent -- so the gap carries no signal about it. On the worn
 * control the gap distributions for identical and for distinct adjacent pairs
 * are indistinguishable, and the separation between the median gap and the
 * median interval is an order of magnitude smaller than the arrival jitter on
 * it, so a rule keyed on the gap decides by coin flip at the capture's own
 * median. The cadence envelope those statements rest on is pinned by
 * `FieldHrDuplicationTest.the notification cadence is 500 ms give or take six`.
 * The gap comparison itself is pinned NOWHERE, and is recorded here as a
 * rejected design rather than as a result.
 */
object RrIngest {
    /**
     * The beats [sample] carries that [previous] did not.
     *
     * [previous] is the last notification that CARRIED intervals, not simply the
     * last notification. A notification with the R-R flag clear says nothing
     * about beats, so letting it reset the comparison would turn one beat into
     * two whenever the strap went briefly silent inside a repeat run.
     *
     * It is also the last notification seen on the STREAM, not the last one
     * accumulated into any particular list: a beat that completed before a
     * session began is not new merely because that session's accumulator is, so
     * a caller starting a fresh accumulation must not reset what it passes here.
     *
     * Only the immediate predecessor is compared. A heart returning to an
     * interval it held a moment ago is ordinary, and treating equality at any
     * distance as duplication would delete real beats wholesale.
     */
    fun newBeats(previous: HrSample?, sample: HrSample): List<Double> =
        if (previous != null && sample.rrIntervalsMs == previous.rrIntervalsMs) {
            emptyList()
        } else {
            sample.rrIntervalsMs
        }

    /**
     * What [previous] must become once [sample] has been accounted for.
     *
     * A notification carrying no intervals leaves it alone: the R-R flag being
     * clear says nothing about beats, so letting such a notification become the
     * reference would make the next repeat look new and turn one beat into two.
     *
     * This lives here rather than at each call site because there are two call
     * sites -- the fold below and the streaming collector in `RecordViewModel`
     * -- and `:app` has no test source set. Written out twice, the copy in
     * `:app` would be unpinned and the seam would be doing half its job. One
     * function, so one test kills both.
     */
    fun nextPrevious(previous: HrSample?, sample: HrSample): HrSample? =
        if (sample.rrIntervalsMs.isNotEmpty()) sample else previous

    /**
     * The beats a whole stream carries, in order.
     *
     * This signature is the stable one. It already carries the notification
     * boundaries and their order, which is everything any rule for #81 can need,
     * so it does not change when the rule does. That is deliberate: the
     * fixture-driven discharges and the differentials for #81 are written
     * against this, so they can fail before the fix and pass after it without
     * being rewritten in between. A test the gating commit has to edit is not a
     * gate.
     */
    fun newBeats(samples: List<HrSample>): List<Double> {
        val out = mutableListOf<Double>()
        var previous: HrSample? = null
        for (sample in samples) {
            out += newBeats(previous, sample)
            previous = nextPrevious(previous, sample)
        }
        return out
    }
}
