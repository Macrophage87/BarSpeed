package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plane the two seeded carries are measured in, and the one place that
 * reads a seed's plane without the seed in hand (#63).
 *
 * The rows here hold on either side of the seed change: they are the
 * neighbours it must not move.
 */
class CarryPlaneTest {
    private fun document(id: String, declarations: String): String = """
        {"schemaVersion":"1.12","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":3}]}
        ]}]}
    """.trimIndent()

    private fun inversionLines(id: String, declarations: String): List<String> =
        PlanImport.parse(document(id, declarations)).warnings.filter { "\"sensorInverted\"" in it }

    private val stackDriveDown = ""","sensorOnStack":true,"concentric":"down""""

    /** No seed but a carry travels horizontally, before or after #63. */
    @Test
    fun `no seed entry other than a carry is horizontal`() {
        val others = ExerciseDef.SEED.filter { it.kind != ExerciseKind.CARRY }
        assertTrue(others.isNotEmpty())
        for (def in others) assertEquals(false, def.horizontal, def.id)
    }

    /**
     * The import gate's inversion line on a vertical seed, and on the same
     * seed declared horizontal: the line follows the plane, declared or not.
     */
    @Test
    fun `the inversion line follows a vertical seed's plane and a declared one`() {
        assertEquals(1, inversionLines("back_squat", stackDriveDown).size)
        assertEquals(emptyList(), inversionLines("back_squat", stackDriveDown + ""","plane":"horizontal""""))
    }

    /** A declared vertical plane on a carry is the plan's word, and is read. */
    @Test
    fun `a carry declared vertical is read vertical by the inversion line`() {
        assertEquals(1, inversionLines("farmers_walk", stackDriveDown + ""","plane":"vertical"""").size)
    }
}
