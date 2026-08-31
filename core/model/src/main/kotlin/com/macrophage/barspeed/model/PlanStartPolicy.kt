package com.macrophage.barspeed.model

/**
 * Where a stored plan stands: staged awaiting approval, active, or archived by
 * a later activation.
 *
 * The three names are `PlanEntity.STATUS_*` in `:core:data`, which is the
 * canonical statement of the literals — this enum cannot import it, since
 * `:core:data` depends on this module and not the other way round. The coupling
 * is pinned from the side that can see both, by `PlanLifecycleContractTest` in
 * `:core:data`, rather than left to two copies of three strings agreeing by
 * habit.
 *
 * [UNKNOWN] is a real answer, not a fallback for tidiness. A row whose status
 * is none of the three is a row this app did not write, and reading it as
 * "archived" would put a plan into a switch prompt whose wording claims to know
 * what the row means.
 */
enum class PlanLifecycle {
    ACTIVE,
    STAGED,
    ARCHIVED,
    UNKNOWN,
    ;

    companion object {
        fun of(status: String?): PlanLifecycle = when (status) {
            "active" -> ACTIVE
            "staged" -> STAGED
            "archived" -> ARCHIVED
            else -> UNKNOWN
        }
    }
}

/**
 * What the lifter must be told before a start that also switches which plan the
 * app is following, and the word on the button that agrees to it.
 */
data class PlanSwitchPrompt(val title: String, val body: String, val confirmLabel: String)

/** What a START control on a plan does, or why there is no control to draw. */
sealed interface PlanStartDecision {
    /**
     * There is nothing to start. [reason] is drawn where the control would have
     * been: a plan that cannot be started and says nothing about it looks
     * exactly like a screen that forgot to draw a button.
     */
    data class Unstartable(val reason: String) : PlanStartDecision

    /**
     * A session can be started from this plan.
     *
     * [switch] is null when this plan is already the active one, so starting it
     * changes no app state and needs no consent. When it is non-null it carries
     * both facts at once, deliberately: what the lifter is agreeing to, and
     * that agreeing writes a new active plan. They are the same fact — the
     * consent exists *because* of the write — so splitting them into a boolean
     * beside a string would let a caller ask one and act on the other.
     */
    data class Startable(val switch: PlanSwitchPrompt?) : PlanStartDecision
}

/**
 * Whether a plan can be started, and what starting it costs.
 *
 * Pure, and in `:core:model` for the reason [RecordingServicePolicy] is here:
 * no test on the CI path can render a Compose caller, so a rule written beside
 * one is a rule nothing runs. What is decided here is which control
 * is drawn and what its dialog says; whether the lifter reads that dialog
 * before tapping through it is a [Field] question and no test in this
 * repository can answer it.
 *
 * The three refusals and the two promptings below are #182's whole rule. What
 * it is NOT is a guard against starting a session on top of one already
 * running: that state is not reachable from here, and inventing a check
 * against a signal that does not exist would be a claim stronger than its
 * evidence. Reaching the plans tab means the "record" nav entry was popped,
 * which clears `RecordViewModel` and every buffer in it; an abandoned session
 * row is left open with `endedAtMs` null and nothing queries for one. See the
 * commit body.
 */
object PlanStartPolicy {
    /** Drawn where the control would have been when the stored JSON will not decode. */
    const val UNREADABLE = "This plan could not be read, so there is nothing to start."

    /** Drawn when the plan decodes but prescribes nothing to do. */
    const val NO_SETS = "This plan prescribes no sets, so there is nothing to start."

    /** The consent word on a start that switches plans. */
    const val START = "START"

    /** The consent word on a start that also performs the approval. */
    const val APPROVE_AND_START = "APPROVE & START"

    fun decide(plan: PlanFile?, lifecycle: PlanLifecycle, activePlanName: String?): PlanStartDecision {
        if (plan == null) return PlanStartDecision.Unstartable(UNREADABLE)
        // Counted rather than taken from validate(): a row staged under an
        // older rule still decodes, and PlanRepository.decode returns it on
        // purpose. An empty queue is a session the lifter cannot do anything
        // with, and it would have opened a session row to hold it.
        val sets = plan.sessions.sumOf { session -> session.exercises.sumOf { it.sets.size } }
        if (sets == 0) return PlanStartDecision.Unstartable(NO_SETS)
        // Already active: starting writes nothing, so there is nothing to ask
        // about. This is the hero card's case, and it keeps the hero card's
        // behaviour.
        if (lifecycle == PlanLifecycle.ACTIVE) return PlanStartDecision.Startable(switch = null)

        val staged = lifecycle == PlanLifecycle.STAGED
        return PlanStartDecision.Startable(
            PlanSwitchPrompt(
                title = "Start \"${plan.planName}\"?",
                body =
                buildString {
                    append(
                        if (staged) {
                            "This plan is staged and has never been approved. Starting it approves " +
                                "it and makes it the active plan."
                        } else {
                            "Starting this makes it the active plan."
                        },
                    )
                    append(" Every new session follows it until you switch again.")
                    // Named, not implied. PlanDao.activate archives whatever is
                    // active before marking this one, so the plan the lifter
                    // has been following stops being followed -- and the only
                    // other place that would show is the plan quietly missing
                    // from the home screen next session.
                    activePlanName?.let { append(" \"$it\" is archived.") }
                },
                confirmLabel = if (staged) APPROVE_AND_START else START,
            ),
        )
    }
}
