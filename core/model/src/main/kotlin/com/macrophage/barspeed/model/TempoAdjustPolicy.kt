package com.macrophage.barspeed.model

/**
 * One digit of a tempo prescription, as a control that adjusts one must show it.
 *
 * The digits are POSITIONAL -- digit 1 is the down stroke, digit 3 the up
 * stroke -- so which of them is the eccentric is a property of the LIFT and not
 * of the position. That is what [caption] carries and what [position] alone
 * cannot say: on a triceps pushdown digit 1 is the drive.
 *
 * [choices] rides on the digit rather than being asked for separately, so a
 * wheel needs one object and cannot pair one digit's label with another
 * digit's values.
 */
data class TempoDigit(
    /** 1-based position in the notation, as [Tempo.notation] writes it. */
    val position: Int,
    /**
     * The word for this digit.
     *
     * The same token `TempoSchedule` gives the stroke and the same one the
     * in-set screen prints while the guide is calling it, so the wheel the
     * lifter scrolls and the word they then hear cannot disagree.
     */
    val label: String,
    /** What this digit is for THIS lift: which phase, or which phase it follows. */
    val caption: String,
    /** The values this digit may take, in the order a wheel offers them. */
    val choices: List<String>,
)

/**
 * Pure decisions behind the between-sets tempo control: what each digit of a
 * tempo IS for the lift coming up, which values a digit may take, and how far
 * an adjustment carries. Issue #148.
 *
 * Here rather than beside the caller for the reason [SetLoadPolicy] gives, and
 * that reason survives the arrival of `:app`'s test source set: the two call
 * sites are `restingState` and `advancedState`, file-private top-level
 * functions in `RecordViewModel.kt`, and Kotlin file privacy is not package
 * privacy -- a test one file over cannot name either. Nothing in this file
 * touches Android, a sensor or a clock.
 *
 * ## What it deliberately cannot do
 *
 * It cannot CLEAR a tempo and it cannot ADD one. The ask was to adjust a tempo,
 * and clearing or adding one moves a set across [LeadInPolicy.prepCase]'s
 * boundary: a rep set that acquires a tempo gains a prep, a voice pacing it,
 * the guide taking the rep count off the lifter, and a compliance verdict; one
 * that loses its tempo loses all four. Every value this object can produce
 * comes from [choices], which has no empty entry, and [wheelValues] answers
 * null -- no control at all -- for a set that declares no tempo. Adding one is
 * a feature, and not this one.
 */
object TempoAdjustPolicy {
    /** Digit 1: the down stroke. */
    const val DOWN_STROKE = 1

    /** Digit 2: the pause after the digit-1 stroke. */
    const val BOTTOM_PAUSE = 2

    /** Digit 3: the up stroke. */
    const val UP_STROKE = 3

    /** Digit 4: the pause after the digit-3 stroke. */
    const val TOP_PAUSE = 4

    /** How many digits a tempo has. */
    const val DIGITS = 4

    /**
     * The shortest stroke a wheel may select, in seconds.
     *
     * Measured rather than chosen. `CadencePlan.of` floors every stroke at one
     * second because the runner can only sleep in whole seconds, so a stroke
     * prescribed as 0 is PLAYED as 1 while the compliance scorer goes on
     * grading the lifter against the 0. A control that BUILDS a tempo out of
     * digits must not be able to reach that state. A plan that declares one
     * still can, and [wheelValues] answers null for it rather than quietly
     * raising it.
     *
     * Pauses have no such floor. A pause of 0 is a real pause -- the one where
     * the lifter does not stop -- and the metronome plays it by emitting no
     * beat at all.
     */
    const val MIN_STROKE_S = 1

    /** The largest value a single digit can spell; see [Tempo.parse]'s compact form. */
    const val MAX_DIGIT_S = 9

    /** The up stroke's "as fast as possible" marker. [Tempo.parse] takes it on digit 3 alone. */
    const val EXPLOSIVE = "X"

    private val PAUSE_CHOICES: List<String> = (0..MAX_DIGIT_S).map { it.toString() }

    private val STROKE_CHOICES: List<String> = (MIN_STROKE_S..MAX_DIGIT_S).map { it.toString() }

    private val UP_STROKE_CHOICES: List<String> = STROKE_CHOICES + EXPLOSIVE

    /**
     * The values digit [position] may take, in wheel order.
     *
     * Read off what [Tempo.parse] already refuses rather than restated: "X" is
     * taken on the up stroke and nowhere else, and a component of ten or more
     * needs the dash form, which four single-character wheels do not spell.
     */
    fun choices(position: Int): List<String> = when (position) {
        UP_STROKE -> UP_STROKE_CHOICES
        DOWN_STROKE -> STROKE_CHOICES
        else -> PAUSE_CHOICES
    }

    /**
     * What each digit is, for a lift with this drive direction and plane.
     *
     * NAIVE, and knowingly so: this reads the notation the classic way, digit 1
     * the eccentric and digit 3 the concentric. That is right for a bench press
     * and right for every horizontal machine, and it is wrong for a lift whose
     * drive goes DOWN -- a triceps pushdown, a lat pulldown, a leg curl -- where
     * digit 1 is the drive and moves down. `TempoSchedule.of` reads those
     * positionally, and the app was corrected once for exactly this mistake in
     * v0.1.41.
     *
     * Written in this shape so the differential is a body change rather than a
     * new function: #148 c2 reds it on a pushdown, c3 derives it from the
     * direction.
     */
    fun digits(concentricUp: Boolean, horizontal: Boolean): List<TempoDigit> = listOf(
        TempoDigit(
            position = DOWN_STROKE,
            label = strokeLabel(horizontal, isConcentric = false, movesUp = !concentricUp),
            caption = ECCENTRIC,
            choices = choices(DOWN_STROKE),
        ),
        TempoDigit(BOTTOM_PAUSE, PAUSE, after(ECCENTRIC), choices(BOTTOM_PAUSE)),
        TempoDigit(
            position = UP_STROKE,
            label = strokeLabel(horizontal, isConcentric = true, movesUp = concentricUp),
            caption = CONCENTRIC,
            choices = choices(UP_STROKE),
        ),
        TempoDigit(TOP_PAUSE, PAUSE, after(CONCENTRIC), choices(TOP_PAUSE)),
    )

    /**
     * The four wheel values [tempoText] shows, or null when it is not something
     * four single-character wheels can show.
     *
     * Null rather than a best effort, and that is the point of it: the
     * alternative is opening a control on `3-0-1.5-0` and writing back `3010`,
     * which changes the prescription of a set the lifter never touched a wheel
     * on. Four inputs answer null -- a set that declares no tempo, a component
     * of ten or more, a fractional component, and a stroke below
     * [MIN_STROKE_S] -- and the caller draws no control for any of them.
     */
    fun wheelValues(tempoText: String?): List<String>? {
        val tempo = tempoText?.let { Tempo.parseOrNull(it) } ?: return null
        val spelled =
            listOf(
                wholeSecond(tempo.downS) ?: return null,
                wholeSecond(tempo.bottomPauseS) ?: return null,
                tempo.upS?.let { wholeSecond(it) ?: return null } ?: EXPLOSIVE,
                wholeSecond(tempo.topPauseS) ?: return null,
            )
        // The floor and the ceiling are both this membership test and are not
        // restated above: a component of ten or more spells "10", which is in
        // no wheel, and a zero stroke spells "0", which is in neither stroke
        // wheel. A second guard would be a check no test could kill.
        return spelled.takeIf { values -> values.indices.all { values[it] in choices(it + 1) } }
    }

    /** The tempo these wheel values spell, or null when they spell none. */
    fun compose(values: List<String>): String? {
        if (values.size != DIGITS) return null
        if (values.indices.any { values[it] !in choices(it + 1) }) return null
        return values.joinToString("")
    }

    /**
     * [tempoText] with digit [position] set to [value], or null when the result
     * would not be a tempo.
     *
     * The only route by which a wheel changes anything. It runs the whole
     * string back through [wheelValues] first, so a tempo the control could not
     * have DRAWN cannot be written back through it either.
     */
    fun withDigit(tempoText: String?, position: Int, value: String): String? {
        if (position < DOWN_STROKE || position > TOP_PAUSE) return null
        val values = wheelValues(tempoText)?.toMutableList() ?: return null
        values[position - 1] = value
        return compose(values)
    }

    /**
     * The tempo to offer for the set coming up, or null to offer none.
     *
     * TODAY'S RULE, defect included, stated where a test can reach it.
     * `restingState` seeds its tempo field `nextSlot?.tempo ?: p.tempoText` and
     * `advancedState` bakes that into the upcoming slot, so an exercise
     * declaring NO tempo inherits the one the previous set ran. It is then
     * paced by the voice, counted by the guide instead of by the lifter, given
     * a prep it never declared, and it permanently records a tempo nobody
     * prescribed.
     *
     * [hasPlannedNext] is ignored here, and it is the operand that fixes it. It
     * separates two facts that must not share an answer, exactly as
     * [SetLoadPolicy.seedAddedKg] separates them for load: "the next planned set
     * declares no tempo" is a declaration and must offer none, while "there is
     * no next planned set at all" is the ad-hoc case, where the last tempo is
     * the only thing to go on and carrying it forward is what the lifter typed
     * it for. #148 c2 reds this; c3 stops conflating them.
     *
     * detekt is right that the parameter is unused and is being told so rather
     * than turned off: `config/detekt/detekt.yml` is untouched and the
     * suppression is one function wide. It is here so the differential later on
     * this branch is a body change against a real assertion, rather than a
     * signature change that would stop the red commit compiling at all.
     */
    @Suppress("UnusedParameter")
    fun seedTempo(hasPlannedNext: Boolean, nextDeclaredTempo: String?, lastRanTempo: String?): String? =
        nextDeclaredTempo ?: lastRanTempo

    /**
     * The tempo the lifter ADJUSTED that still stands for the set coming up, or
     * null when that set is offered whatever its own slot declares.
     *
     * Null in every case today, and null is today's answer rather than a
     * placeholder: no control anywhere states a tempo, so there is nothing that
     * could stand. #148 c3 gives this its body, and that body is
     * [SetLoadPolicy.standingStatedAddedKg]'s -- the same four boundaries, for
     * the same reasons, on a different quantity.
     *
     * detekt is right about both the body and the parameters, and is being told
     * so rather than turned off, exactly as [SetLoadPolicy.standingStatedAddedKg]
     * was at the same point in #124: the config file is untouched and the
     * suppression is one function wide. Lifting the seam here, one commit ahead
     * of the change, is what lets the differential be shown red against a real
     * assertion.
     */
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
    fun standingAdjustedTempo(
        adjustedTempo: String?,
        sameExerciseBlock: Boolean,
        lastDeclaredTempo: String?,
        nextDeclaredTempo: String?,
    ): String? = null

    /**
     * The tempo the upcoming planned slot carries once the lifter taps through
     * to it, replacing what the plan declared.
     *
     * [declaredTempo] is the slot's own tempo, still the plan's text because
     * this slot has not been through this function yet. [adjustedTempo] is what
     * the lifter set on the wheels, null when they set nothing, and it is the
     * only thing that displaces the declaration.
     *
     * What the plan prescribed is not written back over. `PlannedSlot`'s frozen
     * declaration is what [standingAdjustedTempo] bounds the carry against, the
     * same way `plannedLoadKg` is for load.
     */
    fun carriedIntoNextSet(declaredTempo: String?, adjustedTempo: String?): String? = adjustedTempo ?: declaredTempo

    private const val ECCENTRIC = "eccentric"

    private const val CONCENTRIC = "concentric"

    private const val PAUSE = "PAUSE"

    private fun after(phase: String): String = "after the $phase"

    /**
     * The word for a stroke.
     *
     * Vertical work is called by DIRECTION, because that is what the lifter
     * hears and what makes a leg curl's `1030` a one-second pull down.
     * Horizontal work is called by PHASE, because there is no up or down on a
     * seated row for a positional word to attach to. Both halves are
     * `TempoSchedule`'s, restated here because `:core:dsp` depends on this
     * module and not the other way about; `TempoLabelContractTest` over there
     * pins the two to the same tokens rather than trusting this comment.
     */
    private fun strokeLabel(horizontal: Boolean, isConcentric: Boolean, movesUp: Boolean): String = when {
        horizontal && isConcentric -> "DRIVE"
        horizontal -> "RETURN"
        movesUp -> "UP"
        else -> "DOWN"
    }

    /** [seconds] written out, or null when it is fractional and so has no whole-second digit. */
    private fun wholeSecond(seconds: Double): String? =
        if (seconds == Math.floor(seconds)) seconds.toInt().toString() else null
}
