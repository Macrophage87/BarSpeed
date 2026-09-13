package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExerciseDef.DRIVE_DOWN_IDS] against the two statements it is derived from,
 * so the table cannot drift away from either of them silently (#263).
 *
 * It mints no id of its own: every entry is an [ExerciseDef.STACK_MOUNTED_IDS]
 * entry, and which of those twelve belong is decided by the three families
 * [ExerciseDef.concentricUp]'s KDoc and the published plan schema both name --
 * leg curls, lat pulldowns, pushdowns. The four stack ids that are NOT here
 * are pinned by name, because the interesting failure is a widening rather
 * than a narrowing: `leg_extension` rides the same stack and drives UP, and a
 * table that swept in every id it could reach would warn on it.
 *
 * Nothing here reads a plan. This table feeds a WARNING; the resolved
 * geometry is `DriveDownSeedBaselineTest`'s subject and does not move.
 */
class DriveDownFamilyTest {
    @Test
    fun `every drive-down id is a stack-mounted id`() {
        assertTrue(
            ExerciseDef.DRIVE_DOWN_IDS.all { it in ExerciseDef.STACK_MOUNTED_IDS },
            "these mint ids of their own: " +
                "${ExerciseDef.DRIVE_DOWN_IDS - ExerciseDef.STACK_MOUNTED_IDS}",
        )
    }

    /**
     * The exclusions, by name. A horizontal pull has no up or down for a drive
     * direction to be wrong about, and a leg extension's drive really does go
     * up -- so on all four the omitted key's default is correct and a warning
     * would be noise.
     */
    @Test
    fun `the stack ids that drive up or sideways are excluded, by name`() {
        listOf(
            "seated_row",
            "seated_cable_row",
            "cable_row",
            "leg_extension",
            "assisted_pull_up",
            "assisted_chin_up",
            "assisted_dip",
        ).forEach { id ->
            assertTrue(ExerciseDef.ridesStack(id), "$id left STACK_MOUNTED_IDS")
            assertFalse(ExerciseDef.drivesDown(id), "$id must not be treated as a drive-down lift")
        }
    }

    /** The three families, and nothing else, as both documents name them. */
    @Test
    fun `the table is exactly the pulldown, pushdown and leg-curl entries of the stack table`() {
        assertEquals(
            setOf("lat_pulldown", "triceps_pushdown", "leg_curl", "seated_leg_curl", "lying_leg_curl"),
            ExerciseDef.DRIVE_DOWN_IDS,
        )
    }

    /**
     * The premise the whole warning rests on: with no seed entry there is no
     * built-in drive direction, so the Kotlin default `concentricUp = true`
     * stands and an omitted key really is the app guessing.
     */
    @Test
    fun `no drive-down id has a seed entry to supply a real drive direction`() {
        ExerciseDef.DRIVE_DOWN_IDS.forEach { id ->
            assertNull(ExerciseDef.seedById(id), "$id is seeded now; re-decide #263's warning")
        }
    }

    /** [ExerciseDef.ridesStack]'s shape, including its lowercasing. */
    @Test
    fun `drivesDown matches an exact id, case-insensitively, and reads no words`() {
        assertTrue(ExerciseDef.drivesDown("LAT_PULLDOWN"))
        assertTrue(ExerciseDef.drivesDown("lat_pulldown"))
        // Words in the id are deliberately not read: a close name is not a match.
        assertFalse(ExerciseDef.drivesDown("wide_grip_lat_pulldown"))
        assertFalse(ExerciseDef.drivesDown("pulldown"))
        assertFalse(ExerciseDef.drivesDown("back_squat"))
    }

    /** Nothing in the resolution path consults it. */
    @Test
    fun `the table does not change how a set resolves`() {
        val base = ExerciseDef("lat_pulldown", "Lat Pulldown")
        assertTrue(base.concentricUp, "the seedless default is still drive-up")
        assertTrue(ExerciseDef.resolvedById("lat_pulldown").concentricUp)
    }
}
