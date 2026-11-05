package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CustomExerciseRepair]: when a stored custom-exercise row is a stale
 * inference, and what replaces it (#59).
 */
class CustomExerciseRepairTest {
    @Test
    fun `a pushdown stored before the hint landed is repaired to start concentric`() {
        val stale = StoredExerciseRow("Tricep pushdown", StartPhase.ECCENTRIC.name)
        assertEquals(
            StoredExerciseRow("Tricep pushdown", StartPhase.CONCENTRIC.name),
            CustomExerciseRepair.repaired("tricep_pushdown", stale),
        )
    }

    @Test
    fun `a row that says what the app infers today is left alone`() {
        for (id in listOf("cable_fly", "tricep_pushdown", "seated_leg_curl", "pallof_hold")) {
            assertNull(CustomExerciseRepair.repaired(id, CustomExerciseRepair.inferredRow(id)), id)
        }
    }

    @Test
    fun `a stale display name is repaired with the start phase it already has`() {
        val stored = StoredExerciseRow("cable fly", ExerciseDef.inferStartPhase("cable_fly").name)
        assertEquals(CustomExerciseRepair.inferredRow("cable_fly"), CustomExerciseRepair.repaired("cable_fly", stored))
    }

    @Test
    fun `the row written for a new id is the inference, field by field`() {
        assertEquals(
            StoredExerciseRow(
                ExerciseDef.inferredDisplayName("rope_pushdown"),
                ExerciseDef.inferStartPhase("rope_pushdown").name,
            ),
            CustomExerciseRepair.inferredRow("rope_pushdown"),
        )
    }
}
