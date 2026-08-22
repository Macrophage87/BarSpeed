package com.macrophage.barspeed.model

/**
 * Which sets get a prep before them, how long it is, who gets to say, and where
 * the bounds are enforced.
 *
 * @see PrepCase for the shapes of set a prep can precede.
 *
 * ## What a prep is
 *
 * The seconds between the lifter starting a set and the set beginning -- the
 * first movement call on a tempo'd lift, the word that starts the clock on a
 * hold or a carry. `LeadInPlan` in `:core:dsp` lays those seconds out beat by
 * beat, and this decides how many of them there are.
 *
 * The two live in different modules on purpose. The beat layout is DSP; the
 * precedence is a plan question, and [PlanFile.validate] is here and cannot see
 * `:core:dsp` at all -- `core/dsp` declares `api(project(":core:model"))` and
 * `core/model` declares no project dependency, so the arrow runs one way only.
 *
 * ## Why it is per exercise
 *
 * A Romanian deadlift with straps is not ready five seconds after START: the
 * lifter's hands are busy fastening. A cable machine is ready in two. One number
 * for every exercise is wrong for both, and the lifter hears the difference as
 * either being rushed under a loaded bar or standing braced through fifteen
 * seconds of nothing.
 *
 * What the plan author is being asked to estimate is the time the lifter's hands
 * are UNAVAILABLE -- straps, chalk, a hook grip, a belt, lying down on a machine
 * -- not the walk to the rack or the loading of plates, which happen before the
 * set is started at all.
 *
 * ## Precedence: adjusted, then declared, then the default
 *
 * The lifter's adjustment beats the plan's declaration. A plan here is
 * regenerated weekly by a model from a fresh prompt and nothing carries a
 * correction forward, so a plan-wins rule would discard the lifter's fix every
 * Monday.
 *
 * A device-local value that silently wins would normally be invisible to the
 * one reader who could repair the plan. Both values are published,
 * `plannedPrep_s` beside `prep_s`, and whenever they differ the lifter adjusted
 * it. The correction survives the week AND the plan converges on reality
 * instead of drifting from it.
 *
 * ## Three tiers of bound, none redundant
 *
 * 1. `plan.schema.json` states `minimum` and `maximum`, which fails early for
 *    anyone running a validator over a generated plan.
 * 2. [PlanFile.validate] refuses the same range, naming the JSON path. This is
 *    the real gate: the app imports plans pasted out of a chat window that no
 *    validator has ever seen.
 * 3. [clamp] here, on the DEVICE path, which neither of the two above covers --
 *    a value read back from settings written by another build, or handed in by a
 *    control added later.
 *
 * The third is load-bearing rather than defensive. `LeadInPlan.of` builds its
 * beats with `List(prepS)`, so a negative prep throws
 * `IllegalArgumentException: Illegal Capacity: -1` inside `:app`, where nothing
 * catches it, in the middle of starting a set -- and the set that was about to
 * be recorded is lost.
 */
object LeadInPolicy {
    /**
     * The prep when nothing says otherwise, in seconds.
     *
     * The single statement of this number. It used to be `GuidedCadence.LEAD_IN_S`
     * in `:core:dsp`, read by the runner itself; that constant is gone rather
     * than left standing beside this one, because two constants meaning the same
     * thing are two facts that can disagree and the way they disagree is
     * somebody changing one of them.
     */
    const val DEFAULT_S = 5

    /**
     * No prep at all is legal: nothing is spoken before the set begins.
     * Refusing it would mean deciding for a lifter who wants a machine set to
     * start the instant they tap.
     */
    const val MIN_S = 0

    /**
     * A typo ceiling, not a correctness bound. Two minutes of standing still
     * before a set is not a prescription, and a plan that meant 12 and wrote 120
     * should be told rather than obeyed.
     */
    const val MAX_S = 120

    /**
     * Below this the launch phrase is clipped: at 1 only `Brace` is spoken and
     * the `Ready` beat two seconds out is dropped; at 0 nothing is spoken before
     * the set begins.
     *
     * This equals `LeadInPlan.PHRASE_S` in `:core:dsp` -- the length of the
     * launch phrase -- and is deliberately NOT written as it, which this module
     * could not do in any case. Three constants in this codebase are 2 and all
     * three mean different things: how long the launch phrase is, the shortest
     * stroke that can carry a merged rep announcement, and the shortest stroke
     * counted out loud. `LeadInPlan.PHRASE_S` and `CadencePlan.MERGE_MIN_STROKE_S`
     * each already carry this warning; this is the same warning for the fourth.
     *
     * Not a bound. A prep below it is legal and is warned about rather than
     * refused: a two-second cable set is exactly what this feature was asked for,
     * and someone who wants no countdown should be able to have none.
     */
    const val MIN_USEFUL_S = 2

    /** Held inside [MIN_S]..[MAX_S]. */
    fun clamp(prepS: Int): Int = prepS.coerceIn(MIN_S, MAX_S)

    /**
     * What the plan prescribed: its own declaration, or [DEFAULT_S] where it
     * declared nothing.
     *
     * Never null, and that is the point. "The plan declared nothing" still leaves
     * a prep that was prescribed -- the default -- and reporting absence instead
     * would leave a reader of the export unable to tell an adjustment from a
     * declaration without knowing this constant.
     */
    fun planned(declaredS: Int?): Int = clamp(declaredS ?: DEFAULT_S)

    /**
     * What actually plays: the lifter's adjustment, else the plan's declaration,
     * else the default.
     */
    fun resolve(declaredS: Int?, adjustedS: Int?): Int = clamp(adjustedS ?: declaredS ?: DEFAULT_S)

    /**
     * The word a TIMED set's prep ends on, or null for a kind whose timed sets
     * get no prep.
     *
     * ## Who owns the opening word, case by case
     *
     * `LeadInPlan` in `:core:dsp` lays out the prep and deliberately does not
     * pick the word the set opens on -- that is the property which makes a
     * seated row open on `Drive` and a chest press on `Return` without anyone
     * remembering to write it. It stays true here. Two other components own the
     * word, one per case:
     *
     * - [PrepCase.CUED]: `TempoSchedule`, resolved from the plane, the drive
     *   direction and the start phase, and spoken by `CadencePlan`'s beat 0.
     * - [PrepCase.TIMED]: this function, from the kind alone. There is no
     *   `TempoSchedule` on a timed set to ask.
     *
     * Exhaustive over [ExerciseKind] on purpose: a kind added later cannot
     * compile without someone deciding what its timed sets say.
     */
    fun timedStartWord(kind: ExerciseKind): String? = when (kind) {
        ExerciseKind.HOLD -> "Hold"
        ExerciseKind.CARRY -> "Carry"
        // A timed set of a rep-based lift has no movement to name, and an
        // explosive lift is judged on peak velocity with deliberately no cadence
        // to open.
        ExerciseKind.DYNAMIC, ExerciseKind.EXPLOSIVE -> null
    }

    /**
     * Which kind of prep, if any, a set of this shape gets.
     *
     * One statement of the rule with four readers: the record flow deciding
     * whether a set is guided at all AND what follows its prep, the import gate
     * warning that a declared prep is inert, and the screen deciding whether to
     * offer the adjuster. Written out four times it would be four rules, and the
     * way they diverge is a plan warned about a prep that does play, or a
     * control that changes nothing.
     *
     * [isTimed] is asked first and answered on its own. It is not a modifier on
     * the tempo question: it says how the set is MEASURED, and a set measured on
     * the clock never plays a `TempoSchedule` whether or not a tempo string
     * reached it. `PlanFile.validate` refuses that pair, but the device path has
     * no validator -- an ad-hoc set carries whatever `tempoInput` holds -- so
     * the combination is reachable and is decided here rather than left to
     * operator precedence.
     */
    fun prepCase(hasTempo: Boolean, isTimed: Boolean, kind: ExerciseKind): PrepCase = when {
        // A timed set runs a stopwatch rather than a cadence, and gets a prep
        // where the movement has a name to open on. Pairing the two here rather
        // than testing the kind twice is what makes a prep that ends in silence
        // unreachable.
        isTimed -> if (timedStartWord(kind) != null) PrepCase.TIMED else PrepCase.NONE
        // An explosive lift is judged on peak velocity and is deliberately given
        // no tempo to follow.
        hasTempo && kind != ExerciseKind.EXPLOSIVE -> PrepCase.CUED
        else -> PrepCase.NONE
    }

    /**
     * Whether a set of this shape plays a prep at all.
     *
     * The yes/no half of [prepCase], for the two readers that need nothing
     * finer: the import gate and the prep adjuster on the rest screen.
     */
    fun playsPrep(hasTempo: Boolean, isTimed: Boolean, kind: ExerciseKind): Boolean =
        prepCase(hasTempo, isTimed, kind) != PrepCase.NONE

    /**
     * Whether a set of this shape speaks, given the lifter's audio-cues setting.
     *
     * A tempo'd set's voice IS the feature: prescribing a tempo is asking to be
     * paced, so its prep and its stroke calls speak whatever the toggle says. A
     * timed set's voice is an aid, and the toggle declines it -- with cues off a
     * hold is silent end to end, prep included.
     *
     * Stated here rather than left implicit in which call sites happen to read
     * the flag. Two readers in `:app` ask it, one for the prep and one for the
     * countdown during the set, and a rule spread across two call sites is two
     * rules.
     */
    fun speaks(case: PrepCase, audioCues: Boolean): Boolean = when (case) {
        PrepCase.CUED -> true
        PrepCase.NONE, PrepCase.TIMED -> audioCues
    }
}

/**
 * The kinds of set a prep can precede, and what the prep runs into.
 *
 * A prep exists to give the lifter the seconds between tapping START and the
 * instant the set actually begins. What makes that instant announceable differs
 * by case, and so does what happens at it, which is why this is three states
 * rather than a boolean.
 */
enum class PrepCase {
    /**
     * No prep. The set begins the moment it is started.
     */
    NONE,

    /**
     * A tempo'd set of a rep-based lift. The prep runs into the cadence, and the
     * word it ends on is the first stroke call, which `TempoSchedule` picks.
     */
    CUED,

    /**
     * A hold or a carry. There is no cadence to run into: the prep ends on the
     * word [LeadInPolicy.timedStartWord] picks, and the set's own clock starts
     * there rather than at the tap -- see [SetClockPolicy].
     */
    TIMED,
}
