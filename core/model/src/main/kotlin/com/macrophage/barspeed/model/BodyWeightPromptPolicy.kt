package com.macrophage.barspeed.model

/**
 * Whether to ask the lifter for a body weight, and when (issue #181).
 *
 * The owner's rule in one sentence: body weight is only needed for bodyweight
 * exercises, so ask for it at the moment a session that contains one is about
 * to start, and only when what is stored is absent or has gone stale. It is
 * not a standing task on the home screen.
 *
 * Here rather than in the Composable that draws the dialog, for
 * [SetLoadPolicy]'s reason: `:app` has one test file over one pure function,
 * so a threshold written into a `@Composable` is a threshold nothing can run
 * against. Everything below is a pure function of a plan session, two nullable
 * numbers and a clock reading; `:app` is left with the dialog and the write.
 *
 * Nothing here decides whether a session may START. The prompt is always
 * skippable and a skip is not a refusal of the session — see [shouldPrompt]'s
 * [skippedThisSession] parameter, which is the only thing that suppresses it
 * once a lifter has said no.
 */
object BodyWeightPromptPolicy {
    /**
     * How long a stored body weight stands before the app asks again.
     *
     * Fourteen days is the owner's figure ("hasn't been updated for two
     * weeks"). It is a nag threshold, not a physiological one: nothing here
     * claims a lifter's mass changes on a fortnightly cadence, only that two
     * weeks is how long the app will keep quiet about a number it cannot
     * verify.
     */
    const val STALE_AFTER_DAYS = 14L

    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    /** [STALE_AFTER_DAYS] in milliseconds, the unit both stored times use. */
    const val STALE_AFTER_MS = STALE_AFTER_DAYS * MS_PER_DAY

    /**
     * What the app knows about the stored body weight, as four states rather
     * than a boolean.
     *
     * [UNKNOWN_AGE] is the one that matters and the one a boolean would have
     * lost. A figure written by a build that stored no timestamp — every build
     * up to and including 0.1.44 — has a value and no date. That is neither
     * fresh nor absent: it could have been typed yesterday or a year ago, and
     * the app cannot tell which. Rendering it as fresh would silence the
     * prompt for exactly the lifters most likely to be carrying an old number,
     * and rendering it as absent would throw away a figure that is probably
     * right. It is its own state, it asks once per session until answered, and
     * the dialog says plainly that it does not know how old the number is.
     */
    enum class StoredBodyWeight {
        /** Nothing usable is stored — never set, or stored as a non-positive number. */
        ABSENT,

        /** A value with no recorded set-at time: written before this app recorded one. */
        UNKNOWN_AGE,

        /** A value dated less than [STALE_AFTER_DAYS] days ago. */
        DATED_FRESH,

        /** A value dated [STALE_AFTER_DAYS] days ago or more. */
        DATED_STALE,
    }

    /**
     * Classify what is stored.
     *
     * [kg] non-positive is [ABSENT][StoredBodyWeight.ABSENT], matching what
     * `SettingsStore.bodyWeightKg` already publishes — a 0 in this slot is an
     * absence dressed as a number, and it is the one number the app reads as
     * the base load of every bodyweight set.
     *
     * [setAtMs] null, zero or negative is [UNKNOWN_AGE][StoredBodyWeight.UNKNOWN_AGE]:
     * there is no epoch-millis reading in this app's lifetime for which those
     * are a real answer, so they are the absence of a time rather than a very
     * old one.
     *
     * A time in the FUTURE reads as fresh, deliberately. The phone's clock can
     * move backwards — a timezone fix, a manual correction, an NTP step — and
     * the value really was written at a moment this device called "now". The
     * failure this whole policy exists to remove is being nagged for a number
     * that is already correct, so clock skew resolves towards silence.
     */
    fun stateOf(kg: Double?, setAtMs: Long?, nowMs: Long): StoredBodyWeight = when {
        kg == null || kg <= 0 || !kg.isFinite() -> StoredBodyWeight.ABSENT
        setAtMs == null || setAtMs <= 0L -> StoredBodyWeight.UNKNOWN_AGE
        nowMs - setAtMs >= STALE_AFTER_MS -> StoredBodyWeight.DATED_STALE
        else -> StoredBodyWeight.DATED_FRESH
    }

    /**
     * Whole days since the stored value was written, or null when there is no
     * usable time to measure from. Truncating, so a value written 13 hours ago
     * is 0 days old rather than half a day.
     *
     * Null for a future timestamp as well as a missing one: "-1 days ago" is
     * not a sentence to show a lifter, and [stateOf] already treats that case
     * as fresh, so nothing needs a number for it.
     */
    fun ageDays(setAtMs: Long?, nowMs: Long): Long? {
        if (setAtMs == null || setAtMs <= 0L) return null
        val elapsed = nowMs - setAtMs
        return if (elapsed < 0L) null else elapsed / MS_PER_DAY
    }

    /**
     * Does this session put the lifter's own body on the bar at any point?
     *
     * One `bodyweight: true` exercise anywhere in the session is enough: the
     * stored figure is the base load of that exercise's every set, so a
     * session of nine barbell exercises and one set of dips needs the number
     * exactly as much as a session of nothing but dips. An empty session, or
     * one of purely loaded work, needs nothing and is never asked — that is
     * the whole point of #181.
     */
    fun sessionNeedsBodyWeight(session: PlanSessionDef): Boolean = session.exercises.any { it.bodyweight }

    /**
     * Ask now?
     *
     * The gate is an AND of three independent facts and every one of them can
     * silence it on its own: the session must actually need a body weight,
     * what is stored must not be dated-fresh, and the lifter must not already
     * have declined within this session.
     *
     * [skippedThisSession] is the lifter's own "not now" and outranks
     * everything else here. It is deliberately a parameter rather than
     * something this object remembers: the span a skip lasts for is a fact
     * about the training session, which lives in the caller's state, and an
     * object holding it would keep it across a process death that ended the
     * session and lose it on a configuration change that did not.
     *
     * Not a decision about starting the session. A caller that gets `true`
     * shows a dialog; a caller that gets `false` starts. Both start.
     */
    fun shouldPrompt(
        session: PlanSessionDef,
        kg: Double?,
        setAtMs: Long?,
        nowMs: Long,
        skippedThisSession: Boolean,
    ): Boolean = !skippedThisSession &&
        sessionNeedsBodyWeight(session) &&
        stateOf(kg, setAtMs, nowMs) != StoredBodyWeight.DATED_FRESH

    /**
     * Why the app is asking, in the lifter's terms, said once where the prompt
     * is rather than in a settings screen nobody opens.
     *
     * Every clause is pinned by `BodyWeightPromptPrerequisiteTest`: an absent
     * figure makes `SetLoadPolicy.totalKg` record the ADDED load alone, and a
     * stale one is used exactly as a current one would be, with nothing
     * downstream able to tell the difference. "An estimate is fine" is the
     * owner's instruction and is load-bearing — a lifter who thinks this needs
     * a weigh-in skips it, and the app then records the pull-up at 5 kg.
     */
    const val WHY_IT_MATTERS =
        "This is the base load recorded for every bodyweight set - pull-ups, dips, push-ups - " +
            "so an out-of-date figure quietly mis-states the load and power of all of them. " +
            "An estimate is fine; it is a base load, not a weigh-in."

    /**
     * What the app currently holds, stated so the lifter can tell "confirm
     * this" from "type something".
     *
     * The [UNKNOWN_AGE][StoredBodyWeight.UNKNOWN_AGE] line says the app does
     * not know when the number was set rather than guessing at it. That is the
     * honest sentence and it is also the useful one: a lifter who set it last
     * week can dismiss the dialog knowing why it appeared.
     */
    fun storedLine(state: StoredBodyWeight, kg: Double?, ageDays: Long?, unit: WeightUnit): String = when (state) {
        StoredBodyWeight.ABSENT -> "No body weight stored yet."
        StoredBodyWeight.UNKNOWN_AGE ->
            "Stored: ${unit.format(kg ?: 0.0)}, set before the app recorded when."
        StoredBodyWeight.DATED_STALE, StoredBodyWeight.DATED_FRESH ->
            "Stored: ${unit.format(kg ?: 0.0)}, ${agePhrase(ageDays)}."
    }

    private fun agePhrase(ageDays: Long?): String = when {
        ageDays == null -> "set at an unknown time"
        ageDays <= 0L -> "set today"
        ageDays == 1L -> "set 1 day ago"
        else -> "set $ageDays days ago"
    }
}
