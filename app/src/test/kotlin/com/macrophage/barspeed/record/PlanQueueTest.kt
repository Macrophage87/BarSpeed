package com.macrophage.barspeed.record

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [timedVerdicts] at the boundary [TIMED_CLOSE_ENOUGH_FRACTION] draws
 * between "just short" and "consider a lighter load" -- the boundary a
 * naive implementation is likeliest to get wrong by one second. Issue
 * #122's route 1: `:app` had no test source set at all, so this pure
 * function -- already public, no visibility change needed to reach it --
 * had never been run by anything.
 *
 * NOT ALSO `rescuedTitle`/`rescuedDiscardWarning`/`rescuedDetail`, the
 * three functions #122 itself names. Those are pure and were reachable the
 * same way (widening from `private` to `internal`), but every one of them
 * lives in `HomeScreen.kt`, which also switches on `WeightUnit` -- a
 * `:core:model` enum -- for the unrelated body-weight dialog. Kotlin
 * compiles every `when`-over-enum in one file into a single synthetic
 * `HomeScreenKt$WhenMappings` class, so calling any of the three loads
 * that whole class, including the `WeightUnit` branch. `:core:model` (and
 * `:core:dsp`, `:core:witmotion`, `:core:hrm`) build with
 * `kotlin { jvmToolchain(21) }` and emit class file version 65; `:app`
 * builds and runs its own unit tests on `jvmToolchain(17)`, whose JVM
 * class loader stops at 61 -- `UnsupportedClassVersionError` on the first
 * test, every time, before any assertion runs. `:core:data`'s own
 * `build.gradle.kts` already found and fixed this same defect for itself
 * (`tasks.withType<Test>().configureEach { javaLauncher.set(...) }`,
 * pinning the test JVM to 21 without changing what the module compiles
 * to), with its own KDoc naming the identical error one module over. The
 * matching fix for `:app` is a build-file edit this tranche was told not
 * to make.
 *
 * `PlanQueue.kt` itself DOES import from `:core:model`
 * (`PlanSessionDef`, `SetGeometryPolicy`, for the unrelated
 * `flattenPlan`), so the same collateral risk was live here too. It
 * measured clean: all five tests below ran and passed with no toolchain
 * error, because calling `timedVerdicts` alone never causes the JVM to
 * resolve those other types -- there is no enum `when` in this file to
 * force a shared `WhenMappings` class, and no top-level property whose
 * `<clinit>` would touch them either. Measured, not assumed: read the
 * class-loading behaviour this file depends on off the JVM specification
 * rather than off this comment before relying on the same argument for a
 * different function in a different file.
 */
class PlanQueueTest {
    @Test
    fun `no actual hold time yields no verdict`() {
        assertEquals(emptyList(), timedVerdicts(actualS = null, plannedS = 30))
    }

    @Test
    fun `an actual hold with no planned target just reports the time held`() {
        assertEquals(listOf("Held 12s."), timedVerdicts(actualS = 12, plannedS = null))
    }

    @Test
    fun `holding at least the full planned time earns the full-target verdict`() {
        assertEquals(
            listOf("Held 10s — full 10s target. Nice."),
            timedVerdicts(actualS = 10, plannedS = 10),
        )
    }

    @Test
    fun `holding exactly at the close-enough threshold earns the just-short verdict`() {
        // plannedS=10, TIMED_CLOSE_ENOUGH_FRACTION=0.9 -> threshold is 9.
        assertEquals(
            listOf("Held 9s of 10s — just short."),
            timedVerdicts(actualS = 9, plannedS = 10),
        )
    }

    @Test
    fun `holding one second under the close-enough threshold earns the shorter-target verdict`() {
        assertEquals(
            listOf("Held 8s of 10s. Consider a shorter target or lighter load."),
            timedVerdicts(actualS = 8, plannedS = 10),
        )
    }

    // ---- #177: where an appended set goes -----------------------------------

    /** Three sets of one exercise, then two of another. */
    private fun twoBlocks() = listOf(
        QueueBlockKey("back_squat", 0),
        QueueBlockKey("back_squat", 1),
        QueueBlockKey("back_squat", 2),
        QueueBlockKey("seated_row", 0),
        QueueBlockKey("seated_row", 1),
    )

    /**
     * On the LAST set of a block, "after the exercise's remaining sets" and
     * "immediately next" are the same index, and both rules must say so.
     *
     * Pinned in the commit that adds the seam because it is one of the two
     * answers the naive rule and the block rule agree on -- so it holds before
     * the fix and after it, which is what a characterization pin is for.
     */
    @Test
    fun `a set appended on the last set of a block goes immediately after it`() {
        assertEquals(3, addedSetIndex(twoBlocks(), upcomingIndex = 2))
    }

    /** The same, on the last slot of the whole queue: the answer is the end. */
    @Test
    fun `a set appended on the final slot of the queue goes at the end`() {
        assertEquals(5, addedSetIndex(twoBlocks(), upcomingIndex = 4))
    }

    /**
     * An index outside the queue appends at the end rather than throwing or
     * returning a position that would insert past it.
     *
     * `upcomingIndex` is `queueIndex + 1` during rest, so it is legitimately one
     * past the last slot on the rest screen after the final set -- the state in
     * which the screen says "That was the last planned set". Nothing offers the
     * control there today, and a guard that depends on which composable happens
     * to call you is a guard nothing states.
     */
    @Test
    fun `an out-of-range upcoming index appends at the end`() {
        assertEquals(5, addedSetIndex(twoBlocks(), upcomingIndex = 5))
        assertEquals(0, addedSetIndex(emptyList(), upcomingIndex = 0))
    }

    /**
     * RED before the fix. Appending on set ONE of a three-set block puts the
     * new set after set three, not after set one.
     *
     * This is #177 item 3 and the whole reason the rule is a function rather
     * than an insertion at `upcomingIndex + 1`. The lifter is adding to the
     * block: they found the working weight on the opener and want one more set
     * of the same exercise, and the two sets the plan already asked for are
     * still wanted. Jumping the queue would run the appended set second and
     * push the prescribed remainder behind it, which is a different session
     * from the one anyone asked for.
     */
    @Test
    fun `a set appended mid-block goes after the block's remaining sets`() {
        assertEquals(3, addedSetIndex(twoBlocks(), upcomingIndex = 0))
        assertEquals(3, addedSetIndex(twoBlocks(), upcomingIndex = 1))
    }

    /**
     * RED before the fix. Adding two sets is adding one twice: the second lands
     * after the first (#177 item 4).
     *
     * The queue here is what the first append leaves behind -- the appended
     * slot carries the next index in the block, so it reads as a continuation
     * of it rather than as the start of a new one. Nothing may assume at most
     * one addition, and the shape that would break it is a rule scanning for
     * the last PRESCRIBED set instead of the last set of the block.
     */
    @Test
    fun `appending twice puts the second set after the first`() {
        val afterOneAppend = twoBlocks().toMutableList().apply { add(3, QueueBlockKey("back_squat", 3)) }
        assertEquals(4, addedSetIndex(afterOneAppend, upcomingIndex = 0))
        val afterTwoAppends = afterOneAppend.toMutableList().apply { add(4, QueueBlockKey("back_squat", 4)) }
        assertEquals(5, addedSetIndex(afterTwoAppends, upcomingIndex = 0))
    }

    /**
     * RED before the fix. A session running one movement in two consecutive
     * blocks appends to the block the lifter is IN, not to the far end of both.
     *
     * `setIndexInExercise == 0` is what marks the start of a block, and it is
     * `SetLoadPolicy.sameExerciseBlock`'s rule read from here rather than
     * restated. Without it the exercise id alone would swallow the second block
     * -- so a lifter adding a set to their opening squat block would find it
     * queued after the closing one, three exercises later.
     */
    @Test
    fun `two consecutive blocks of one exercise are two blocks`() {
        val twice =
            listOf(
                QueueBlockKey("back_squat", 0),
                QueueBlockKey("back_squat", 1),
                QueueBlockKey("back_squat", 0),
                QueueBlockKey("back_squat", 1),
            )
        assertEquals(2, addedSetIndex(twice, upcomingIndex = 0))
        assertEquals(4, addedSetIndex(twice, upcomingIndex = 2))
    }

    /**
     * FOUND BY MUTATION TESTING, and the only pin that covers the exercise-id
     * half of the rule.
     *
     * Deleting `blocks[i].exerciseId == exerciseId` from [addedSetIndex] left
     * the whole suite green at
     * `2f16199d07e8f4093b07a2e82693bafd065463fa`, reported in that commit's own
     * body as a surviving mutation. The reason is that every other fixture here
     * has each block starting at `setIndexInExercise == 0`, so the index test
     * alone stops the walk and the id test never decides anything.
     *
     * A REAL QUEUE CAN BREAK THAT. `jumpToExercise` pulls the chosen exercise's
     * remaining sets forward and copies only `isExerciseChange`, so the slots
     * keep the indices the plan gave them: a lifter who switches to the row
     * after doing its first set leaves a row block whose first slot is index 1.
     * Appending to the squat block that now precedes it must stop at that
     * boundary. Without the id test it walks straight through, and the added
     * squat set is queued after the ROW block instead -- which is the near
     * neighbour of the two-consecutive-blocks case, failing in the opposite
     * direction.
     *
     * Its own commit because c3 touches no test file; the pin is green with the
     * fix in place and reds the moment the guard is removed, which is what
     * makes it a pin rather than decoration.
     */
    @Test
    fun `a block whose first slot survived a switch still ends the walk`() {
        val afterSwitch =
            listOf(
                QueueBlockKey("back_squat", 0),
                QueueBlockKey("back_squat", 1),
                // What jumpToExercise leaves behind: the row's remaining sets,
                // pulled forward, still carrying the plan's own indices.
                QueueBlockKey("seated_row", 1),
                QueueBlockKey("seated_row", 2),
            )
        assertEquals(2, addedSetIndex(afterSwitch, upcomingIndex = 0))
        assertEquals(4, addedSetIndex(afterSwitch, upcomingIndex = 2))
    }
}
