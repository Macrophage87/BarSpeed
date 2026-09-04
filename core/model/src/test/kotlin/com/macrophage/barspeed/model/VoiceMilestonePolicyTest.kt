package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterization pins on the in-set voice's two decisions, lifted out of
 * `RecordViewModel` into [VoiceMilestonePolicy].
 *
 * NO RED WAS SHOWN and none was available: the lift changes no behaviour, and
 * before it the two decisions lived in `:app` where nothing runs on them.
 * Every case here is written from the code that was moved, so it pins what the
 * app already did rather than something new. What makes them able to fail is
 * recorded in the commit body as a mutation table.
 *
 * WHAT IS NOT PINNED HERE. Whether cues are switched on, and whether the
 * sensor rather than the guide is the counter, are the caller's gates and stay
 * in `RecordViewModel`; no test in this repository reaches them.
 *
 * ONE MUTATION SURVIVES and no case here kills it: deleting `plannedReps > 1`
 * from [VoiceMilestonePolicy.repMilestone]. The only rep count that clause
 * separates is `plannedReps` 1 with `repCount` 0, and the zero guard above it
 * has already returned null for that. It is reachable at a negative
 * `repCount`, which is not a count this app produces. The clause is kept
 * because this file's commit lifts the two decisions unchanged.
 */
class VoiceMilestonePolicyTest {
    @Test
    fun `a whole second of a moving phase is spoken`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 1.0, Phase.ECCENTRIC, 0)
        assertEquals("1", next.speak)
        assertEquals(1, next.second)
        assertEquals(Phase.ECCENTRIC, next.phase)
    }

    @Test
    fun `elapsed seconds are truncated, not rounded`() {
        assertEquals("1", VoiceMilestonePolicy.phaseCount(Phase.CONCENTRIC, 1.99, Phase.CONCENTRIC, 0).speak)
    }

    @Test
    fun `nothing is spoken before the first whole second`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 0.99, Phase.ECCENTRIC, 0)
        assertNull(next.speak)
        assertEquals(0, next.second)
    }

    @Test
    fun `the second already spoken is not spoken twice`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 2.4, Phase.ECCENTRIC, 2)
        assertNull(next.speak)
        assertEquals(2, next.second)
    }

    @Test
    fun `the next second of the same phase is spoken`() {
        assertEquals("3", VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 3.0, Phase.ECCENTRIC, 2).speak)
    }

    @Test
    fun `a phase change clears the spoken second`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.CONCENTRIC, 1.2, Phase.ECCENTRIC, 4)
        assertEquals("1", next.speak)
        assertEquals(1, next.second)
        assertEquals(Phase.CONCENTRIC, next.phase)
    }

    @Test
    fun `a non-moving phase says nothing and still moves the counted phase`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.IDLE, 9.0, Phase.CONCENTRIC, 4)
        assertNull(next.speak)
        assertEquals(Phase.IDLE, next.phase)
        assertEquals(0, next.second)
    }

    @Test
    fun `a moving phase re-entered after idle speaks its first second again`() {
        val idle = VoiceMilestonePolicy.phaseCount(Phase.IDLE, 0.3, Phase.ECCENTRIC, 4)
        val back = VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 1.0, idle.phase, idle.second)
        assertEquals("1", back.speak)
    }

    @Test
    fun `an elapsed that falls back under one second says nothing`() {
        val next = VoiceMilestonePolicy.phaseCount(Phase.ECCENTRIC, 0.5, Phase.ECCENTRIC, 3)
        assertNull(next.speak)
        assertEquals(3, next.second)
    }

    @Test
    fun `a second that goes backwards inside one phase is spoken`() {
        assertEquals("1", VoiceMilestonePolicy.phaseCount(Phase.CONCENTRIC, 1.0, Phase.CONCENTRIC, 3).speak)
    }

    @Test
    fun `a rep on a set with no planned count is Rep N`() {
        assertEquals("Rep 4", VoiceMilestonePolicy.repMilestone(4, 3, null))
    }

    @Test
    fun `the planned count is Done`() {
        assertEquals("Done", VoiceMilestonePolicy.repMilestone(5, 4, 5))
    }

    @Test
    fun `one before the planned count is Last rep`() {
        assertEquals("Last rep", VoiceMilestonePolicy.repMilestone(4, 3, 5))
    }

    @Test
    fun `a planned single is Done and never Last rep`() {
        assertEquals("Done", VoiceMilestonePolicy.repMilestone(1, 0, 1))
    }

    @Test
    fun `the first rep of a planned pair is Last rep`() {
        assertEquals("Last rep", VoiceMilestonePolicy.repMilestone(1, 0, 2))
    }

    @Test
    fun `a rep past the planned count is Rep N`() {
        assertEquals("Rep 6", VoiceMilestonePolicy.repMilestone(6, 5, 5))
    }

    @Test
    fun `the rep already announced says nothing`() {
        assertNull(VoiceMilestonePolicy.repMilestone(3, 3, 5))
    }

    @Test
    fun `a count of zero says nothing`() {
        assertNull(VoiceMilestonePolicy.repMilestone(0, 0, 5))
    }

    @Test
    fun `a count that falls back to zero says nothing`() {
        assertNull(VoiceMilestonePolicy.repMilestone(0, 3, 5))
    }
}
