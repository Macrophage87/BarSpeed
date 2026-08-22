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
     * The digits are in NOTATION order and stay there. Which stroke is
     * performed first is [ExerciseDef.startsWith]'s business and is not a
     * parameter here: a lifter reading a tempo off a plan reads digit 1 first
     * whichever end of the movement their rep opens at.
     *
     * Both halves of the reading are `TempoSchedule.of`'s and are stated the
     * same way. Vertical work is POSITIONAL -- digit 1 is the down stroke -- so
     * on a lift whose drive goes down, digit 1 is the drive: a triceps
     * pushdown, a lat pulldown, a leg curl. Horizontal work is read by PHASE,
     * digit 1 the eccentric, because a seated row has no up or down for a
     * positional reading to attach to.
     *
     * Getting this from the position alone is v0.1.41's defect, and it would
     * caption the wheel over a pushdown's drive "eccentric" -- a lifter
     * lengthening the wrong half of the rep.
     */
    fun digits(concentricUp: Boolean, horizontal: Boolean): List<TempoDigit> {
        val digit1IsConcentric = if (horizontal) false else !concentricUp
        val downStroke =
            TempoDigit(
                position = DOWN_STROKE,
                label = strokeLabel(horizontal, isConcentric = digit1IsConcentric, movesUp = false),
                caption = phase(digit1IsConcentric),
                choices = choices(DOWN_STROKE),
            )
        val upStroke =
            TempoDigit(
                position = UP_STROKE,
                label = strokeLabel(horizontal, isConcentric = !digit1IsConcentric, movesUp = true),
                caption = phase(!digit1IsConcentric),
                choices = choices(UP_STROKE),
            )
        return listOf(
            downStroke,
            TempoDigit(BOTTOM_PAUSE, PAUSE, after(downStroke.caption), choices(BOTTOM_PAUSE)),
            upStroke,
            TempoDigit(TOP_PAUSE, PAUSE, after(upStroke.caption), choices(TOP_PAUSE)),
        )
    }

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
     * [hasPlannedNext] separates two facts that must not share an answer,
     * exactly as [SetLoadPolicy.seedAddedKg] separates them for load. "The next
     * planned set declares no tempo" is a DECLARATION and is offered none:
     * declaring nothing is a declaration. "There is no next planned set at all"
     * is the ad-hoc case, where the tempo field is the only declaration there
     * is and carrying it forward is what the lifter typed it for.
     *
     * Conflating them is what leaked one exercise's tempo into the next.
     * `restingState` seeded `nextSlot?.tempo ?: p.tempoText` and
     * `advancedState` baked that into the upcoming slot, so an exercise
     * declaring NO tempo inherited the one the previous set ran: it was paced
     * by the voice, counted by the guide instead of by the lifter, given a prep
     * its author never declared, and it recorded that tempo as its own
     * prescription for good. Reachable on the plan this repo publishes.
     */
    fun seedTempo(hasPlannedNext: Boolean, nextDeclaredTempo: String?, lastRanTempo: String?): String? =
        if (hasPlannedNext) nextDeclaredTempo else lastRanTempo

    /**
     * The tempo the lifter ADJUSTED that still stands for the set coming up, or
     * null when that set is offered whatever its own slot declares.
     *
     * The same expression as [SetLoadPolicy.standingStatedAddedKg], with the
     * same four boundaries and for the same reasons; read that function for the
     * long form. A lifter who slows the eccentric on set 1 of an exercise did
     * not mean it for set 1 alone, any more than one who moved up in weight
     * did, and the alternative re-offers the plan's tempo on every later set
     * and paces the lifter against it.
     *
     * [adjustedTempo] is what the lifter set on the wheels as it stood when the
     * set that just finished was written, null when they set nothing.
     *
     * [sameExerciseBlock] bounds the carry, and is
     * [SetLoadPolicy.sameExerciseBlock]'s answer passed in rather than a second
     * statement of what a block is.
     *
     * [lastDeclaredTempo] and [nextDeclaredTempo] are the two slots' frozen
     * `plannedTempo`, never their live `tempo`, which carries the adjustment
     * itself once [carriedIntoNextSet] has baked it in -- comparing those would
     * compare a value against itself. A plan declaring a DIFFERENT tempo for
     * the next set is prescribing a change and it is that tempo the lifter is
     * offered: without it the fix becomes the same defect facing the other way,
     * and an adjustment made to the opener of a block written 3010/4010 would
     * flatten the prescribed contrast.
     *
     * A null on one side only is not that case and is not claimed to be: the
     * plan declared a tempo for one of the two sets and not the other. Null
     * compares unequal to a string, so the carry drops there too -- the safe
     * direction, and stated as what it is. Null on BOTH sides compares equal,
     * but is unreachable rather than a carry: a set that declares no tempo gets
     * no wheels at all, so nothing can have been adjusted for it.
     */
    fun standingAdjustedTempo(
        adjustedTempo: String?,
        sameExerciseBlock: Boolean,
        lastDeclaredTempo: String?,
        nextDeclaredTempo: String?,
    ): String? = adjustedTempo?.takeIf { sameExerciseBlock && lastDeclaredTempo == nextDeclaredTempo }

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

    private fun phase(isConcentric: Boolean): String = if (isConcentric) CONCENTRIC else ECCENTRIC

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
