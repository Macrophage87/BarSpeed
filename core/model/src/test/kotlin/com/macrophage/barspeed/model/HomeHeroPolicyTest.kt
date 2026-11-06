package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [HomeHeroPolicy]: what Home's hero card says (UX review 2026-11-06,
 * home-nav F2, F3, F4).
 */
class HomeHeroPolicyTest {
    private fun session(name: String, blocks: Int = 1, setsPerBlock: Int = 3) = PlanSessionDef(
        name = name,
        exercises = List(blocks) { i ->
            PlanExerciseDef(exercise = "e$i", sets = List(setsPerBlock) { PlanSetDef(reps = 5) })
        },
    )

    private val plan = listOf(session("Push A", blocks = 2), session("Pull A"), session("Legs A"))

    private fun open(name: String?, sets: Int) = LatestSession(
        sessionId = 7L,
        startedAtMs = 0L,
        endedAtMs = null,
        setCount = sets,
        lastSetEndedAtMs = 1L,
        nextOrderIdx = sets,
        planSessionName = name,
    )

    @Test
    fun `an open session wins over the next plan session`() {
        val hero = HomeHeroPolicy.hero(true, plan, open("Pull A", 4), "Push A")
        assertEquals(HomeHero.Continue("Pull A", setsSaved = 4, nextSetNumber = 5), hero)
        assertEquals("CONTINUE Pull A · set 5", HomeHeroPolicy.buttonLabel(hero))
        assertEquals("Open session · 4 sets saved", HomeHeroPolicy.detail(hero))
    }

    @Test
    fun `an open ad-hoc session is named as one`() {
        val hero = HomeHeroPolicy.hero(true, null, open(null, 1), null)
        assertEquals("CONTINUE Ad-hoc session · set 2", HomeHeroPolicy.buttonLabel(hero))
        assertEquals("Open session · 1 set saved", HomeHeroPolicy.detail(hero))
    }

    @Test
    fun `the session after the last one done is next, with that session's counts only`() {
        val hero = HomeHeroPolicy.hero(true, plan, null, "Push A")
        assertEquals(HomeHero.Next("Pull A", blockCount = 1, setCount = 3), hero)
        assertEquals("START Pull A", HomeHeroPolicy.buttonLabel(hero))
        assertEquals("Next: Pull A · 1 exercise · 3 sets", HomeHeroPolicy.detail(hero))
    }

    @Test
    fun `nothing done under this plan starts at its first session`() {
        assertEquals(HomeHero.Next("Push A", blockCount = 2, setCount = 6), HomeHeroPolicy.hero(true, plan, null, null))
    }

    @Test
    fun `the last session done wraps round to the first`() {
        assertEquals("Push A", HomeHeroPolicy.nextSession(plan, "Legs A")?.name)
    }

    @Test
    fun `a last session the plan does not hold falls back to the picker`() {
        val hero = HomeHeroPolicy.hero(true, plan, null, "Renamed day")
        assertEquals(HomeHero.Pick, hero)
        assertEquals("START SESSION", HomeHeroPolicy.buttonLabel(hero))
    }

    @Test
    fun `two sessions sharing the last one's name fall back to the picker`() {
        assertNull(HomeHeroPolicy.nextSession(listOf(session("A"), session("B"), session("A")), "A"))
    }

    @Test
    fun `a plan with no sessions falls back to the picker`() {
        assertEquals(HomeHero.Pick, HomeHeroPolicy.hero(true, emptyList(), null, null))
    }

    @Test
    fun `before the plan source answers the hero is loading, not no plan`() {
        assertEquals(HomeHero.Loading, HomeHeroPolicy.hero(false, null, null, null))
        assertNull(HomeHeroPolicy.detail(HomeHero.Loading))
    }

    @Test
    fun `no plan once loaded says so`() {
        assertEquals(HomeHero.NoPlan, HomeHeroPolicy.hero(true, null, null, null))
    }
}
