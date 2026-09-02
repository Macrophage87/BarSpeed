package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What tapping START NEXT SET does to the queue, and what it does when there
 * is nothing left in it (#195).
 *
 * RED IN THIS COMMIT. `the last planned set is not re-armed` fails here and
 * passes at c3. The two pins this file carried describing the re-arm as
 * correct -- `the last planned set is re-armed on the slot it just recorded`
 * and `the re-armed slot still carries the plan's prescription and is not
 * marked added` -- are deleted rather than reworded: they were c0
 * characterization of the defect and they are now false about what this app
 * should do. What they measured that is still worth having, that a re-armed
 * slot is invisible in the export, is kept below as a statement about the
 * SLOT.
 *
 * REACHABLE FOR [AppendedSlotTest]'s REASON -- `app/build.gradle.kts` pins the
 * test JVM to 21, so a `:app` test may load a `:core:model` type. Nothing here
 * touches Android, Room or a sensor: `advancedState` is a pure function over
 * [RecordState] and was made `internal` for this file, as `appendedState` was
 * for #177's.
 */
class LastPlannedSetTest {
    @Test
    fun `a set with one to come advances to it`() {
        val out = advancedState(resting(twoSets, queueIndex = 0))
        assertEquals(1, out.queueIndex, "the queue did not move on")
        assertEquals(Stage.READY, out.stage)
        assertEquals("bench_press", out.currentSlot?.exercise?.id)
    }

    /**
     * The lifter's in-rest edits are baked into the slot that is about to run.
     *
     * Pinned here because the fix must not disturb it: it is the whole reason
     * `advancedState` exists, and the branch it lives on is the one being left
     * alone.
     */
    @Test
    fun `an in-rest load edit is written into the set that is about to run`() {
        val out = advancedState(resting(twoSets, queueIndex = 0, statedLoadKg = 62.5))
        assertEquals(62.5, out.queue[1].loadKg)
        assertEquals(60.0, out.queue[1].plannedLoadKg, "the frozen prescription was overwritten")
    }

    /**
     * An ad-hoc session has no queue at all, and re-arming READY is how it
     * runs its second and every later set. This is the branch that must
     * survive the fix.
     */
    @Test
    fun `an ad-hoc session comes back to READY with the typed statements cleared`() {
        val out = advancedState(adHoc.copy(statedLoadKg = 40.0, statedReps = 5))
        assertEquals(Stage.READY, out.stage)
        assertNull(out.statedLoadKg)
        assertNull(out.statedReps)
    }

    /**
     * THE DEFECT (#195). The last planned set must not be re-armed: there is
     * nothing to advance to, so advancing does nothing and the lifter stays on
     * the rest screen. `startNextSet` calls `beginSet` in the same frame, so a
     * state that comes back READY here is a state that records the finished
     * set a second time.
     */
    @Test
    fun `the last planned set is not re-armed`() {
        val before = resting(twoSets, queueIndex = 1)
        val out = advancedState(before)
        assertEquals(before, out, "the finished slot was re-armed")
    }

    /**
     * The way to get another set is #177's append, and it still works in one
     * pass: appending puts a slot after the last one, and START then runs
     * THAT.
     *
     * Green before the fix as well as after -- the append gives the queue a
     * next slot, which is the branch that was already correct. It is here
     * because it is the half of the contract the fix must not break.
     */
    @Test
    fun `a set appended after the last one is what the next start runs`() {
        val appended = assertNotNull(appendedState(resting(twoSets, queueIndex = 1)))
        val out = advancedState(appended)
        assertEquals(Stage.READY, out.stage)
        assertEquals(2, out.queueIndex)
        assertTrue(out.currentSlot?.isAddedSet == true, "START ran something the lifter did not append")
        assertNull(out.currentSlot?.plannedLoadKg, "an appended set carries a prescription nobody wrote")
    }

    /**
     * WHAT THE TAP COSTS TODAY, which #195 asks be established before
     * anything is designed, kept as the reason the fix is worth making. The
     * re-armed slot is the recorded set's own slot with `plannedLoadKg`,
     * `plannedReps` and `isAddedSet = false` intact, so the row it writes is
     * indistinguishable in the export from the planned set it duplicates --
     * unlike an appended one, which the test above pins as marked and
     * unprescribed.
     */
    @Test
    fun `the finished slot is not marked in any way an export could see`() {
        val slot = twoSets[1]
        assertEquals(60.0, slot.plannedLoadKg)
        assertEquals(8, slot.plannedReps)
        assertTrue(!slot.isAddedSet)
    }
}

private val bench = ExerciseDef("bench_press", "Bench press")

private fun lastSetSlot(setIndexInExercise: Int) = PlannedSlot(
    exercise = bench,
    geometry = SetGeometryPolicy.describe(bench, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = 2,
    reps = 8,
    durationS = null,
    plannedReps = 8,
    plannedDurationS = null,
    loadKg = 60.0,
    plannedLoadKg = 60.0,
    tempo = "3010",
    plannedTempo = "3010",
    side = null,
    implementCount = 1,
    exerciseNotes = null,
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 150,
    prepS = 0,
    sensors = 1,
    isExerciseChange = setIndexInExercise == 0,
    warmup = false,
    isAddedSet = false,
)

private val twoSets = listOf(lastSetSlot(0), lastSetSlot(1))

/** Mid-rest after the slot at [queueIndex]. */
private fun resting(queue: List<PlannedSlot>, queueIndex: Int, statedLoadKg: Double? = null) = RecordState(
    stage = Stage.RESTING,
    queue = queue,
    queueIndex = queueIndex,
    adHoc = false,
    statedLoadKg = statedLoadKg,
)

private val adHoc = RecordState(stage = Stage.RESTING, queue = emptyList(), queueIndex = 0, adHoc = true)
