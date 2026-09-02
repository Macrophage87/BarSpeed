package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three-way decision behind one `sensorOnStack` value: what the plan said,
 * what the app ships for that id, and nothing.
 *
 * One case per [GeometrySource] this decision can produce, because the source
 * is published and a reader who cannot tell a declaration from a default reads
 * a guess as a fact. Field-37 is the reason the seeded branch exists: six sets
 * ran on an assist stack with the key omitted (#223).
 */
class StackMountSeedTest {
    @Test
    fun `a declared true is the plan's word, reported declared`() {
        assertEquals(
            StackMount(true, GeometrySource.DECLARED),
            SetGeometryPolicy.stackMount("back_squat", base = false, declared = true),
        )
    }

    /**
     * The half that makes the seed default safe to ship: a plan that says
     * `false` on a machine the app seeds still wins. The lifter may have
     * clipped the sensor to the handle, and only the plan's author knows.
     */
    @Test
    fun `a declared false beats the seed default and is reported declared`() {
        assertTrue(ExerciseDef.ridesStack("lat_pulldown"))
        assertEquals(
            StackMount(false, GeometrySource.DECLARED),
            SetGeometryPolicy.stackMount("lat_pulldown", base = false, declared = false),
        )
    }

    @Test
    fun `an omitted key on a seeded machine id resolves to the stack, reported seeded`() {
        assertEquals(
            StackMount(true, GeometrySource.SEEDED),
            SetGeometryPolicy.stackMount("assisted_pull_up", base = false, declared = null),
        )
    }

    /** A built-in definition that already says stack is a seed answer too. */
    @Test
    fun `an omitted key over a built-in true is reported seeded`() {
        assertEquals(
            StackMount(true, GeometrySource.SEEDED),
            SetGeometryPolicy.stackMount("some_custom_machine", base = true, declared = null),
        )
    }

    /**
     * Nothing said it and no table names it, so the type default stands and
     * says so. DEFAULT rather than INFERRED on purpose: no words in the id are
     * read here, so an id the table does not carry is not being guessed at.
     */
    @Test
    fun `an omitted key on an id nothing covers is reported default`() {
        assertEquals(
            StackMount(false, GeometrySource.DEFAULT),
            SetGeometryPolicy.stackMount("back_squat", base = false, declared = null),
        )
        assertEquals(
            StackMount(false, GeometrySource.DEFAULT),
            SetGeometryPolicy.stackMount("machine_assisted_pull_up", base = false, declared = null),
        )
    }

    /**
     * The table matches an id exactly and lowercased, so an entry that is not
     * a lowercase snake_case id can never be reached.
     */
    @Test
    fun `every seeded id is a lowercase snake_case id and matches case-insensitively`() {
        for (id in ExerciseDef.STACK_MOUNTED_IDS) {
            assertEquals(id.lowercase(), id, "$id can never match")
            assertTrue(id.matches(Regex("[a-z0-9_]+")), "$id is not a plan-shaped id")
            assertTrue(ExerciseDef.ridesStack(id.uppercase()), "$id does not match case-insensitively")
        }
    }

    /**
     * None of them is a [ExerciseDef.SEED] entry, which is what keeps this
     * table from changing the provenance of the four geometry values it says
     * nothing about.
     */
    @Test
    fun `no seeded stack id is also a built-in exercise`() {
        for (id in ExerciseDef.STACK_MOUNTED_IDS) {
            assertNull(ExerciseDef.seedById(id), "$id is a SEED entry, so seeding its mount changes other sources")
        }
    }

    /**
     * Sleds are excluded although #223 names them: a leg press sled is the
     * load itself on an inclined rail, and `sensorOnStack` forces the measured
     * axis to vertical.
     */
    @Test
    fun `sleds are not seeded as stack-mounted`() {
        assertFalse(ExerciseDef.ridesStack("leg_press"))
        assertFalse(ExerciseDef.ridesStack("hack_squat"))
    }
}
