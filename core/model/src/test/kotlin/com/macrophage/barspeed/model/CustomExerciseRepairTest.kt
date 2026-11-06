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

    /**
     * core-model review 2026-11-06, F1: the exact-id start table reaches rows
     * already stored. Every install that ran robocoach's lower-body plan holds
     * a `leg_extension` row written ECCENTRIC; the repair needs nothing
     * registered, because it compares against [ExerciseDef.inferStartPhase],
     * which is where the table is read.
     */
    @Test
    fun `a leg extension stored eccentric-first is repaired and read concentric-first`() {
        val stale = StoredExerciseRow(ExerciseDef.inferredDisplayName("leg_extension"), StartPhase.ECCENTRIC.name)
        assertEquals(
            StoredExerciseRow(ExerciseDef.inferredDisplayName("leg_extension"), StartPhase.CONCENTRIC.name),
            CustomExerciseRepair.repaired("leg_extension", stale),
        )
        assertEquals(StartPhase.CONCENTRIC, CustomExerciseRepair.definition("leg_extension", stale).startsWith)
    }

    /** The same, both ways, for the rest of the table and the RDL veto. */
    @Test
    fun `every start the table moved is repaired from the stale stored value`() {
        val moved =
            mapOf(
                "step_up" to StartPhase.CONCENTRIC,
                "glute_bridge" to StartPhase.CONCENTRIC,
                "sit_up" to StartPhase.CONCENTRIC,
                "pec_deck" to StartPhase.CONCENTRIC,
                "hip_abduction" to StartPhase.CONCENTRIC,
                "arnold_press" to StartPhase.CONCENTRIC,
                "overhead_squat" to StartPhase.ECCENTRIC,
                "overhead_triceps_extension" to StartPhase.ECCENTRIC,
                "nordic_curl" to StartPhase.ECCENTRIC,
                "dumbbell_romanian_deadlift" to StartPhase.ECCENTRIC,
                "snatch_grip_rdl" to StartPhase.ECCENTRIC,
            )
        for ((id, now) in moved) {
            val was = if (now == StartPhase.CONCENTRIC) StartPhase.ECCENTRIC else StartPhase.CONCENTRIC
            val stale = StoredExerciseRow(ExerciseDef.inferredDisplayName(id), was.name)
            assertEquals(now.name, CustomExerciseRepair.repaired(id, stale)?.startsWith, id)
            assertEquals(now, CustomExerciseRepair.definition(id, stale).startsWith, id)
        }
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
