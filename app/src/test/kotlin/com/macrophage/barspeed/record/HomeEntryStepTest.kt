package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.LatestSession
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [homeEntryStep]: what Record does about the way in Home's hero chose (UX
 * review 2026-11-06, home-nav F2, F3).
 *
 * Written without a local run: this container has no Android SDK, so `:app`
 * does not build here and CI is the first thing to execute this file.
 */
class HomeEntryStepTest {
    private val pushA =
        PlanSessionDef(
            name = "Push A",
            exercises = listOf(PlanExerciseDef(exercise = "bench_press", sets = listOf(PlanSetDef(reps = 5)))),
        )

    private fun offer(name: String?) = LatestSession(
        sessionId = 1L,
        startedAtMs = 0L,
        endedAtMs = null,
        setCount = 2,
        lastSetEndedAtMs = 1L,
        nextOrderIdx = 2,
        planSessionName = name,
    )

    @Test
    fun `continue waits for the offer`() {
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.CONTINUE, RecordState(), null))
    }

    /** Acting before the plan arrived would read the row as gone from the plan. */
    @Test
    fun `continue on a plan row waits for that plan session to load`() {
        val s = RecordState(resumeOffer = offer("Push A"))
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.CONTINUE, s, null))
        assertEquals(
            HomeEntryStep.CONTINUE,
            homeEntryStep(RecordEntry.CONTINUE, s.copy(planSessions = listOf(pushA)), null),
        )
    }

    @Test
    fun `continue on an ad-hoc row needs no plan`() {
        val s = RecordState(resumeOffer = offer(null))
        assertEquals(HomeEntryStep.CONTINUE, homeEntryStep(RecordEntry.CONTINUE, s, null))
    }

    @Test
    fun `start new waits for the offer, then declines it`() {
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.NEW, RecordState(), null))
        assertEquals(
            HomeEntryStep.START_NEW,
            homeEntryStep(RecordEntry.NEW, RecordState(resumeOffer = offer(null)), null),
        )
    }

    @Test
    fun `preview waits for the named session, then opens it`() {
        val checked = RecordState(resumeChecked = true)
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.PREVIEW, checked, "Push A"))
        assertEquals(
            HomeEntryStep.PREVIEW,
            homeEntryStep(RecordEntry.PREVIEW, checked.copy(planSessions = listOf(pushA)), "Push A"),
        )
    }

    /**
     * Focused review of batch1-plan-warnings: opening the preview before the
     * resume check answered left SETUP, where alone "Continue this session?"
     * is offered, so a died-mid-session lifter got a second row instead.
     */
    @Test
    fun `preview waits for the resume check even when its session is loaded`() {
        val loaded = RecordState(planSessions = listOf(pushA))
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.PREVIEW, loaded, "Push A"))
    }

    @Test
    fun `preview waits while a Continue offer is up, and opens once it is declined`() {
        val offered = RecordState(planSessions = listOf(pushA), resumeChecked = true, resumeOffer = offer("Push A"))
        assertEquals(HomeEntryStep.WAIT, homeEntryStep(RecordEntry.PREVIEW, offered, "Push A"))
        assertEquals(
            HomeEntryStep.PREVIEW,
            homeEntryStep(RecordEntry.PREVIEW, offered.copy(resumeOffer = null), "Push A"),
        )
    }

    @Test
    fun `preview is dropped once Continue it was taken`() {
        val continued = RecordState(planSessions = listOf(pushA), resumeChecked = true, resumeContinued = true)
        assertEquals(HomeEntryStep.DROP, homeEntryStep(RecordEntry.PREVIEW, continued, "Push A"))
    }

    @Test
    fun `anything the lifter did first wins`() {
        val s = RecordState(stage = Stage.PREVIEW, resumeOffer = offer(null), planSessions = listOf(pushA))
        assertEquals(HomeEntryStep.DROP, homeEntryStep(RecordEntry.CONTINUE, s, null))
        assertEquals(HomeEntryStep.DROP, homeEntryStep(RecordEntry.PREVIEW, s, "Push A"))
    }

    @Test
    fun `no entry is nothing to do`() {
        assertEquals(HomeEntryStep.DROP, homeEntryStep(null, RecordState(), null))
    }
}
