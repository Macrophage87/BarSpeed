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
 * built its own sources on `jvmToolchain(17)` and RAN its unit tests on
 * that launcher too, and that JVM's class loader stops at 61 --
 * `UnsupportedClassVersionError` on the first test, every time, before any
 * assertion ran. `:core:data`'s own
 * `build.gradle.kts` already found and fixed this same defect for itself
 * (`tasks.withType<Test>().configureEach { javaLauncher.set(...) }`,
 * pinning the test JVM to 21 without changing what the module compiles
 * to), with its own KDoc naming the identical error one module over.
 * `:app` carries that same block as of #188, so the trap described in this
 * paragraph is CLOSED: `AppendedSlotTest`, one file over, loads
 * `ExerciseDef` and `SetGeometryPolicy` freely. The paragraph stays because
 * it is why this file is shaped the way it is, not because it still binds.
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
}
