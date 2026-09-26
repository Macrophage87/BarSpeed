package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample

/**
 * A session's HRV computed from its STORED heart-rate windows (#62 half (b)).
 *
 * WHAT THE CLOSE COMPUTES. The session close stores [Hrv.rmssdMs] over the
 * beats `RecordViewModel` accumulated while the session ran: every
 * notification in READY, IN_SET and RESTING, passed through
 * [RrIngest.newBeats] against the last notification seen on the stream. That
 * list lives in memory, so a session that never reaches the close -- the
 * lifter left without finishing, or the process died -- stores no HRV.
 *
 * WHAT IS STORED. Each of those windows reaches storage on its own: IN_SET
 * as the set's `hrm` stream, READY and each inter-set rest as the following
 * set's `rest_before_hrm` stream, and the window after the last set as
 * `rest_after_hrm`, which only the close writes. The caller hands the
 * windows in session order; this joins them in the order given and does not
 * sort them.
 *
 * WHAT THIS DOES. It rebuilds one notification stream from the windows and
 * runs the close's own two steps over it: [RrIngest.newBeats], then
 * [Hrv.rmssdMs]. It is not a second RMSSD and not a second de-duplication.
 * Two things make the joined stream the right input:
 *
 *  - A notification already seen in an earlier window is dropped. Since
 *    #178, a rest window begins with a COPY of the tail of the set before it
 *    -- the same timestamp, bpm and intervals -- so joining the windows as
 *    they are stored would count those beats twice and splice a jump back in
 *    time into the successive differences.
 *  - The ingest is folded over the joined stream, not restarted per window.
 *    A strap re-send that straddles two windows is one beat, as it was to
 *    the live collector, whose reference is never reset between stages.
 *
 * WHAT IT CANNOT RECOVER. Beats that reached no stored window are not in
 * it: a window lost with the process, and the final rest, which an
 * unfinished session never has. The stored intervals are rounded to 0.1 ms
 * by `HrCsv`; the close used the strap's own values. So a figure from here
 * is computed from stored streams, not from the intervals the close
 * received, and it is not guaranteed to equal the close's figure.
 *
 * MEASURED, NOT GUARANTEED. On the six closed strap sessions field-40 to
 * field-45, this computation over each set's `rest_before_hrm` then `hrm`
 * window, in set order and with the final rest left out, came within 0.8
 * percent of the figure each close stored. That was measured with a Python
 * port of this rule, run read-only over the field captures; the port's
 * [RrIngest] and [Hrv] reproduce the sixteen per-set figures and the
 * 1603-beat count `HrvWornControlDischargeTest` pins.
 */
object SessionHrv {
    /**
     * RMSSD, in ms, over the beats [windows] carry once joined, or null where
     * fewer than [Hrv.DEFAULT_MIN_INTERVALS] successive differences survive
     * -- never 0.
     *
     * [windows] must be one session's stored heart-rate windows in session
     * order. A notification is "already seen" when an earlier one in the
     * joined stream is equal to it in timestamp, bpm and intervals: the
     * [HrSample] data-class equality.
     */
    fun rmssdMs(windows: List<List<HrSample>>): Double? = Hrv.rmssdMs(RrIngest.newBeats(windows.flatten().distinct()))
}
