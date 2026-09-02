package com.macrophage.barspeed.model

/**
 * Which side the NEXT set works: the plan's prescription unless the lifter has
 * said otherwise for that one set (#215, #144).
 *
 * ## Why the decision is here
 *
 * [SetLoadPolicy]'s reason, one field over: no test on the CI path can reach a
 * composable or a ViewModel, so a rule written inside one is a rule nothing can
 * measure. `:app` draws the chips and this decides what they mean.
 *
 * ## The three facts and what each is protecting
 *
 * - the PRESCRIPTION, `PlannedSlot.side`, which a plan declares per set --
 *   unilateral work is written one set per side, so the plan's own order IS the
 *   alternation. A slot that declares no side is bilateral and no statement
 *   here can make it otherwise: the control is not drawn for one, and a stray
 *   value would put a limb on a set that used both.
 * - the LIFTER'S CHOICE for the set coming up, `RecordState.statedSide`, which
 *   is null until they touch the control and is cleared again once the set it
 *   was made for has been written. That expiry is what keeps the plan's
 *   alternation intact: a statement that stood would flip every remaining set
 *   of the block onto one side, which is the opposite of what a lifter swapping
 *   arm order for ONE set asked for.
 * - the ALTERNATION RULE, which is the plan's own declaration and is therefore
 *   not a fourth input. It stands wherever nothing was stated, and that is the
 *   whole of it.
 *
 * ## What this does NOT decide
 *
 * Whether the side the set worked is RECORDED. It is, in `set_records.side`,
 * beside `plannedSide` which freezes what the plan asked for -- the pair #144
 * was opened for, because until this change `side` was a copy of the
 * prescription and no reading of it could say which limb moved.
 */
object SideChoicePolicy {
    /**
     * The sides a lifter may pick, in the order the chips are drawn.
     *
     * A LIST because the order is drawn and a set is not ordered, pinned
     * against [PlanFile.VALID_SIDES] rather than spelled twice: a vocabulary
     * the control offers that the plan contract would reject is a value the
     * import gate refuses on the way back in.
     */
    val CHOICES: List<String> = listOf("left", "right")

    /**
     * Whether the set coming up is one a side can be stated for at all.
     *
     * True for a unilateral slot and false for everything else, which is the
     * silent-no-draw rule `SideArrow` already applies to the arrow itself: a
     * value that never passed [PlanFile.VALID_SIDES] is not a side, and
     * offering to change it would invite the lifter to state a limb on a set
     * that used both.
     *
     * A SEAM AT THIS COMMIT, refusing every slot; #215's fix commit gives it a
     * body and the pins for it are red until then.
     */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun offersChoice(declaredSide: String?): Boolean = false

    /**
     * The side the next set works.
     *
     * [declaredSide] is the slot's frozen prescription and [statedSide] is what
     * the lifter picked for THIS set, null when they picked nothing.
     *
     * A SEAM AT THIS COMMIT, returning the prescription unconditionally, which
     * is what the app does today at every call site; #215's fix commit gives it
     * a body and the pins for it are red until then.
     */
    @Suppress("UNUSED_PARAMETER")
    fun carriedIntoNextSet(declaredSide: String?, statedSide: String?): String? = declaredSide
}
