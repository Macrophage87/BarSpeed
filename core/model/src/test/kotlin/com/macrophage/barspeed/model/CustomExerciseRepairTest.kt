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

    /**
     * The read half, lifted from `SessionRepository.exerciseById`: a row that
     * says what the app infers today is read as the definition the app builds
     * for an id with no row at all, so a stored row and no row agree.
     */
    @Test
    fun `a row that agrees with today's inference is read as the inferred definition`() {
        val ids = listOf("cable_fly", "tricep_pushdown", "seated_leg_curl", "pallof_hold", "lat_pulldown", "sled_push")
        for (id in ids) {
            val read = CustomExerciseRepair.definition(id, CustomExerciseRepair.inferredRow(id))
            assertEquals(ExerciseDef.inferred(id), read, id)
        }
    }

    /**
     * DIFFERENTIAL, #59 round 1 (A2/B4). The repair rewrites the row at set
     * end, but the plan queue reads the row earlier, when the session card is
     * tapped, so a stale row ran a whole session on the old start before the
     * rewrite reached anything. The read has to say today's answer itself.
     */
    @Test
    fun `a stale stored row is read as today's inference before anything rewrites it`() {
        val stale = StoredExerciseRow("Tricep pushdown", StartPhase.ECCENTRIC.name)
        val read = CustomExerciseRepair.definition("tricep_pushdown", stale)
        assertEquals(StartPhase.CONCENTRIC, read.startsWith, "a stale ECCENTRIC row still drives the queue")
        assertEquals(ExerciseDef.inferred("tricep_pushdown"), read)
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
