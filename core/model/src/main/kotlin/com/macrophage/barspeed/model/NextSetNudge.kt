package com.macrophage.barspeed.model

/**
 * Which dimension of an exercise moves when the lifter had headroom left
 * (#214), declared per exercise by the plan's `progression` key.
 *
 * The owner's words: *"have within the excersize JSON whether or not to
 * increase on reps (or time) or weight when underloaded. Then adjust the
 * follow on grid to suggest those changes. The user can still change it on
 * the usual screen. Default to weight though."* And, separately: *"In some
 * cases, there's a need to not change weight or reps, just allow that as
 * another option, in which case don't show the increasing prompt."*
 *
 * [WEIGHT] is what an absent declaration means, so every plan written against
 * schema 1.10 or earlier behaves exactly as it did before this key existed.
 * That default is a decision and not a fallback: load is what almost every
 * ladder in this app already moves, and a plan that wanted otherwise now has
 * a way to say so.
 *
 * [NONE] is not "no opinion" -- it is the opinion that this exercise holds its
 * load and its reps across its sets, so nothing is offered however the set was
 * rated. Absence and [NONE] are therefore different statements, which is why
 * the key is optional AND has a fourth value rather than three values and an
 * omission meaning "hold".
 */
enum class ProgressionKind {
    WEIGHT,
    REPS,
    TIME,
    NONE,
    ;

    /**
     * The phrase a full-session view puts on this exercise's header, before
     * the session starts (#235).
     *
     * Four kinds, four phrases, drawn on the exercise header and never on a
     * set line -- what an exercise steps up on is declared on the exercise.
     * The record screen's session preview and the plan detail screen both
     * call this and nothing else, so the two cannot describe one plan two
     * ways.
     *
     * [NONE] says "holds load" rather than saying nothing, and it is the case
     * this function exists for: an exercise declared `"none"` shows no
     * post-set grid by design, so before this its dimension was unreadable at
     * every moment of the session rather than merely until the first rating.
     *
     * It answers for the KIND, not for the declaration, and that is the whole
     * of how the omitted case is handled: [ofPlan] resolves an omitted key to
     * [WEIGHT] before this is ever called, so an exercise whose plan said
     * nothing reads exactly as one that said `"weight"`. That is what the
     * omission MEANS -- every plan written against schema 1.10 or earlier
     * says weight by saying nothing -- so the pair a lifter can tell apart
     * before lifting is an omission and a declared [NONE], which is what #235
     * asks for, and not an omission and a declared `"weight"`.
     *
     * The words are the owner's with one stem un-elided; `ProgressionPhraseTest`
     * carries the reasoning and pins every case.
     */
    fun phrase(): String = when (this) {
        WEIGHT -> "steps up by weight"
        REPS -> "steps up by reps"
        TIME -> "steps up by time"
        NONE -> "holds load"
    }

    companion object {
        /**
         * The kind a plan's `progression` declaration names, or [WEIGHT] when
         * it declares nothing.
         *
         * An unrecognised string also reads as [WEIGHT], and that is reachable
         * only past a refusal: `PlanFile.validate` rejects any value outside
         * `PlanFile.VALID_PROGRESSIONS` with the path named, so a plan the app
         * accepted cannot get here carrying one. Reading it as the default
         * rather than throwing keeps a decoding accident out of the record
         * flow, where a crash between sets costs a set.
         */
        fun ofPlan(declared: String?): ProgressionKind =
            entries.firstOrNull { it.name.equals(declared, ignoreCase = true) } ?: WEIGHT
    }
}

/**
 * One tile of the post-set grid: the step it applies and the words it says.
 *
 * [amount] is in the unit [label] names -- pounds or kilograms in the lifter's
 * display unit for [ProgressionKind.WEIGHT], whole reps for
 * [ProgressionKind.REPS], whole seconds for [ProgressionKind.TIME]. It is
 * never in kilograms for a lb-mode weight tile: the conversion to storage
 * happens once, in [NextSetNudgePolicy.bumpedLoadKg], so no figure the lifter
 * reads is ever the result of one.
 */
data class NextSetNudge(
    val kind: ProgressionKind,
    val amount: Double,
    val label: String,
)

/**
 * What the rest screen offers after a set the lifter says had more in it, and
 * the arithmetic that applies it to the set coming up (#214).
 *
 * ## Why this is here and not in the composable
 *
 * The reason [EffortScale] and [WarmupMarkPolicy] are here: no test on the CI
 * path can reach a composable, so a rule written inside one is a rule nothing
 * can measure. `:app` keeps the drawing and this keeps the deciding.
 *
 * ## The trigger, and what each condition is protecting
 *
 * All of these must hold for the set just finished, or nothing is offered:
 *
 * - the rating is an anchored HEADROOM rung -- [HeadroomTier.ofRpe] is
 *   non-null. The lifter said more was possible, in the one part of the scale
 *   where that claim is made as a figure rather than as a feeling.
 * - the set did not fail. `failed` here is the app's OR of the lifter's own
 *   tap and the derived shortfall, and the derived half is why this condition
 *   exists at all: a set stopped at 6 of 8 reps and then rated "could have
 *   added 10-15 lb" is a real state, and offering to add load after a missed
 *   set is the wrong question. THIS IS A FOURTH CONDITION BEYOND THE THREE
 *   #214's body names, added deliberately rather than by omission.
 *   This keys on `failed` ALONE and reads no [SetLimiter]: a failed set the
 *   lifter answered `setup` -- the set-up was wrong, not that the muscle
 *   gave out (#146) -- suppresses the nudge exactly as a genuine capacity
 *   failure does. That is the safe direction, and it is left alone here on
 *   purpose: reading the limiter to tell those apart is a widening of what
 *   this function decides on, not a correction to it.
 * - it was not a warm-up. Pass [WarmupMarkPolicy.effective] of the plan's
 *   declaration and the lifter's mark, never the declaration alone: a ramp set
 *   the lifter marked is still a ramp set.
 * - sets are left on the exercise. There is nothing to raise otherwise, and
 *   the write this grid performs would land on another movement or nowhere.
 * - the exercise's [ProgressionKind] is not [ProgressionKind.NONE].
 *
 * ## The steps are authored per unit and never converted
 *
 * [EffortScale]'s rule, restated because this table is a second place a
 * converted figure could ship: *"You can probably say you can go up 5 kg/10
 * lbs, but not 4.72 kg."* [LB_STEPS] and [KG_STEPS] are two independent
 * authored rows, and no entry of either is a conversion of an entry of the
 * other.
 *
 * **[KG_STEPS] steps in 2.5, which is NOT a whole multiple of
 * [EffortScale.GYM_INCREMENT_MULTIPLE], and that divergence is deliberate.**
 * The effort scale asks how much more the lifter could have taken, in the
 * coarse notch a gym's equipment moves in; this grid is the finer control
 * offered afterwards, once the coarse claim has been made, and 2.5 kg is a
 * real pair of 1.25 kg plates. THE ROW IS AUTHORED HERE, NOT QUOTED. The
 * owner stated no kilogram ROW -- the only verbatim row in #214 is
 * *"basically 5-30 lbs"*, which is [LB_STEPS] -- and #214's
 * requirement 2 asks that every kilogram figure be a whole multiple of the
 * effort scale's authoring rule, which 2.5, 7.5 and 12.5 are not. That is a
 * departure from the written requirement, taken on the plate argument above
 * and RAISED for the owner rather than settled here. The row satisfying
 * requirement 2 as written would be 5 / 10 / 15 / 20 / 25 / 30 kg, whose
 * first rung is about 11 lb -- more than twice the pound row's first rung,
 * on the same tap. [KG_STEP_MULTIPLE] is what this table's own authoring
 * rule is pinned against.
 */
object NextSetNudgePolicy {
    /**
     * The pound row, authored: the owner's *"basically 5-30 lbs"*, in the 5 lb
     * steps a bar takes at 2.5 lb per side.
     */
    val LB_STEPS: List<Double> = listOf(5.0, 10.0, 15.0, 20.0, 25.0, 30.0)

    /** The kilogram row, authored beside the pound row and never from it. */
    val KG_STEPS: List<Double> = listOf(2.5, 5.0, 7.5, 10.0, 12.5, 15.0)

    /**
     * Every figure in [KG_STEPS] is a whole multiple of this.
     *
     * Half [EffortScale.GYM_INCREMENT_MULTIPLE], for the reason stated on this
     * object: a rung of the effort scale names the notch the equipment moves
     * in, and this names the smallest change worth offering once the lifter
     * has already said the notch was there.
     */
    const val KG_STEP_MULTIPLE = 2.5

    /**
     * The rep row. Two rungs and no more: the rating the lifter just gave is a
     * LOAD claim, not a rep claim, so anything past a couple of reps is a
     * number nobody supplied. The counted end of the effort scale starts at
     * three reps left for the same reason.
     */
    val REP_STEPS: List<Int> = listOf(1, 2)

    /** The timed row, in seconds; the owner's +5 / +10 / +15 s. */
    val TIME_STEPS_S: List<Int> = listOf(5, 10, 15)

    /**
     * How many sets of the finished exercise the queue still holds in front of
     * it.
     *
     * Counted off the QUEUE rather than from the finished slot's
     * `setsInExercise - setIndexInExercise - 1`, and the difference is not
     * cosmetic. A set appended at the rack (#177) is inserted into the queue
     * without renumbering the slots already behind it, so the arithmetic form
     * answers 0 on exactly the case where the lifter has just said they want
     * another set. Walking forward from the queue answers what is actually
     * there, and it stops at the first slot of another movement -- the same
     * boundary the load carry stops at ([SetLoadPolicy.sameExerciseBlock]).
     *
     * [upcomingExerciseIds] is the queue AFTER the finished slot, in order. A
     * null [finishedExerciseId] -- an ad-hoc set, which belongs to no block --
     * has no sets left by construction.
     */
    fun setsLeftInExercise(finishedExerciseId: String?, upcomingExerciseIds: List<String>): Int {
        if (finishedExerciseId == null) return 0
        return upcomingExerciseIds.takeWhile { it == finishedExerciseId }.count()
    }

    /**
     * The tiles to draw, or an empty list when the grid must not appear at all.
     *
     * ONE function rather than a boolean beside a table: two entry points are
     * two answers to one question, and the case this grid would get wrong
     * quietly is the one where it draws with nothing in it.
     *
     * [tier] is [HeadroomTier.ofRpe] of the rating stored for the set that just
     * finished. [warmup] is [WarmupMarkPolicy.effective] of the plan's
     * declaration and the lifter's own mark, never the declaration alone.
     * [setsLeftInExercise] is [setsLeftInExercise]'s answer. [progression] is
     * [ProgressionKind.ofPlan] of the exercise's declaration.
     */
    fun options(
        tier: HeadroomTier?,
        failed: Boolean,
        warmup: Boolean,
        setsLeftInExercise: Int,
        progression: ProgressionKind,
        unit: WeightUnit,
    ): List<NextSetNudge> {
        // Two statements rather than one four-term condition, each carrying the
        // pair that belongs together: first whether the lifter claimed more was
        // possible on a set they actually completed, then whether there is
        // anything of theirs to raise it on.
        if (tier == null || failed) return emptyList()
        if (warmup || setsLeftInExercise <= 0) return emptyList()
        return when (progression) {
            ProgressionKind.NONE -> emptyList()
            ProgressionKind.WEIGHT ->
                weightSteps(unit).map {
                    NextSetNudge(ProgressionKind.WEIGHT, it, "+${plainFigure(it)} ${unit.suffix}")
                }
            ProgressionKind.REPS ->
                REP_STEPS.map {
                    NextSetNudge(ProgressionKind.REPS, it.toDouble(), if (it == 1) "+1 rep" else "+$it reps")
                }
            ProgressionKind.TIME ->
                TIME_STEPS_S.map { NextSetNudge(ProgressionKind.TIME, it.toDouble(), "+$it s") }
        }
    }

    /**
     * Which of [options]' tiles the rung suggests first, or null when the grid
     * is not drawn at all (#244).
     *
     * Rung 6 the smallest step of the row, rung 4 its middle, rung 1 its
     * largest -- the owner's third comment on #244.
     *
     * IT PICKS FROM [offered] AND NEVER NARROWS IT. The owner's first two
     * comments on #244 asked for a narrowed offer per rung and his third
     * withdrew that outright -- *"Give the option to add more at each of the
     * headroom intervals."* -- so every rung offers the full row and the rung
     * decides only the default highlight. A function returning a MEMBER of the
     * list it was handed cannot narrow it, which is why this takes the offered
     * row rather than rebuilding one from the progression and the unit.
     */
    fun suggestedStep(tier: HeadroomTier?, offered: List<NextSetNudge>): NextSetNudge? {
        val index = SUGGESTED_INDEX[tier] ?: return null
        return offered.getOrNull(index(offered.size))
    }

    /**
     * Which position in the offered row each rung suggests.
     *
     * A POSITION IN A ROW OF A GIVEN LENGTH, not a fixed index: the rows are
     * six long for weight, three for time and two for reps, so "the middle
     * one" is arithmetic over the length rather than a number.
     *
     * THE MIDDLE OF AN EVEN ROW IS THE UPPER OF THE TWO CENTRE ENTRIES,
     * `size / 2`, and that is a choice with a reason. It makes rung 6 to rung
     * 4 a real move on every row -- +1 to +2 reps, +5 to +10 s, +5 to +20 lb
     * -- which is the direction the owner named when he said the add point is
     * rung 4 and not rung 6. On the pound row it also lands the suggestion on
     * 20 lb, exactly the figure rung 4's own caption says was left. `size / 2 -
     * 1` would have put rung 4 on the same +1 rep as rung 6 and made the rung
     * decide nothing at all on a rep exercise.
     *
     * TWO RUNGS SHARE A SUGGESTION ON THE REP ROW, because it is two long and
     * there are three rungs. Written down rather than left as an accident:
     * rung 4 and rung 1 both suggest +2, and what separates them on screen is
     * CUSTOM beside the tile, the owner's *"rung 1 the largest with custom
     * beside it"*, rather than a third step nobody offered.
     */
    private val SUGGESTED_INDEX: Map<HeadroomTier, (Int) -> Int> =
        mapOf(
            HeadroomTier.ONE_INCREMENT to { _ -> 0 },
            HeadroomTier.TWO_INCREMENTS to { size -> size / 2 },
            HeadroomTier.MUCH_MORE to { size -> size - 1 },
        )

    /**
     * The authored row for a unit, PICKED and never computed.
     *
     * The whole of "authored, never converted" is that this is a lookup with
     * two arms rather than one row plus [WeightUnit.fromKg].
     */
    private fun weightSteps(unit: WeightUnit): List<Double> = when (unit) {
        WeightUnit.KG -> KG_STEPS
        WeightUnit.LB -> LB_STEPS
    }

    /**
     * A step as the tile says it: "5", not "5.0", and "2.5" kept.
     *
     * Deliberately not [WeightUnit.format] or [WeightUnit.inputValue]: both
     * convert, and a tile that went through either would print the pound row
     * in kilograms the moment the display unit changed under it.
     */
    private fun plainFigure(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    /**
     * The next set's added load after a weight tile, in kilograms.
     *
     * The addition happens in the DISPLAY unit and is converted once, so a 45
     * lb set tapped "+10 lb" records the kilogram value of exactly 55 lb
     * rather than 45 lb converted, added to a converted 10, and rounded twice.
     *
     * A null [currentAddedKg] returns null and writes nothing. Absence is not a
     * zero to add to: a load the app could not parse is a load nobody stated,
     * and inventing one here would put a figure on the next set's card that the
     * lifter never gave.
     */
    fun bumpedLoadKg(currentAddedKg: Double?, nudge: NextSetNudge, unit: WeightUnit): Double? {
        if (currentAddedKg == null) return null
        return unit.toKg(unit.fromKg(currentAddedKg) + nudge.amount)
    }

    /**
     * The next set's rep count or hold length after a reps or time tile.
     *
     * Null in, null out, for [bumpedLoadKg]'s reason: there is no count to add
     * to, and a count nobody prescribed is not zero.
     */
    fun bumpedCount(current: Int?, nudge: NextSetNudge): Int? {
        if (current == null) return null
        return current + Math.round(nudge.amount).toInt()
    }
}
