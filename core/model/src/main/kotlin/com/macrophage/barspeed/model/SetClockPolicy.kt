package com.macrophage.barspeed.model

/**
 * How long a hold or a carry lasted, and which instant that is measured from.
 *
 * ## Why the instant is a decision and not an obvious fact
 *
 * A set has two starts once a prep sits in front of it: the tap that begins
 * RECORDING, and the moment the prep finishes and the lifter is actually
 * holding the bar. On a [PrepCase.TIMED] set those differ by the whole prep,
 * and only the second one is the hold.
 *
 * Measured from the tap, a 45 s hang with a 10 s prep records 55 s. Nothing in
 * `session.json` separates the two: `duration_s` and `plannedDuration_s` agree
 * with each other either way, and `startedAt` is not a key in that document's
 * set object. The raw archive is where it shows: its per-set `meta.json`
 * carries `startedAt_ms`, `endedAt_ms` and `prep_s` beside `duration_s`, and
 * `endSet` reads the clock once for both `duration_s` and `endedAt`, so a
 * tap-started hold satisfies `duration_s == floor((endedAt_ms -
 * startedAt_ms) / 1000)` exactly where a correct one falls short of it by
 * about `prep_s`. The other witness is audible: `TimedSetVoice` counts against
 * the same figure, so `"15 seconds"` would arrive with 25 seconds of holding
 * still to go.
 *
 * The same figure decides whether the set counts as met: `setTargetMet` on the
 * in-set screen and the auto-fail rule at `endSet` both compare it against
 * `TIMED_CLOSE_ENOUGH_FRACTION` of the prescription, so a prep folded into it
 * turns a short hold into a passed one.
 *
 * ## Why the tap is still used where no timed prep ran
 *
 * Not symmetry for its own sake: it keeps the number identical to what the app
 * recorded before preps reached timed sets. On a set with no timed prep the tap
 * instant is taken in `beginSet` before the set journal is opened, and the
 * clock instant would be taken after it -- a disk open apart. Measuring the
 * unchanged cases from the unchanged instant means this leaves them alone.
 *
 * ## What this cannot check
 *
 * That `:app` hands in the right instants. `:app` has no test source set, so the
 * rule is pinned here and the wiring that obeys it is compile- and lint-gated
 * only.
 */
object SetClockPolicy {
    /**
     * Whole seconds the set was measured for.
     *
     * [tappedAtMs] is when the lifter started the set. [clockStartedAtMs] is
     * when the set's own clock began ticking, and is null when it never did --
     * the set was ended while its prep was still running.
     *
     * A set abandoned during its prep is zero seconds. That is a measured zero
     * rather than an absence: the hold had not begun, so it lasted no time at
     * all, and it falls short of any prescription exactly as it should.
     */
    fun heldSeconds(case: PrepCase, tappedAtMs: Long, clockStartedAtMs: Long?, endedAtMs: Long): Int = when (case) {
        PrepCase.TIMED -> clockStartedAtMs?.let { wholeSeconds(it, endedAtMs) } ?: 0
        PrepCase.NONE, PrepCase.CUED -> wholeSeconds(tappedAtMs, endedAtMs)
    }

    private fun wholeSeconds(fromMs: Long, toMs: Long): Int = ((toMs - fromMs) / 1_000L).toInt()
}
