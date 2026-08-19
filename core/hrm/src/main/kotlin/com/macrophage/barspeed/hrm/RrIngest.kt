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
 * WHAT IT DOES TODAY: every reported interval is a beat. That is a faithful
 * statement of the rule already in force at the call site, moved rather than
 * changed, and it is wrong -- issue #81. This strap re-sends its last completed
 * R-R whenever no new beat has arrived, so part of the ingested series is the
 * same beat counted again, and RMSSD is a function of SUCCESSIVE DIFFERENCES,
 * every one of which a repeat contributes as exactly zero. On the worn control
 * the reported intervals sum to 110% of the time the sets lasted, which is not
 * something a heart can do.
 *
 * WHY A RULE FOR THIS CANNOT LIVE IN [Hrv]: two equal values inside ONE
 * notification are two beats the strap queued; two equal values in SUCCESSIVE
 * notifications may be one beat reported twice. Only the notification boundary
 * separates those cases, and [Hrv] is handed a flat `List<Double>` with the
 * boundaries already gone. This is a property of the FORMAT and of [HrSample] --
 * `rrIntervalsMs` is a list, and the BLE characteristic defines it as every
 * interval since the last notification. It is NOT something this corpus
 * demonstrates: 0 of the 2,184 notifications across all 20 committed captures
 * carry more than one interval, so the multi-interval case is unexercised by
 * every capture held here and is a `[Field]` question, not a finding.
 *
 * WHAT ARRIVAL TIME DOES NOT DO, recorded because issue #81 proposes it and it
 * does not survive contact with the capture: adjacent notifications are one
 * cadence tick apart whether or not a beat happened in between -- that is what
 * makes them adjacent -- so the gap carries no signal about it. Measured on the
 * worn control, the gap distributions for identical and for distinct adjacent
 * pairs are the same to a correlation of 0.02, and the median gap and the median
 * interval differ by 13 ms against arrival jitter spanning 350-700 ms. A rule
 * keyed on the gap decides by coin flip at the capture's own median.
 */
object RrIngest {
    /**
     * The beats [sample] carries.
     *
     * Today the answer does not depend on anything that came before, which is
     * why this takes one notification and not two. Fixing #81 means it must
     * depend on the preceding notification, and the parameter for that arrives
     * with the rule that uses it -- adding it here, unused, would be a
     * suppressed detekt finding standing in for a design that does not exist
     * yet.
     */
    fun newBeats(sample: HrSample): List<Double> = sample.rrIntervalsMs

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
    fun newBeats(samples: List<HrSample>): List<Double> = samples.flatMap(::newBeats)
}
