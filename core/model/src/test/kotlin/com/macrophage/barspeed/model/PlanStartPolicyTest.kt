package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether a plan can be started from the plans tab, and what the lifter is told
 * before a start that also switches which plan the app follows.
 *
 * The pins in this commit are today's rule, moved somewhere a test can run on
 * it: the home screen's hero card starts whatever plan is active, refuses
 * nothing and announces nothing. The differentials that follow are red against
 * that rule rather than against a missing symbol. #182.
 *
 * Nothing here touches Android, a screen or a database — a decoded plan and two
 * scalars in, one decision out.
 */
class PlanStartPolicyTest {
    private fun plan(name: String = "Winter Block", sets: Int = 3, sessions: Int = 1) = PlanFile(
        schemaVersion = "1.0",
        planName = name,
        sessions =
        (1..sessions).map { s ->
            PlanSessionDef(
                name = "Session $s",
                exercises =
                listOf(
                    PlanExerciseDef(
                        exercise = "back_squat",
                        sets = (1..sets).map { PlanSetDef(reps = 5, loadKg = 100.0) },
                    ),
                ),
            )
        },
    )

    @Test
    fun `the active plan starts with nothing to agree to`() {
        // The one case that already exists: the hero card starts the active
        // plan, and nothing about the app changes when it does. This pin
        // survives the differentials -- it is the case they are measured
        // against.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.ACTIVE, activePlanName = null)

        assertEquals(PlanStartDecision.Startable(switch = null), decision)
    }

    @Test
    fun `an active plan is still startable while some other row claims the name`() {
        // Two imports of one training block is the ordinary case, and the
        // active one of them is not made unstartable by the other existing.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.ACTIVE, activePlanName = "Winter Block")

        assertEquals(PlanStartDecision.Startable(switch = null), decision)
    }

    @Test
    fun `a lifecycle is read from the status literal the database stores`() {
        // The three literals are `PlanEntity.STATUS_*`, pinned against those
        // constants from `:core:data`, which is the module that can see both.
        // What is pinned here is the other half: anything else is UNKNOWN, and
        // UNKNOWN is an answer rather than a silent fallback to "archived".
        assertEquals(PlanLifecycle.ACTIVE, PlanLifecycle.of("active"))
        assertEquals(PlanLifecycle.STAGED, PlanLifecycle.of("staged"))
        assertEquals(PlanLifecycle.ARCHIVED, PlanLifecycle.of("archived"))
        assertEquals(PlanLifecycle.UNKNOWN, PlanLifecycle.of(null))
        assertEquals(PlanLifecycle.UNKNOWN, PlanLifecycle.of(""))
        assertEquals(PlanLifecycle.UNKNOWN, PlanLifecycle.of("ACTIVE"))
    }

    @Test
    fun `a switch prompt carries its own consent word`() {
        // A shape pin, not a rule pin: whatever the wording turns out to be,
        // the prompt is the only carrier of the fact that starting writes a new
        // active plan, so a caller cannot read the question and skip the write.
        val prompt = PlanSwitchPrompt(title = "t", body = "b", confirmLabel = "START")

        assertEquals("START", PlanStartDecision.Startable(prompt).switch?.confirmLabel)
        assertNull(PlanStartDecision.Startable(switch = null).switch)
    }
}
