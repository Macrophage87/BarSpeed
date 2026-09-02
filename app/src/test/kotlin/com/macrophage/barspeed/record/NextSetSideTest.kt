package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a side chosen on the change-next-set control does to the queue (#215).
 *
 * THE FIRST TEST IS THE DIFFERENTIAL and it fails at the commit that
 * introduces it: `carriedValues` asks [com.macrophage.barspeed.model
 * .SideChoicePolicy], whose body at this SHA is a seam returning the plan's
 * declaration, so a stated side reaches the slot and is discarded. That is the
 * defect #144 describes from the other end -- the lifter swaps arm order, the
 * record says the plan's order, and no reading of the row can tell.
 *
 * The other four pass here. They are the half of the contract that catches the
 * fix going too far: a change that made the lifter's choice win everywhere
 * would flatten the plan's alternation onto one arm, put a limb on a bilateral
 * set, or overwrite the frozen prescription the export publishes. Each is a
 * real way to break this and none of them would be caught by the differential
 * alone.
 *
 * WHAT IS NOT REACHABLE FROM HERE, said rather than implied. The EXPIRY --
 * statedSide cleared on every rest transition, which is what keeps the choice
 * to one set -- is four functions, and only one of them is pinned:
 * `AppendedSlotTest` reaches `removedState`, while `restingState` is private
 * and takes a frozen set write, and `appendedState` and `jumpedState` are
 * pinned for the other four stated fields and not for this one. So the expiry
 * is mostly compile-gated, and is NOT settled by the emulator run either: that
 * run's plan was left / right / left, so set 3's prescription equals the
 * deviation stated for set 2, and it cannot tell expiry from coincidence. A
 * left / right / right plan would settle it and has not been run. And
 * `flattenPlan`, which freezes `plannedSide` off the plan, is a suspend
 * extension on `SessionRepository`: its own KDoc records that no test on the
 * CI path calls it, and `plannedReps` and `plannedTempo` are frozen there
 * unpinned for the same reason.
 *
 * Reachable for [LastPlannedSetTest]'s reason: `app/build.gradle.kts` pins the
 * test JVM to 21, and `advancedState` is a pure function over `RecordState`.
 */
class NextSetSideTest {
    @Test
    fun `the arm the lifter chose is baked into the set START will run`() {
        val out = advancedState(restingWithSide(queueIndex = 0, statedSide = "left"))

        assertEquals("left", out.queue[1].side, "the stated arm did not reach the set coming up")
        assertEquals(1, out.queueIndex)
        assertEquals(Stage.READY, out.stage)
    }

    @Test
    fun `the plan's alternation past that set is untouched`() {
        val out = advancedState(restingWithSide(queueIndex = 0, statedSide = "left"))

        assertEquals("left", out.queue[2].side, "a choice for one set changed the set after it")
        assertEquals("right", out.queue[3].side, "a choice for one set changed the rest of the block")
    }

    @Test
    fun `the frozen prescription survives the bake`() {
        val out = advancedState(restingWithSide(queueIndex = 0, statedSide = "left"))

        assertEquals(
            "right",
            out.queue[1].plannedSide,
            "the bake overwrote what the plan asked for, so the export can no longer publish the pair",
        )
    }

    @Test
    fun `a set nobody stated an arm for keeps the one the plan prescribed`() {
        val out = advancedState(restingWithSide(queueIndex = 0, statedSide = null))

        assertEquals("right", out.queue[1].side)
        assertEquals("right", out.queue[1].plannedSide)
    }

    @Test
    fun `a stale statement cannot put an arm on a bilateral set`() {
        val bilateral = listOf(bothSidesSlot(0), bothSidesSlot(1))
        val out = advancedState(
            RecordState(
                stage = Stage.RESTING,
                queue = bilateral,
                queueIndex = 0,
                adHoc = false,
                statedSide = "right",
            ),
        )

        assertNull(out.queue[1].side, "a bilateral set was recorded as one-armed")
    }
}

private val curl = ExerciseDef("dumbbell_curl", "Dumbbell curl")

private fun unilateralSlot(setIndexInExercise: Int, side: String) = PlannedSlot(
    exercise = curl,
    geometry = SetGeometryPolicy.describe(curl, null),
    setIndexInExercise = setIndexInExercise,
    setsInExercise = 4,
    reps = 10,
    durationS = null,
    plannedReps = 10,
    plannedDurationS = null,
    loadKg = 14.0,
    plannedLoadKg = 14.0,
    tempo = null,
    plannedTempo = null,
    side = side,
    plannedSide = side,
    implementCount = 1,
    exerciseNotes = null,
    exerciseNotesBehindTap = null,
    targetMeanConVelMps = null,
    velocityLossStopPct = null,
    restS = 90,
    prepS = 0,
    sensors = 1,
    isExerciseChange = setIndexInExercise == 0,
    warmup = false,
    isAddedSet = false,
)

private fun bothSidesSlot(setIndexInExercise: Int) =
    unilateralSlot(setIndexInExercise, "left").copy(side = null, plannedSide = null)

/** Left, right, left, right -- the order a plan writes unilateral work in. */
private val alternating =
    listOf(
        unilateralSlot(0, "left"),
        unilateralSlot(1, "right"),
        unilateralSlot(2, "left"),
        unilateralSlot(3, "right"),
    )

private fun restingWithSide(queueIndex: Int, statedSide: String?) = RecordState(
    stage = Stage.RESTING,
    queue = alternating,
    queueIndex = queueIndex,
    adHoc = false,
    statedSide = statedSide,
)
