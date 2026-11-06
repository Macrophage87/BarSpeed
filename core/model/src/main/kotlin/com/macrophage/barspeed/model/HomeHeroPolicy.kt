package com.macrophage.barspeed.model

/**
 * What Home's hero card offers (UX review 2026-11-06, home-nav F2, F3, F4;
 * record-setup F8).
 *
 * The hero used to read START SESSION in every case and open the session
 * picker, so a lifter coming back mid-workout saw the same card as on a fresh
 * day, and one starting a day had to remember which session was next. This
 * says which of the cases applies; the card only draws it.
 *
 * Here rather than in `:app` for the reason [SessionResumePolicy] is: no test
 * on the CI path can reach a decision written beside a `@Composable`.
 */
sealed interface HomeHero {
    /** The plan source has not answered yet: draw neither a plan nor "No active plan". */
    data object Loading : HomeHero

    /** No plan is active; the button opens Record for an ad-hoc start. */
    data object NoPlan : HomeHero

    /**
     * A session is open and [SessionResumePolicy.offer] would ask about it.
     * [nextSetNumber] is the session's set count plus one: the number the next
     * set takes in the session, not its position in the plan's queue.
     */
    data class Continue(val sessionName: String, val setsSaved: Int, val nextSetNumber: Int) : HomeHero

    /** The plan session after the last one done, with that session's own counts. */
    data class Next(val sessionName: String, val blockCount: Int, val setCount: Int) : HomeHero

    /** A plan is active but which session is next is not known; the button opens the picker. */
    data object Pick : HomeHero
}

object HomeHeroPolicy {
    /** What an open session with no plan session name is called on the card. */
    const val AD_HOC_NAME = "Ad-hoc session"

    /**
     * The hero for this moment.
     *
     * [planLoaded] is false until the active-plan source has emitted once, and
     * the answer is then [HomeHero.Loading], never [HomeHero.NoPlan]: on a cold
     * launch that copy used to stand in for a plan that was only still being
     * read. [planSessions] is the active plan's sessions, or null with no
     * plan. [openSession] is what [SessionResumePolicy.offer] returned, so the
     * resume rule stays in one place. [lastDonePlanSessionName] is the plan
     * session name of the newest History row recorded under this plan, or null
     * when none is.
     */
    fun hero(
        planLoaded: Boolean,
        planSessions: List<PlanSessionDef>?,
        openSession: LatestSession?,
        lastDonePlanSessionName: String?,
    ): HomeHero {
        if (openSession != null) {
            return HomeHero.Continue(
                sessionName = openSession.planSessionName ?: AD_HOC_NAME,
                setsSaved = openSession.setCount,
                nextSetNumber = openSession.setCount + 1,
            )
        }
        if (!planLoaded) return HomeHero.Loading
        if (planSessions == null) return HomeHero.NoPlan
        val next = nextSession(planSessions, lastDonePlanSessionName) ?: return HomeHero.Pick
        return HomeHero.Next(
            sessionName = next.name,
            blockCount = next.exercises.size,
            setCount = next.exercises.sumOf { it.sets.size },
        )
    }

    /**
     * The plan session after [lastDone] in plan order, wrapping to the first;
     * the first when nothing has been done under this plan.
     *
     * Null, meaning "let the lifter pick", when the plan has no sessions, when
     * [lastDone] names no session in it (renamed, or from an edited plan), or
     * when it names more than one: plan order cannot say which of two
     * same-named sessions was done.
     */
    fun nextSession(planSessions: List<PlanSessionDef>, lastDone: String?): PlanSessionDef? {
        if (planSessions.isEmpty()) return null
        if (lastDone == null) return planSessions.first()
        val matches = planSessions.indices.filter { planSessions[it].name == lastDone }
        if (matches.size != 1) return null
        return planSessions[(matches.single() + 1) % planSessions.size]
    }

    /** The primary button's words. */
    fun buttonLabel(hero: HomeHero): String = when (hero) {
        is HomeHero.Continue -> "CONTINUE ${hero.sessionName} · set ${hero.nextSetNumber}"
        is HomeHero.Next -> "START ${hero.sessionName}"
        HomeHero.Loading, HomeHero.NoPlan, HomeHero.Pick -> "START SESSION"
    }

    /** The line above the button, or null for none. */
    fun detail(hero: HomeHero): String? = when (hero) {
        is HomeHero.Continue ->
            "Open session · ${if (hero.setsSaved == 1) "1 set" else "${hero.setsSaved} sets"} saved"
        is HomeHero.Next -> "Next: ${hero.sessionName} · ${count(hero.blockCount, "exercise")} · " +
            count(hero.setCount, "set")
        HomeHero.Pick -> "Pick today's session"
        HomeHero.Loading, HomeHero.NoPlan -> null
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
}
