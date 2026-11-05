package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.RestClockPolicy
import com.macrophage.barspeed.model.VoiceCue

/**
 * The instant the rest after a set runs from, from what the set's own record
 * holds (#284, hazard H1).
 *
 * Here rather than in `RecordViewModel.restStartedFrom`, which composed
 * [SetEnd.calledOver] with [RestClockPolicy.startedAtMs] in a module no test on
 * the CI path reaches: the instant is written onto the row as
 * `restStartedAtMs` and bounds the next set's `rest_before_hrm` window, so it
 * is a stored figure, and the choice of which cue seeds it is a decision.
 *
 * [RestClockPolicy] owns the ordering of the three instants it is handed; this
 * owns which cue on the record is handed to it as the set-over instant.
 */
object RestStart {
    /**
     * @param cues the set's frozen cue track.
     * @param cadenceGuided whether a cadence ran on the set --
     *   `SetTargets.cadenceGuided`, the input [SetEnd.of] takes.
     * @param tapMarks the set's rep marks, oldest first: the lifter's `+1 REP`
     *   taps on a set they counted, the guide's calls on a guided one, empty on
     *   a set the sensor counted.
     * @param sensorEndAtMs a release that decided a hold's recorded seconds, or null.
     * @param endedAtMs the instant the set's write froze.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun of(
        cues: List<VoiceCue>,
        cadenceGuided: Boolean,
        tapMarks: List<Long>,
        sensorEndAtMs: Long?,
        endedAtMs: Long,
    ): Long = RestClockPolicy.startedAtMs(
        setOverCueAtMs = (SetEnd.calledOver(cues) as? SetEnd.Cued)?.atMs,
        sensorEndAtMs = sensorEndAtMs,
        endedAtMs = endedAtMs,
    )
}
