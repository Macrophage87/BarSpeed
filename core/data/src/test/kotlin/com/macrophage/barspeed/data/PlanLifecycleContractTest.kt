package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.PlanLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three status literals a plan row can carry, pinned from the one module
 * that can see both statements of them.
 *
 * `PlanLifecycle.of` matches string literals because `:core:model` cannot
 * import [PlanEntity] — the dependency runs the other way. That is two copies
 * of three strings, which is the shape that drifts. This test is the coupling:
 * rename a constant, or change the value behind one, and it reds here rather
 * than in a plan that silently stops being startable.
 *
 * Nothing here executes Room, SQLite or Android; [PlanEntity]'s companion
 * constants are plain strings.
 */
class PlanLifecycleContractTest {
    @Test
    fun `every stored status maps to the lifecycle the start policy reads`() {
        assertEquals(PlanLifecycle.ACTIVE, PlanLifecycle.of(PlanEntity.STATUS_ACTIVE))
        assertEquals(PlanLifecycle.STAGED, PlanLifecycle.of(PlanEntity.STATUS_STAGED))
        assertEquals(PlanLifecycle.ARCHIVED, PlanLifecycle.of(PlanEntity.STATUS_ARCHIVED))
    }

    @Test
    fun `the three statuses the app writes are distinct and exhaustive of what it writes`() {
        // States the consequence the mapping test does not: a fourth status
        // introduced without a lifecycle for it lands every row carrying it in
        // UNKNOWN. This test cannot detect that on its own - the set below is
        // written out by hand, so a fourth constant only reds here once someone
        // adds it here.
        val written = setOf(PlanEntity.STATUS_ACTIVE, PlanEntity.STATUS_STAGED, PlanEntity.STATUS_ARCHIVED)

        assertEquals(3, written.size)
        assertEquals(
            setOf(PlanLifecycle.ACTIVE, PlanLifecycle.STAGED, PlanLifecycle.ARCHIVED),
            written.map { PlanLifecycle.of(it) }.toSet(),
        )
    }
}
