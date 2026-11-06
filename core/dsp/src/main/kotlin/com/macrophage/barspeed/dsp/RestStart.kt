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
 * owns which instant on the record is handed to it as the set-over instant.
 *
 * ## The milestone is not the end of the set
 *
 * On a set the lifter counts by tapping, the tap at the planned count speaks
 * `Done` -- `VoiceMilestonePolicy.repMilestone`'s milestone, the same string a
 * cadence's terminal call is. [SetEnd.calledOver] reads any terminal word, so
 * it read the milestone as the set ending, and the rest seed it fed had two
 * faults (#284, H1):
 *
 * - A lifter who tapped past the plan had their rest start at the planned
 *   tap, before the reps they went on to do.
 * - The audio-cue toggle moved it. With the cues off no milestone is written
 *   and the rest started at the write instant instead -- a display toggle
 *   deciding a stored instant.
 *
 * So the set-over instant is [SetEnd.of]'s, which admits `Done` only where a
 * cadence spoke it (#285), and where no cue on the record called the set over
 * on a set no cadence ran, it is the lifter's LAST TAP: the last `+1 REP`
 * instant is when they stopped lifting, with the cues on or off, short of the
 * plan or past it. Where the lifter stopped at the plan with the cues on, the
 * milestone and the last tap are one instant, so that set's rest is
 * unchanged.
 *
 * WHY NOT A NEW WORD FOR THE MILESTONE, the other shape #284 named. Giving
 * the manual milestone a non-terminal word changes what the lifter hears and
 * mends only sets recorded after it; every manual set already on disk carries
 * `Done` in its cue track, and the export and analysis read stored tracks.
 * Deciding by who spoke the word reads those rows correctly too.
 *
 * Marks on a guided set are the guide's calls and are never read here: a
 * cadence always leaves a terminal word on the record (`SetEnd.terminalCall`
 * writes `Set ended` where it said no `Done`). A sensor-counted set writes no
 * mark -- a correction tap is recorded as the count, not as an instant -- so
 * it rests from its write, as before.
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
    fun of(
        cues: List<VoiceCue>,
        cadenceGuided: Boolean,
        tapMarks: List<Long>,
        sensorEndAtMs: Long?,
        endedAtMs: Long,
    ): Long = RestClockPolicy.startedAtMs(
        setOverCueAtMs =
        (SetEnd.of(cues, cadenceGuided) as? SetEnd.Cued)?.atMs
            ?: tapMarks.lastOrNull()?.takeIf { !cadenceGuided },
        sensorEndAtMs = sensorEndAtMs,
        endedAtMs = endedAtMs,
    )
}
