package com.macrophage.barspeed.model

/**
 * Who decides that a set was preparatory, when the plan and the lifter both
 * have something to say about it (#194).
 *
 * ## The hole this fills
 *
 * #187 removed the warm-up TILE from the effort grid, because a warm-up is
 * what a set is FOR and not how it felt, and the tile recorded `warmup = true`
 * and `rpe = null` together -- discarding the effort by construction. After it
 * the plan is the only producer of `warmup`, so a set added at the rack, which
 * is precisely the case #177 built the append control for, cannot be marked at
 * all and exports as a working set.
 *
 * ## Two facts, not one flag with two writers
 *
 * The declaration and the mark are stored apart -- `warmup` is what the plan
 * declared, frozen at the write; `warmupMark` is the lifter's own statement,
 * null until they make one -- and composed here. The alternative, letting a
 * tap overwrite the declaration, would take the plan's statement back out of
 * the row and re-muddy the exact field #187 spent a change clarifying. It is
 * the same arrangement `SetRatingTracker` already uses for the tapped and
 * derived halves of a failure, and for the same reason: a correction to one
 * must not erase the other.
 *
 * ## The mark never occupies the rating's slot
 *
 * Nothing here reads or writes `rpe`. `warmup: true` together with `rpe: 6` is
 * expressible, is what an honest hard warm-up records, and round-trips; that
 * is the whole principle #187 established and this change is written to it.
 */
object WarmupMarkPolicy {
    /**
     * Whether the set was preparatory, all things considered.
     *
     * THE LIFTER'S MARK WINS WHERE BOTH EXIST, and the reason is ordering in
     * time and authority, not convenience: the plan's declaration is a
     * prediction written before the session, the mark is a statement by the
     * person who did the set, made after it. The app already resolves a
     * disagreement that way once -- re-rating on the rest screen overwrites a
     * tapped failure -- and resolving this one the other way would make the
     * mark undoable on exactly the sets a plan already has an opinion about.
     *
     * The disagreement is not silent. Both facts stay in the row, so a plan
     * warm-up the lifter unmarked is distinguishable from a set no plan ever
     * called a warm-up, and [markedByLifter] is what the export publishes to
     * say which reading a document carries.
     */
    fun effective(declared: Boolean, mark: Boolean?): Boolean = mark ?: declared

    /** True once the lifter has stated anything at all about this set's purpose. */
    fun markedByLifter(mark: Boolean?): Boolean = mark != null

    /**
     * The mark a tap of the rest-screen toggle leaves behind.
     *
     * It never returns to null. Null means "the lifter has not said", and a
     * lifter who has tapped twice has said something twice -- returning them
     * to silence would make the second tap unrecordable and would hand the
     * plan back a set they had just taken off it.
     */
    fun toggled(declared: Boolean, mark: Boolean?): Boolean = !effective(declared, mark)

    /**
     * Whether the lifter's mark contradicts what the plan declared.
     *
     * Not used to decide anything; it is what the rest screen says out loud so
     * the lifter can see that the app knows the plan disagreed, rather than
     * finding the plan's word quietly gone.
     */
    fun disagrees(declared: Boolean, mark: Boolean?): Boolean = mark != null && mark != declared
}
