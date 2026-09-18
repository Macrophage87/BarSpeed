package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Implement
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.SkippedSet
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [skippedState] leaves behind when a lifter drops an upcoming prescribed
 * set (#300).
 *
 * `SkipSetControl.target` decides WHICH slot goes and is pinned in
 * `:core:model`. It cannot see this half: the queue edit, the skip record and
 * the rest screen's boxes are [RecordState] and [PlannedSlot], which live in
 * `:app`. At round 1 of the review nothing in any test source set named
 * `skippedState`, `skipSetTarget` or `skipUpcomingSet`, so every decision below
 * was compile-gated only.
 *
 * REACHABLE FOR [AppendedSlotTest]'s REASON: `app/build.gradle.kts` pins the
 * test JVM to 21, so a test in this module may load `:core:model`'s types.
 *
 * THE DIFFERENTIALS are the four stated values inside a block, and each says so
 * at itself. Everything else here passes at the commit that adds it and is
 * named where it sits, because a green tick inside a red commit is not
 * coverage.
 *
 * MUTATED, NOT ASSERTED TO WORK. Eight mutations, one build each, forward from
 * the finished tree at `fdc602d1c85e2977d8ddc5dea8b44259c904664f` and reverted
 * after, by `./gradlew :app:testDebugUnitTest --rerun-tasks --no-build-cache`,
 * whose control is 83 tests and 0 failures. Counts read from this class's JUnit
 * XML:
 *
 *  - the record names the exercise by display name       83/3
 *  - the set number taken from the queue position        83/1
 *  - the remaining sets renumbered to close the hole     83/2
 *  - the list replaced instead of appended to            83/1
 *  - the ad-hoc guard removed                            83/0, SURVIVED
 *  - a skip with nothing after it records nothing        83/1
 *  - the block boundary ignored, statements always carry 83/1
 *  - the stated side carried                             83/1
 *
 * TWO OF THOSE ROWS SAY SOMETHING THE COUNTS ALONE DO NOT. The queue-position
 * mutation is caught ONLY by `two skips leave two entries in the order they
 * were dropped`: on a first skip the position and the plan's number are both 2,
 * so every single-skip fixture here agrees with the wrong rule, and the second
 * skip is what separates them. And the ad-hoc mutation SURVIVES, because
 * [RecordState.skipSetTarget] already returns null on an ad-hoc session --
 * `skippedState`'s own guard is a second gate on the same fact, so `an ad-hoc
 * session has nothing to skip` pins the behaviour and cannot say which gate
 * produced it.
 */
class SkippedSlotTest {
    // ---- the queue and the record -------------------------------------------

    /**
     * NOT A DIFFERENTIAL. The record's two fields, read back off the state the
     * skip leaves: the exercise ID the export joins on, and the PLAN's own set
     * number rather than the queue position.
     */
    @Test
    fun `the skip records the slot's exercise id and the plan's set number`() {
        val out = assertNotNull(skippedState(resting(steppingSquats(), queueIndex = 0)))

        assertEquals(listOf(SkippedSet("back_squat", 2)), out.skippedSets)
    }

    /**
     * NOT A DIFFERENTIAL. The queue loses the target slot and nothing else
     * moves: the numbers the plan gave the remaining sets stand, holes and all,
     * which is what makes a skip readable against the prescription afterwards.
     */
    @Test
    fun `the queue loses exactly the skipped slot and nothing renumbers`() {
        val before = steppingSquats()
        val out = assertNotNull(skippedState(resting(before, queueIndex = 0)))

        assertEquals(before.size - 1, out.queue.size)
        assertEquals(listOf(0, 2, 3), out.queue.filter { it.exercise.id == "back_squat" }.map { it.setIndexInExercise })
        assertEquals(listOf(4, 4, 4), out.queue.filter { it.exercise.id == "back_squat" }.map { it.setsInExercise })
        assertEquals(listOf(0, 1), out.queue.filter { it.exercise.id == "deadlift" }.map { it.setIndexInExercise })
        assertEquals(listOf(2, 2), out.queue.filter { it.exercise.id == "deadlift" }.map { it.setsInExercise })
    }

    /**
     * NOT A DIFFERENTIAL. Two taps, two entries, in the order they were
     * dropped. The second skip is taken from the state the first one left,
     * which is the only shape the control offers: `upcomingIndex` is unchanged
     * by the removal, so the slot that moved up is the next one it names.
     */
    @Test
    fun `two skips leave two entries in the order they were dropped`() {
        val once = assertNotNull(skippedState(resting(steppingSquats(), queueIndex = 0)))
        val twice = assertNotNull(skippedState(once))

        assertEquals(listOf(SkippedSet("back_squat", 2), SkippedSet("back_squat", 3)), twice.skippedSets)
        assertEquals(listOf(0, 3), twice.queue.filter { it.exercise.id == "back_squat" }.map { it.setIndexInExercise })
    }

    /** NOT A DIFFERENTIAL. An ad-hoc session has no prescription to deviate from. */
    @Test
    fun `an ad-hoc session has nothing to skip`() {
        assertNull(skippedState(resting(steppingSquats(), queueIndex = 0).copy(adHoc = true)))
    }

    /**
     * NOT A DIFFERENTIAL. A skip of the queue's LAST slot records the deviation
     * even though there is no set to re-seed the boxes from -- the branch where
     * the function returns before touching them.
     */
    @Test
    fun `a skip of the queue's last slot still records the deviation`() {
        val out = assertNotNull(skippedState(resting(steppingSquats().take(2), queueIndex = 0)))

        assertEquals(listOf(SkippedSet("back_squat", 2)), out.skippedSets)
        assertEquals(1, out.queue.size)
    }

    // ---- what the lifter said, inside the block ------------------------------

    /**
     * RED before the fix. The load correction is a statement about the EXERCISE
     * and holds for the rest of the block, which is the rule `restingState`
     * applies through `SetLoadPolicy.standingStatedAddedKg`. Clearing it on a
     * skip re-seeds the box from the plan and records the plan's number against
     * a bar the lifter loaded themselves -- #143's defect, reached through a
     * control #143 did not know about.
     *
     * The plan steps 100 / 110 / 120 / 130 and the lifter is standing 5 kg over
     * set 2's declaration, so set 3 is offered its own 120 plus that 5.
     */
    @Test
    fun `a mid-block skip carries the load correction onto the set that moves up`() {
        val state = resting(steppingSquats(), queueIndex = 0).copy(statedLoadKg = 115.0)

        val out = assertNotNull(skippedState(state))

        assertEquals(125.0, out.statedLoadKg, "the lifter's correction was reverted to the plan")
        assertEquals(out.weightUnit.inputValue(125.0), out.loadInput, "the box and the statement disagree")
    }

    /**
     * RED before the fix. A flat block returns the statement itself, bit for
     * bit, and the box shows it. The stepping case above cannot see this: a
     * subtraction with a zero difference is a different value from the number
     * the lifter typed, which is why `standingStatedAddedKg` returns early on
     * equal declarations.
     */
    @Test
    fun `a mid-block skip on a flat block keeps the stated load unchanged`() {
        val state = resting(flatSquats(), queueIndex = 0).copy(statedLoadKg = 117.5)

        val out = assertNotNull(skippedState(state))

        assertEquals(117.5, out.statedLoadKg, "a flat block's own number was not the number kept")
        assertEquals(out.weightUnit.inputValue(117.5), out.loadInput)
    }

    /**
     * RED before the fix. The rep count the lifter stated holds for the rest of
     * the block on `SetRepsPolicy`'s rule, and the box follows it.
     */
    @Test
    fun `a mid-block skip keeps the rep count the lifter stated`() {
        val state = resting(flatSquats(), queueIndex = 0).copy(statedReps = 5)

        val out = assertNotNull(skippedState(state))

        assertEquals(5, out.statedReps, "the stated count was reverted to the plan's")
        assertEquals("5", out.repsInput, "the box and the statement disagree")
    }

    /** RED before the fix. The same rule for a hold, on a timed block. */
    @Test
    fun `a mid-block skip keeps the hold the lifter stated`() {
        val state = resting(planks(), queueIndex = 0).copy(statedDurationS = 30)

        val out = assertNotNull(skippedState(state))

        assertEquals(30, out.statedDurationS, "the stated hold was reverted to the plan's")
        assertEquals("30", out.durationInput, "the box and the statement disagree")
    }

    /**
     * RED before the fix. The tempo set on the wheels holds for the rest of the
     * block on `TempoAdjustPolicy`'s rule -- equal declarations here, so the
     * adjustment stands.
     */
    @Test
    fun `a mid-block skip keeps the tempo the lifter set on the wheels`() {
        val state = resting(flatSquats(), queueIndex = 0).copy(statedTempo = "2011")

        val out = assertNotNull(skippedState(state))

        assertEquals("2011", out.statedTempo, "the turned tempo was reverted to the plan's")
    }

    // ---- where the block ends -----------------------------------------------

    /**
     * NOT A DIFFERENTIAL, and the half that catches the fix going too far. The
     * skipped set is the last of its block, so the set that moves up is the
     * next exercise and nothing the lifter said about the squat reaches it. The
     * boxes are seeded from the deadlift's own declarations.
     */
    @Test
    fun `skipping the last set of a block drops the statements with the block`() {
        val state = resting(steppingSquats(), queueIndex = 2).copy(
            statedLoadKg = 135.0,
            statedReps = 5,
            statedDurationS = 30,
            statedTempo = "2011",
        )

        val out = assertNotNull(skippedState(state))

        assertEquals("deadlift", out.upcomingSlot?.exercise?.id)
        assertNull(out.statedLoadKg, "a squat load reached a deadlift set")
        assertNull(out.statedReps)
        assertNull(out.statedDurationS)
        assertNull(out.statedTempo)
        assertEquals(out.weightUnit.inputValue(140.0), out.loadInput)
        assertEquals("8", out.repsInput)
    }

    /**
     * NOT A DIFFERENTIAL, and it is the ONE stated field that does not carry.
     * The plan writes unilateral work one set per side, so its own order is the
     * alternation and a choice that stood would put every remaining set of the
     * block on one arm -- `restingState` states that rule at its own
     * `statedSide = null`, and `NextSetSideTest` pins the alternation it
     * protects.
     *
     * WHAT CARRYING IT WOULD PUBLISH, measured rather than argued. With
     * `statedSide = s.statedSide` in `skippedState` and a throwaway probe over
     * a plan declaring left / right / right: a "left" stated for the skipped
     * set reaches the set that moves up, `advancedState` bakes it in, and the
     * slot goes into the write with `side` left against `plannedSide` right --
     * a one-armed set the lifter said nothing about, published as a deviation
     * from a prescription they never contradicted. Round 1 of the review asked
     * for the carry; that is the measurement it was refused on. The probe also
     * shows what does NOT catch it: 41 tests over this class,
     * `AppendedSlotTest` and `NextSetSideTest`, 1 failed, and the one was the
     * test below.
     */
    @Test
    fun `the arm the lifter chose does not survive a skip`() {
        val state = resting(flatSquats(), queueIndex = 0).copy(statedSide = "left")

        val out = assertNotNull(skippedState(state))

        assertNull(out.statedSide, "a side stated for the skipped set reached the one that moved up")
    }
}

private val squat = ExerciseDef("back_squat", "Back squat")
private val deadlift = ExerciseDef("deadlift", "Deadlift")
private val plank = ExerciseDef("plank", "Plank")

/**
 * Mid-rest after the slot at [queueIndex], which is where the control is drawn:
 * `upcomingIndex` is one further on.
 */
private fun resting(queue: List<PlannedSlot>, queueIndex: Int) =
    RecordState(stage = Stage.RESTING, queue = queue, queueIndex = queueIndex, adHoc = false)

/**
 * One prescribed slot, UNBAKED: every `planned` declaration equals the live
 * value beside it, which is the state a slot the next START has not run is in.
 * The standing policies read the frozen pair, so a fixture that moved one side
 * would be asking them a question the app never asks.
 */
private fun slot(
    exercise: ExerciseDef,
    setIndexInExercise: Int,
    setsInExercise: Int,
    loadKg: Double,
    reps: Int? = 8,
    durationS: Int? = null,
    tempo: String? = "3010",
) = PlannedSlot(
    exercise = exercise,
    geometry = SetGeometryPolicy.describe(exercise, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = setsInExercise,
    reps = reps,
    durationS = durationS,
    plannedReps = reps,
    plannedDurationS = durationS,
    loadKg = loadKg,
    plannedLoadKg = loadKg,
    tempo = tempo,
    plannedTempo = tempo,
    side = null,
    plannedSide = null,
    implementCount = 1,
    implement = Implement.BARBELL,
    barKg = 20.0,
    exerciseNotes = null,
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 180,
    prepS = 10,
    sensors = 1,
    progression = ProgressionKind.WEIGHT,
    isExerciseChange = setIndexInExercise == 0,
    warmup = false,
    isAddedSet = false,
)

/**
 * A STEPPING squat block of four, then a deadlift block of two.
 *
 * The step is what makes the carry visible as a distance rather than as a
 * number, and the second block is what gives the last-of-block case somewhere
 * to land.
 */
private fun steppingSquats() = listOf(
    slot(squat, 0, 4, loadKg = 100.0),
    slot(squat, 1, 4, loadKg = 110.0),
    slot(squat, 2, 4, loadKg = 120.0),
    slot(squat, 3, 4, loadKg = 130.0),
    slot(deadlift, 0, 2, loadKg = 140.0),
    slot(deadlift, 1, 2, loadKg = 140.0),
)

/** A FLAT squat block: equal declarations, where a statement stands as itself. */
private fun flatSquats() = listOf(
    slot(squat, 0, 3, loadKg = 100.0),
    slot(squat, 1, 3, loadKg = 100.0),
    slot(squat, 2, 3, loadKg = 100.0),
)

/** A block measured on the clock rather than in reps. */
private fun planks() = listOf(
    slot(plank, 0, 3, loadKg = 0.0, reps = null, durationS = 45, tempo = null),
    slot(plank, 1, 3, loadKg = 0.0, reps = null, durationS = 45, tempo = null),
    slot(plank, 2, 3, loadKg = 0.0, reps = null, durationS = 45, tempo = null),
)
