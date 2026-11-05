package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which instant the rest after a set runs from (#284, hazard H1).
 *
 * Every track here is synthetic and built from the words the app writes:
 * `VoiceMilestonePolicy.repMilestone`'s `Rep N` / `Last rep` / `Done` for a
 * lifter tapping a six-rep slot, a cadence's `Done`, the timed voice's `Time`.
 * No manual-set capture is committed (see [ManualDoneBoundTest]), so no field
 * track could stand in for one.
 */
class RestStartTest {
    private val endedAtMs = 100_000L

    /** One `+1 REP` tap every two seconds from 10 s, as many as [n]. */
    private fun taps(n: Int) = List(n) { 10_000L + it * 2_000L }

    /** The words a manual six-rep slot speaks at each tap, with the cues on. */
    private fun milestones(marks: List<Long>) = marks.mapIndexed { i, at ->
        val rep = i + 1
        VoiceCue(
            at,
            when {
                rep == 6 -> SetEnd.DONE
                rep == 5 -> "Last rep"
                else -> "Rep $rep"
            },
        )
    }

    @Test
    fun `a guided set's rest runs from the cadence's Done`() {
        val cues = listOf(VoiceCue(20_000L, "Down"), VoiceCue(30_000L, SetEnd.DONE))
        assertEquals(30_000L, RestStart.of(cues, cadenceGuided = true, taps(6), null, endedAtMs))
    }

    @Test
    fun `a hold's rest runs from its Time, and a deciding release outranks it`() {
        val cues = listOf(VoiceCue(40_000L, SetEnd.TIME_UP))
        assertEquals(40_000L, RestStart.of(cues, cadenceGuided = false, emptyList(), null, endedAtMs))
        assertEquals(38_000L, RestStart.of(cues, cadenceGuided = false, emptyList(), 38_000L, endedAtMs))
    }

    /** A sensor-counted set speaks no `Done` and writes no mark: the write instant. */
    @Test
    fun `a set nothing called over and nobody tapped rests from its write`() {
        assertEquals(endedAtMs, RestStart.of(emptyList(), cadenceGuided = false, emptyList(), null, endedAtMs))
    }

    /**
     * A lifter who stopped at the planned count: the milestone and the last
     * tap are one instant, and the rest runs from it.
     */
    @Test
    fun `a manual set stopped at the plan rests from the tap that reached it`() {
        val marks = taps(6)
        assertEquals(marks.last(), RestStart.of(milestones(marks), cadenceGuided = false, marks, null, endedAtMs))
    }
}
