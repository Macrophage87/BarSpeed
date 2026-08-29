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
 * `:app` has one test file over one pure function, so a rule written beside its
 * Compose caller is a rule nothing runs. What is decided here is which control
 * is drawn and what its dialog says; whether the lifter reads that dialog
 * before tapping through it is a [Field] question and no test in this
 * repository can answer it.
 *
 * This commit states TODAY's rule so the differentials that follow are red
 * against a rule rather than against a missing symbol: today the only way into
 * a session is the home screen's hero card, which starts whatever plan is
 * active, refuses nothing and announces nothing. #182.
 */
object PlanStartPolicy {
    // Every parameter is deliberately unread while this states today's rule.
    // The suppression goes away with the rule, in the commit that implements
    // the differentials; it is here so that the signature the differentials are
    // written against exists before them.
    @Suppress("UnusedParameter")
    fun decide(plan: PlanFile?, lifecycle: PlanLifecycle, activePlanName: String?): PlanStartDecision {
        // Today's behaviour, as a function: the hero card navigates to "record"
        // whatever the plan says, refuses nothing and announces nothing. Every
        // parameter is deliberately unread here; the differentials in the next
        // commit are what give them work.
        return PlanStartDecision.Startable(switch = null)
    }
}
