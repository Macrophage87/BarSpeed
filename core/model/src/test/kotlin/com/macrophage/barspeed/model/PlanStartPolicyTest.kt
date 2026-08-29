package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a plan that could not be read is not startable, and says why`() {
        // The screen already has this state -- PlanDetailScreen draws "This
        // plan could not be read." -- and a START control beside that sentence
        // would offer a session built from nothing. The reason is carried
        // rather than the absence, so the card cannot look like a screen that
        // forgot to draw a button.
        val decision = PlanStartPolicy.decide(plan = null, PlanLifecycle.STAGED, activePlanName = "Winter Block")

        assertEquals(
            PlanStartDecision.Unstartable("This plan could not be read, so there is nothing to start."),
            decision,
        )
    }

    @Test
    fun `a plan prescribing no sets is not startable`() {
        // validate() refuses an empty plan at the import gate, so this is not
        // reachable through today's import. It is reachable through storage:
        // PlanRepository.decode deliberately returns a plan that no longer
        // validates, so a row staged under an older rule still reads back. A
        // start from one of those opens a session with an empty queue.
        val empty = PlanFile(schemaVersion = "1.0", planName = "Winter Block", sessions = emptyList())

        val expected = PlanStartDecision.Unstartable("This plan prescribes no sets, so there is nothing to start.")

        assertEquals(expected, PlanStartPolicy.decide(empty, PlanLifecycle.ACTIVE, activePlanName = null))
        assertEquals(expected, PlanStartPolicy.decide(plan(sets = 0), PlanLifecycle.STAGED, activePlanName = null))
    }

    @Test
    fun `starting a staged plan is the approval that promotes it`() {
        // The approval gate is the only thing standing between an LLM-authored
        // document and the app following it. Starting a staged plan cannot walk
        // round it, so the start IS the approval and the button says so: the
        // lifter agrees to promote it, not merely to lift.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.STAGED, activePlanName = "Autumn Block")
        val switch = (decision as PlanStartDecision.Startable).switch

        assertNotNull(switch)
        assertEquals("Start \"Winter Block\"?", switch.title)
        assertEquals("APPROVE & START", switch.confirmLabel)
        assertTrue(switch.body.contains("staged"), "the lifter is told it was never approved")
        assertTrue(switch.body.contains("active plan"), "and that approving makes it the active one")
    }

    @Test
    fun `starting an archived plan says which plan it displaces`() {
        // PlanDao.activate archives the current active row before marking the
        // new one, so the plan the lifter was following stops being followed.
        // Naming it is the difference between a switch and a plan quietly
        // going missing from the home screen next session.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.ARCHIVED, activePlanName = "Autumn Block")
        val switch = (decision as PlanStartDecision.Startable).switch

        assertNotNull(switch)
        assertEquals("START", switch.confirmLabel)
        assertTrue(switch.body.contains("Autumn Block"), "the displaced plan is named, not implied")
        assertFalse(switch.body.contains("staged"), "an archived plan was approved once already")
    }

    @Test
    fun `with no active plan at all there is nothing to say was displaced`() {
        // First plan on a fresh install, or every plan archived. Claiming
        // something was replaced when nothing was is the same defect as staying
        // silent about a real switch, pointing the other way.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.STAGED, activePlanName = null)
        val switch = (decision as PlanStartDecision.Startable).switch

        assertNotNull(switch)
        assertFalse(switch.body.contains("archiv"), "nothing was archived, so nothing claims to have been")
    }

    @Test
    fun `a status this app never wrote is a switch, not a silent start`() {
        // UNKNOWN reaches the same prompt as ARCHIVED and for the same reason:
        // whatever the row is, starting it writes a new active plan. It does
        // not reach STAGED's wording, which would claim to know the row is
        // awaiting an approval nobody recorded.
        val decision = PlanStartPolicy.decide(plan(), PlanLifecycle.UNKNOWN, activePlanName = "Autumn Block")
        val switch = (decision as PlanStartDecision.Startable).switch

        assertNotNull(switch)
        assertEquals("START", switch.confirmLabel)
        assertFalse(switch.body.contains("staged"))
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
