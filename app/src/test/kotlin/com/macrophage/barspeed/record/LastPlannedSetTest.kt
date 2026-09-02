package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What tapping START NEXT SET does to the queue, and what it does when there
 * is nothing left in it (#195).
 *
 * CHARACTERIZATION ONLY IN THIS COMMIT. Three of these tests describe the
 * behaviour that ships today, including the one this issue is about: on the
 * last planned set `advancedState` writes `Stage.READY` and leaves
 * `queueIndex` where it was, so the slot just recorded becomes the current
 * slot again. They are here to make the change visible as a difference rather
 * than to endorse it, and the last-slot pins are reversed by the red
 * differentials in this branch's c2.
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
     * TODAY'S BEHAVIOUR AND THE DEFECT (#195), pinned so the fix is a visible
     * difference. The last planned set is re-armed: the stage goes back to
     * READY on the slot just recorded, and `startNextSet` calls `beginSet` in
     * the same frame, so the tap runs the finished set again.
     */
    @Test
    fun `the last planned set is re-armed on the slot it just recorded`() {
        val out = advancedState(resting(twoSets, queueIndex = 1))
        assertEquals(Stage.READY, out.stage)
        assertEquals(1, out.queueIndex, "queueIndex moved, so the re-arm is gone")
        assertEquals("bench_press", out.currentSlot?.exercise?.id)
    }

    /**
     * WHAT THE TAP COSTS, which #195 asks be established before anything is
     * designed. The re-armed slot is the recorded set's own slot with its
     * frozen declarations intact and `isAddedSet` false, so the row it writes
     * carries the same prescription and is indistinguishable in the export
     * from the planned set it duplicates. The lifter's standing statements are
     * cleared on the way, so it does not even come back at the load the set
     * was actually run with.
     */
    @Test
    fun `the re-armed slot still carries the plan's prescription and is not marked added`() {
        val out = advancedState(resting(twoSets, queueIndex = 1, statedLoadKg = 62.5))
        val slot = out.queue[1]
        assertEquals(60.0, slot.plannedLoadKg)
        assertEquals(8, slot.plannedReps)
        assertTrue(!slot.isAddedSet, "a re-armed slot would at least be distinguishable if it were marked")
        assertNull(out.statedLoadKg, "the load the set was run at is dropped")
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
