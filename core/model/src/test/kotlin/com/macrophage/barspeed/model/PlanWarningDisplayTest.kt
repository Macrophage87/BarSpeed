package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The import gate's two tiers and lifter wording (UX review 2026-11-06,
 * plans F3). Every input here goes through the real [PlanImport.parse], so a
 * producer reworded in `Plan.kt` or `PlanImport.kt` without its rule moving in
 * [PlanWarningDisplay] reds the coverage test below rather than silently
 * becoming an UNRECOGNISED line.
 */
class PlanWarningDisplayTest {
    private fun plan(vararg exercises: String, extraSession: Boolean = false): String {
        val second = if (extraSession) {
            """,{"name":"Day B","exercises":[{"exercise":"back_squat","sets":[{"reps":5}]}]}"""
        } else {
            ""
        }
        return """
            {"schemaVersion":"1.4","planName":"P","sessions":[
              {"name":"Day A","exercises":[${exercises.joinToString(",")}]}$second
            ]}
        """.trimIndent()
    }

    private fun lines(text: String): List<PlanWarningLine> {
        val parsed = PlanImport.parse(text)
        assertEquals(emptyList(), parsed.errors, "fixture must import: ${parsed.errors}")
        return PlanWarningDisplay.lines(parsed.warnings, parsed.plan)
    }

    /** One exercise per producer, so every rule has a real warning behind it. */
    private val everyProducer = listOf(
        """{"exercise":"bench_press","sets":[{"reps":5,"load_kg":60},{"reps":5},{"reps":5,"loadKg":60}]}""",
        """{"exercise":"back_squat","sets":[{"reps":5,"restS":90}]}""",
        """{"exercise":"front_squat","sets":[{"reps":5,"notes":"slow"}]}""",
        """{"exercise":"deadlift","rpeTarget":8,"sets":[{"reps":3}]}""",
        """{"exercise":"db_bench","start":"top","implement":"dumbbell","sets":[{"reps":8,"load_kg":40}]}""",
        """{"exercise":"cossack_squat","sets":[{"reps":5}]}""",
        """{"exercise":"lat_pulldown","sets":[{"reps":10}]}""",
        """{"exercise":"plank","progression":"reps","sets":[{"duration_s":30}]}""",
        """{"exercise":"goblet_squat","start":"bottom","implementCount":2,"sets":[{"reps":8}]}""",
        """{"exercise":"deadlift","start":"top","sets":[{"reps":3}]}""",
        """{"exercise":"overhead_press","kind":"explosive","sets":[{"reps":3}]}""",
        """{"exercise":"sled_pull","kind":"dynamic","start":"bottom","sets":[{"reps":5}]}""",
        """{"exercise":"side_plank","sets":[{"reps":5}]}""",
        """{"exercise":"hip_thrust","prep_s":3,"sets":[{"reps":5}]}""",
        """{"exercise":"barbell_row","prep_s":0,"sets":[{"reps":5,"tempo":"3010"}]}""",
        """{"exercise":"romanian_deadlift","sensors":2,"sets":[{"reps":5}]}""",
        """{"exercise":"seated_row","sets":[{"reps":10}]}""",
        """{"exercise":"triceps_pushdown","concentric":"down","sets":[{"reps":10}]}""",
        """{"exercise":"pull_up","sets":[{"reps":5}]}""",
        """{"exercise":"front_squat","description":"a","notes":"b","sets":[{"reps":5}]}""",
        """{"exercise":"back_squat","additional_notes":"c","sets":[{"reps":5}]}""",
    )

    @Test
    fun `every warning the importer writes has a rule, and every rule is reached`() {
        val all = lines(plan(*everyProducer.toTypedArray()))
        val unrecognised = all.filter { it.rule == PlanWarningRule.UNRECOGNISED }
        assertEquals(emptyList(), unrecognised.map { it.raw }, "warnings with no lifter wording")
        assertEquals(
            PlanWarningRule.entries.toSet() - PlanWarningRule.UNRECOGNISED,
            all.map { it.rule }.toSet(),
        )
    }

    @Test
    fun `a dropped load is top tier, 1-based, and named by display name`() {
        val line = lines(plan(everyProducer[0])).single { it.rule == PlanWarningRule.LOAD_DROPPED }
        assertEquals(PlanWarningTier.RECORDS_WRONG, line.tier)
        assertEquals("Set 3 of Bench Press has no load: it would record as body weight.", line.text)
        assertTrue(line.raw.startsWith("sessions[0].exercises[0].sets[2]: \"loadKg\""), line.raw)
    }

    @Test
    fun `the day is named only when the plan has more than one`() {
        val line = lines(plan(everyProducer[0], extraSession = true))
            .single { it.rule == PlanWarningRule.LOAD_DROPPED }
        assertEquals("Set 3 of Bench Press (Day A) has no load: it would record as body weight.", line.text)
    }

    @Test
    fun `the plan's own declaration being followed is a note`() {
        val line = lines(plan(everyProducer[11])).single { it.rule == PlanWarningRule.KIND_OVERRIDDEN }
        assertEquals(PlanWarningTier.NOTE, line.tier)
        assertEquals("Sled pull: the plan sets its movement type. The plan is followed.", line.text)
    }

    @Test
    fun `top tier comes first, each tier keeping the import's order`() {
        val all = lines(plan(*everyProducer.toTypedArray()))
        val firstNote = all.indexOfFirst { it.tier == PlanWarningTier.NOTE }
        assertTrue(firstNote > 0)
        assertTrue(all.drop(firstNote).all { it.tier == PlanWarningTier.NOTE })
        val parsed = PlanImport.parse(plan(*everyProducer.toTypedArray())).warnings
        val notes = all.filter { it.tier == PlanWarningTier.NOTE }.map { it.raw }
        assertEquals(parsed.filter { it in notes }, notes)
    }

    @Test
    fun `no lifter line carries a JSON path or a raw id`() {
        lines(plan(*everyProducer.toTypedArray())).forEach {
            assertFalse("sessions[" in it.text, it.text)
            assertFalse("bench_press" in it.text || "lat_pulldown" in it.text, it.text)
        }
    }

    @Test
    fun `a misspelt key that is not a load is still top tier`() {
        val line = lines(plan(everyProducer[1])).single { it.rule == PlanWarningRule.KEY_MISSPELT }
        assertEquals(PlanWarningTier.RECORDS_WRONG, line.tier)
        assertEquals("Set 1 of Back Squat: \"restS\" is misspelt (should be \"rest_s\"), so it is ignored.", line.text)
    }

    @Test
    fun `a plan-level extra key reads as the plan`() {
        val text = """{"schemaVersion":"1.4","planName":"P","author":"x","sessions":[""" +
            """{"name":"A","exercises":[{"exercise":"back_squat","sets":[{"reps":5}]}]}]}"""
        val line = lines(text).single()
        assertEquals(PlanWarningRule.UNKNOWN_KEY, line.rule)
        assertEquals("The plan: extra field \"author\" ignored.", line.text)
    }

    @Test
    fun `an unknown sentence is shown loudly, not folded into the notes`() {
        val line = PlanWarningDisplay.of("sessions[0].exercises[0]: something new.", null)
        assertEquals(PlanWarningRule.UNRECOGNISED, line.rule)
        assertEquals(PlanWarningTier.RECORDS_WRONG, line.tier)
        assertEquals("Exercise 1 of session 1: something new.", line.text)
    }

    @Test
    fun `notes label and relay text`() {
        assertEquals("1 note", PlanWarningDisplay.notesLabel(1))
        assertEquals("5 notes", PlanWarningDisplay.notesLabel(5))
        assertEquals("H:\n- a\n- b", PlanWarningDisplay.relayText("H", listOf("a", "b")))
    }

    @Test
    fun `the shipped example and the real plan fixture produce no unrecognised warning`() {
        listOf("/examples/plan.example.json", "/real-plan-lower-body.json").forEach { path ->
            val text = checkNotNull(javaClass.getResourceAsStream(path)) { path }.bufferedReader().readText()
            val parsed = PlanImport.parse(text)
            val unrecognised = PlanWarningDisplay.lines(parsed.warnings, parsed.plan)
                .filter { it.rule == PlanWarningRule.UNRECOGNISED }
            assertEquals(emptyList(), unrecognised.map { it.raw }, path)
        }
    }
}
