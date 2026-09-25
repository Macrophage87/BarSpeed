package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan prompt's reading guide states the planned / working / actual
 * layers of export 1.23 as facts, and nothing more (#157, recommendation
 * section 5, with the owner's rulings of 2026-09-25).
 *
 * `PLAN_PROMPT` is the only copy of the export's reading rules a model is
 * actually handed -- the COPY PLAN PROMPT button puts it on the clipboard -- so
 * a key the schema declares and the prompt never explains is a key a coach
 * reads by guessing. The guess this file exists against is the #157 defect
 * itself: a working figure above the plan read as an error, and one below it
 * read as a failed set.
 *
 * RED WHEN WRITTEN: the prompt names none of the five 1.23 keys. The test that
 * finds the guide's bullet fails first, and every other test here reads that
 * bullet.
 *
 * FACTS ONLY, and two readings are refused. The prompt gives no instruction on
 * how to write the next floor -- that is the plan-writer's job, not a rule of
 * the export -- and it draws no conclusion from a long measured rest: the
 * owner, "With a gym that a lot of people are using, timing can't easily be
 * predicted in advance." The last test holds the bullet to both.
 *
 * Reads the real `GuideScreen.kt` through the test resources, as
 * [GuidePromptContractTest] does and for its reason: a copy would drift.
 */
class PlanPromptWorkingTargetContractTest {
    private val prompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    /** The reading guide's bullet for the three layers, one line of the prompt. */
    private fun bullet(): String {
        val matches = prompt.lines().filter { it.trimStart().startsWith("- PLANNED, WORKING AND ACTUAL") }
        assertEquals(1, matches.size, "the plan prompt carries no single bullet for the planned/working/actual layers")
        return matches.single()
    }

    private fun assertStates(fact: String) = assertTrue(fact in bullet(), "the reading guide does not state: $fact")

    @Test
    fun `the reading guide names every planned, working and actual key`() {
        listOf(
            "plannedReps", "plannedLoad_kg", "plannedDuration_s", "plannedTempo", "rest_s",
            "workingReps", "workingLoad_kg", "workingDuration_s", "tempoPrescribed",
            "reps", "load_kg", "duration_s", "tempoCompliance", "restMeasured_s",
        ).forEach { assertStates("\"$it\"") }
    }

    /** The owner's own model: the plan is a floor and the buttons raise it. */
    @Test
    fun `a working figure above the plan is stated as the in-app raise, not an error`() {
        assertStates("productive floor")
        assertStates("in-app buttons")
        assertStates("not an error")
        assertStates("A working figure differs from its planned figure where I changed it in the app.")
    }

    /** "It's completed even if the target is lowered, just note the discrepancy." */
    @Test
    fun `a met lowered target is stated as completed, not a failure`() {
        assertStates("a set that met it is COMPLETED")
        assertStates("not a failure")
    }

    /** Absence is read off `workingLoad_kg`, which every set a v20 build recorded carries. */
    @Test
    fun `what an absent working key means is keyed off workingLoad_kg`() {
        assertStates("On a set that carries \"workingLoad_kg\"")
        assertStates("an absent \"workingReps\" means the set had no rep target")
        assertStates("an absent \"workingDuration_s\" means the set was not a hold or a carry")
        assertStates(
            "an absent \"plannedTempo\" means no plan declared a tempo for that set: an added " +
                "set never has one, even when it ran the tempo of its block.",
        )
        assertStates("On a set without \"workingLoad_kg\"")
        assertStates("do not assume the plan's figures ran")
    }

    /** `rest_s` is a minimum; only a shorter measured rest is a discrepancy; the working tempo is named. */
    @Test
    fun `rest_s is a minimum and only a shorter measured rest is a discrepancy`() {
        assertStates("\"rest_s\" is the planned rest and it is a MINIMUM")
        assertStates("when it is absent, the countdown ran the app's default")
        assertStates("\"restMeasured_s\" is computed from two clock instants")
        assertStates("Only a \"restMeasured_s\" shorter than \"rest_s\" is a discrepancy.")
        assertStates("\"tempoPrescribed\" is the working tempo")
    }

    /** No reading of a long rest, and no instruction on how to write the next floor. */
    @Test
    fun `the reading guide states facts and no coaching`() {
        val text = bullet().lowercase()
        for (refused in listOf("fatigue", "readiness", "recover", "next floor", "next plan", "next session")) {
            assertTrue(refused !in text, "the reading guide draws a conclusion the owner refused: $refused")
        }
    }
}
