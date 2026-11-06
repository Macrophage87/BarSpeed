package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExerciseDef.inferred], the definition the PLAN path builds for an id the
 * app ships no entry for and has stored no row for, lifted out of
 * `SessionRepository.exerciseById` so it runs here (#69 item 4).
 *
 * Every field is pinned against the inference it must come from rather than a
 * literal, except where the literal is the point -- the display name, and the
 * stack default on an id that is in [ExerciseDef.STACK_MOUNTED_IDS].
 */
class InferredExerciseTest {
    @Test
    fun `an unseen id gets every inference the app makes from an id`() {
        for (id in listOf("cable_fly", "pallof_hold", "sled_push", "kb_swing", "tricep_pushdown", "muscle_up")) {
            val def = ExerciseDef.inferred(id)
            assertEquals(id, def.id)
            assertEquals(ExerciseDef.inferStartPhase(id), def.startsWith, id)
            assertEquals(ExerciseDef.inferKind(id), def.kind, id)
            assertTrue(def.isCustom, id)
            assertEquals(ExerciseDef.ridesStack(id), def.sensorOnStack, id)
        }
    }

    @Test
    fun `the inferences are not the type defaults`() {
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferred("pallof_hold").kind)
        assertEquals(ExerciseKind.CARRY, ExerciseDef.inferred("sled_push").kind)
        assertEquals(StartPhase.CONCENTRIC, ExerciseDef.inferred("cable_row").startsWith)
        assertTrue(ExerciseDef.inferred("lat_pulldown").sensorOnStack, "a stack id rides the stack")
        assertFalse(ExerciseDef.inferred("cable_fly").sensorOnStack, "no other cable id does")
    }

    @Test
    fun `the display name is the id read as words`() {
        assertEquals("Cable fly", ExerciseDef.inferred("cable_fly").displayName)
        assertEquals("Cable fly", ExerciseDef.inferredDisplayName("cable_fly"))
    }

    /**
     * core-model review 2026-11-06, F7. Abbreviations plans use in ids read
     * as the abbreviation, upper case, wherever they sit; every other word
     * keeps the sentence case the app already writes, so a stored row such as
     * "Tricep pushdown" or "Cable fly" still matches and is not rewritten.
     */
    @Test
    fun `abbreviations in an id are written in capitals`() {
        assertEquals("Dumbbell RDL", ExerciseDef.inferredDisplayName("dumbbell_rdl"))
        assertEquals("RDL", ExerciseDef.inferredDisplayName("rdl"))
        assertEquals("KB swing", ExerciseDef.inferredDisplayName("kb_swing"))
        assertEquals("DB row", ExerciseDef.inferredDisplayName("db_row"))
        assertEquals("Seated DB OHP", ExerciseDef.inferredDisplayName("seated_db_ohp"))
        assertEquals("DB RFESS", ExerciseDef.inferredDisplayName("db_rfess"))
        assertEquals("RFE split squat", ExerciseDef.inferredDisplayName("rfe_split_squat"))
        assertEquals("Single leg RDLs", ExerciseDef.inferredDisplayName("single_leg_rdls"), "a plural keeps its s")
        assertEquals("EZ bar curl", ExerciseDef.inferredDisplayName("ez_bar_curl"))
        assertEquals(
            "Rear foot elevated split squat",
            ExerciseDef.inferredDisplayName("rear_foot_elevated_split_squat"),
        )
        assertEquals("Tricep pushdown", ExerciseDef.inferredDisplayName("tricep_pushdown"))
        assertEquals("Kbswing", ExerciseDef.inferredDisplayName("kbswing"), "only a whole word is an abbreviation")
        assertEquals("Dbsnatch", ExerciseDef.inferredDisplayName("dbsnatch"))
    }

    /**
     * What the plan path's fallback leaves to [SetGeometryPolicy.resolve]:
     * body weight is seeded there, through `bodyweightMount`, and the four
     * fields no id can say anything about keep their type defaults.
     */
    @Test
    fun `body weight and the undeclarable geometry are left at the type defaults`() {
        val def = ExerciseDef.inferred("pull_up")
        assertFalse(def.bodyweight, "body weight is resolve()'s to seed, not this function's")
        assertTrue(def.concentricUp)
        assertFalse(def.horizontal)
        assertFalse(def.sensorInverted)
        assertEquals(1.0, def.travelRatio)
        assertNull(ExerciseDef.seedById("pull_up"), "premise: pull_up has no seed entry")
    }
}
