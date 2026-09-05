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
    /**
     * The values this digit may be STEPPED to on THIS lift, in wheel order.
     *
     * The wheel's OFFER, which is narrower than what the notation can spell:
     * [TempoAdjustPolicy.spellable] is that one. The two are separate because
     * whether a digit may be moved to a value depends on the digit's ROLE on
     * the lift, and whether a string is a tempo does not.
     */
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
 * comes from a digit's [TempoDigit.choices], which has no empty entry, and
 * [wheelValues] answers null -- no control at all -- for a set that declares
 * no tempo. Adding one is a feature, and not this one.
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
     * digits must not be able to reach that state. Since #251 the PLAN import
     * gate refuses one too, by path, under plan schema 1.12 -- see
     * [PlanSetDef.validate]. Two doors are still open and are named rather
     * than claimed shut: a set already recorded with a zero stroke, and the
     * ad-hoc tempo TEXT FIELD, which takes free text through [Tempo.parseOrNull]
     * and is not this issue's ask. [wheelValues] answers null for either
     * rather than quietly raising it, so the wheel is not drawn at all.
     *
     * Pauses have no such floor. A pause of 0 is a real pause -- the one where
     * the lifter does not stop -- and the metronome plays it by emitting no
     * beat at all.
     */
    const val MIN_STROKE_S = 1

    /** The largest value a single digit can spell; see [Tempo.parse]'s compact form. */
    const val MAX_DIGIT_S = 9

    /**
     * The drive's "as fast as possible" marker.
     *
     * [Tempo.parse] takes it on digit 3 alone, which is why the wheel offers it
     * on digit 3 alone even though the RULE is about the concentric stroke --
     * see [wheelChoices] and #258.
     */
    const val EXPLOSIVE = "X"

    private val PAUSE_CHOICES: List<String> = (0..MAX_DIGIT_S).map { it.toString() }

    private val STROKE_CHOICES: List<String> = (MIN_STROKE_S..MAX_DIGIT_S).map { it.toString() }

    private val UP_STROKE_SPELLABLE: List<String> = STROKE_CHOICES + EXPLOSIVE

    /**
     * The drive's wheel: X FIRST, then 1..9.
     *
     * The order is the whole of it. X is faster than a one-second stroke, so a
     * range that runs 1..9 and then X puts the fastest value at the slow end,
     * one tap past nine -- where a mis-tap reaches it and a lifter who wants it
     * has to walk through eight values they do not want.
     */
    private val CONCENTRIC_STROKE_CHOICES: List<String> = listOf(EXPLOSIVE) + STROKE_CHOICES

    /**
     * The values digit [position] may SPELL, in wheel order.
     *
     * The NOTATION's alphabet, and only that. Read off what [Tempo.parse]
     * already refuses rather than restated: "X" is taken on the up stroke and
     * nowhere else, and a component of ten or more needs the dash form, which
     * four single-character wheels do not spell.
     *
     * [TempoDigit.choices] is the other half, and the two are deliberately not
     * one list. This one answers "is this string a tempo four wheels can
     * SHOW", which is what [wheelValues] and [compose] ask. That one answers
     * "may this digit be STEPPED to this value on THIS lift", which is
     * narrower, because it depends on which stroke is the drive. One list
     * answering both questions is one flag with two jobs.
     */
    fun spellable(position: Int): List<String> = when (position) {
        UP_STROKE -> UP_STROKE_SPELLABLE
        DOWN_STROKE -> STROKE_CHOICES
        else -> PAUSE_CHOICES
    }

    /**
     * Which digit of the notation is the CONCENTRIC stroke, for a lift with
     * this drive direction and plane: [DOWN_STROKE] or [UP_STROKE].
     *
     * The two-step rule, stated once here and read by [digits] rather than
     * spelled a second time. Horizontal work is read by PHASE -- digit 3 is
     * the drive, whatever the plan declared beside `plane`, because a seated
     * row has no up or down for a positional reading to attach to. Vertical
     * work is POSITIONAL: digit 3 while the drive moves up, digit 1 when it
     * moves down, which is what makes a leg curl's `1030` a one-second pull
     * down.
     *
     * `TempoSchedule.of` and [VelocityLossRegime] say the same thing as
     * `digit1IsConcentric = if (horizontal) false else !concentricUp`, and
     * `VelocityLossRegimeTempoScheduleContractTest` in `:core:dsp` pins two of
     * the three equal.
     */
    fun concentricDigit(concentricUp: Boolean, horizontal: Boolean): Int =
        if (!horizontal && !concentricUp) DOWN_STROKE else UP_STROKE

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
        val concentric = concentricDigit(concentricUp, horizontal)
        val digit1IsConcentric = concentric == DOWN_STROKE
        val downStroke =
            TempoDigit(
                position = DOWN_STROKE,
                label = strokeLabel(horizontal, isConcentric = digit1IsConcentric, movesUp = false),
                caption = phase(digit1IsConcentric),
                choices = wheelChoices(DOWN_STROKE, concentric),
            )
        val upStroke =
            TempoDigit(
                position = UP_STROKE,
                label = strokeLabel(horizontal, isConcentric = !digit1IsConcentric, movesUp = true),
                caption = phase(!digit1IsConcentric),
                choices = wheelChoices(UP_STROKE, concentric),
            )
        return listOf(
            downStroke,
            TempoDigit(BOTTOM_PAUSE, PAUSE, after(downStroke.caption), wheelChoices(BOTTOM_PAUSE, concentric)),
            upStroke,
            TempoDigit(TOP_PAUSE, PAUSE, after(upStroke.caption), wheelChoices(TOP_PAUSE, concentric)),
        )
    }

    /**
     * The values a wheel on digit [position] OFFERS, on a lift whose concentric
     * stroke is digit [concentricDigit]. Issue #251.
     *
     * Three roles, not four positions:
     *
     * - The CONCENTRIC stroke is the drive, and "X" -- as fast as possible --
     *   is a drive instruction. It sits BELOW one second, because it is the
     *   fastest stroke there is and one second is merely the fastest NUMBER.
     * - The ECCENTRIC stroke is 1..9. An explosive eccentric is not a
     *   prescription a lifter can follow, and offering one on the digit the
     *   voice guide calls the RETURN is what this issue was raised for: the
     *   alphabet used to be read off the position alone, so every vertical
     *   concentric-down lift -- a lat pulldown, a triceps pushdown, a leg curl
     *   -- got X on the wrong stroke.
     * - The two pauses are 0..9. A pause of 0 is the pause where the lifter
     *   does not stop.
     *
     * The X is offered only where [spellable] says one can be WRITTEN, which
     * today is digit 3 alone. On a vertical concentric-down lift the drive is
     * digit 1, so that lift is offered no X on either stroke -- not because
     * this rule withholds it but because [Tempo] cannot hold one there.
     * Widening the notation is #258, and it turns this on by moving that one
     * statement rather than this one.
     *
     * Private, and reached only through [TempoDigit.choices]: a caller holding
     * a digit holds the lift it was built for, so there is no way to pair one
     * lift's role with another lift's alphabet.
     */
    private fun wheelChoices(position: Int, concentricDigit: Int): List<String> = when {
        position != DOWN_STROKE && position != UP_STROKE -> PAUSE_CHOICES
        position != concentricDigit -> STROKE_CHOICES
        EXPLOSIVE in spellable(position) -> CONCENTRIC_STROKE_CHOICES
        else -> STROKE_CHOICES
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
        // restated above: a component of ten or more spells "10", which the
        // notation's alphabet does not carry, and a zero stroke spells "0",
        // which is in neither stroke's. A second guard would be a check no
        // test could kill.
        return spelled.takeIf { values -> values.indices.all { values[it] in spellable(it + 1) } }
    }

    /**
     * The tempo these wheel values spell, or null when they spell none.
     *
     * [spellable] and not the wheel's own offer, deliberately: this asks
     * whether four characters ARE a tempo, and a digit the lifter never
     * touched keeps whatever its plan declared. Whether a digit may be MOVED
     * to a value is [withDigit]'s question and is asked of that digit's
     * [TempoDigit.choices].
     */
    fun compose(values: List<String>): String? {
        if (values.size != DIGITS) return null
        if (values.indices.any { values[it] !in spellable(it + 1) }) return null
        return values.joinToString("")
    }

    /**
     * [tempoText] with [digit] set to [value], or null when the result would
     * not be a tempo, or when that digit does not OFFER that value.
     *
     * The only route by which a wheel changes anything. It runs the whole
     * string back through [wheelValues] first, so a tempo the control could not
     * have DRAWN cannot be written back through it either.
     *
     * A [TempoDigit] and not a bare position, because the alphabet a digit may
     * be moved through depends on the LIFT and a position cannot say which
     * lift it belongs to. Taking the digit makes that impossible to omit: a
     * caller that has not asked [digits] which lift it is adjusting has
     * nothing to pass.
     */
    fun withDigit(tempoText: String?, digit: TempoDigit, value: String): String? {
        if (digit.position < DOWN_STROKE || digit.position > TOP_PAUSE) return null
        if (value !in digit.choices) return null
        val values = wheelValues(tempoText)?.toMutableList() ?: return null
        values[digit.position - 1] = value
        return compose(values)
    }

    /**
     * The value [digit] takes after [delta] taps of a stepper, or null when
     * [tempoText] has no such control to tap.
     *
     * The index moves along [TempoDigit.choices] and is COERCED into that
     * list's indices,
     * never wrapped. Wrapping would turn a 9 into a 1 on one mis-tap, which on
     * the down stroke is an eight-second difference in what the voice paces and
     * what the compliance scorer grades; clamping makes the end of the range
     * feel like the end of the range. A tap that cannot move is what [canStep]
     * reports, so the button can be drawn disabled rather than lying.
     *
     * The alphabet is the digit's own, so nothing new is reachable through
     * stepping. "X" is the FIRST entry of the drive's list, which makes `-`
     * from 1 give X and `+` from X give 1 without a second statement of where
     * X is legal. (This paragraph used to say X was the LAST entry and the top
     * of the range. That was the defect #251 was raised for, not a description
     * of it, and the sentence is deleted rather than reworded.)
     *
     * Null exactly where [wheelValues] is null -- a set that declares no tempo,
     * a fractional or two-character component, a stroke below [MIN_STROKE_S] --
     * plus a digit position outside the notation. The caller draws no control
     * at all for any of them.
     *
     * A digit value rather than the whole tempo, because the state entry point
     * this feeds takes a digit and routes it through [withDigit]: the guarantee
     * that a tempo the control could not have DRAWN cannot be written back is
     * inherited rather than restated here.
     */
    fun steppedValue(tempoText: String?, digit: TempoDigit, delta: Int): String? {
        val current = valueAt(tempoText, digit.position) ?: return null
        val choices = digit.choices
        // indexOf CAN answer -1, and could not before #251 split the two
        // alphabets: wheelValues checks a value against what the NOTATION can
        // spell and a wheel now offers less than that, so a plan declaring
        // 30X0 on a pulldown draws an X on a digit whose wheel has none. The
        // coercion is what handles it and no branch stands in front of it: -1
        // is where X belongs on a range that starts at 1, because X is the
        // value BELOW one second, so `-` gives 1, `+` gives 1, and a
        // hypothetical two-place tap gives 2. The state is escapable and not
        // re-enterable, which is what the lifter needs from it. An explicit
        // "off the alphabet lands on the first value" branch stood here for
        // one commit and was deleted: it answered every tap the screen can
        // produce identically, so no test could kill it, and a guard nothing
        // can kill reads as coverage while guarding nothing.
        return choices[(choices.indexOf(current) + delta).coerceIn(choices.indices)]
    }

    /**
     * Whether one tap of [delta] on [digit] would actually move it.
     *
     * The enabled state of a stepper button, decided here rather than in the
     * screen: false at both ends of a digit's [TempoDigit.choices] and false
     * wherever
     * [steppedValue] is null. A button that is drawn enabled and does nothing is
     * the state #154 was raised about, one control over.
     */
    fun canStep(tempoText: String?, digit: TempoDigit, delta: Int): Boolean {
        val current = valueAt(tempoText, digit.position) ?: return false
        return steppedValue(tempoText, digit, delta) != current
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
     * The expression [SetLoadPolicy.standingStatedAddedKg] carried until #143,
     * with the same four boundaries and for the same reasons; read that
     * function for the long form of the first three. THE FOURTH NOW DIFFERS,
     * deliberately: where the plan declares a different value for the set
     * coming up, the load carries the lifter's correction as a distance onto
     * the plan's own next number, and a tempo does not. A tempo is a
     * prescription rather than a quantity on a bar -- there is no sense in
     * which a plan stepping 3-0-1 to 4-0-1 is asking for "one more than
     * whatever you did" -- so a plan that changes the tempo is offered as
     * written, and the statement drops. Unchanged by #143 and stated here
     * because the sentence that used to point at that function for all four
     * boundaries is no longer true of the fourth. A lifter who slows the eccentric on set 1 of an exercise did
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

    /**
     * The value digit [position] currently shows, or null when there is no
     * control on it.
     *
     * [wheelValues] is the whole guard: it is null for every tempo the control
     * cannot draw, and `getOrNull` covers a position outside the notation
     * without a second bounds test to keep in agreement with [DIGITS].
     */
    private fun valueAt(tempoText: String?, position: Int): String? = wheelValues(tempoText)?.getOrNull(position - 1)

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
