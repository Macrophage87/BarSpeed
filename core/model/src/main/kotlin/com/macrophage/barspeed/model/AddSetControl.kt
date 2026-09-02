package com.macrophage.barspeed.model

/**
 * The two facts [AddSetControl.placement] needs about one queued slot.
 *
 * Moved here from `:app`'s `QueueBlockKey` unchanged (#188). It was a `:app`
 * type because `:app` RAN its unit tests on a JDK 17 launcher while
 * `:core:model` emits class file version 65, so an `:app` test that LOADED a
 * `:core:model` type died with `UnsupportedClassVersionError` before
 * asserting anything -- projecting two primitives kept the rule inside the
 * one `:app` file a test could reach. `app/build.gradle.kts` now pins that
 * launcher to 21 and the trap is closed, but the projection stays on its own
 * merits: `placement` needs two facts about a slot and has no business
 * knowing what an `ExerciseDef` is.
 *
 * [setIndexInExercise] is carried because an exercise id alone cannot say
 * where a block ENDS. A session may run the same movement in two consecutive
 * blocks, and the second is a fresh prescription rather than a continuation of
 * the first; index 0 marks exactly the first set of a block. That is
 * [SetLoadPolicy.sameExerciseBlock]'s rule, read from here rather than
 * restated.
 *
 * [isAddedSet] is carried for #206, which needs to find the appended sets
 * inside a block in order to take one back out. [AddSetControl] itself never
 * reads it -- where a set came from says nothing about where the next one
 * goes -- and it is deliberately WITHOUT a default so that a projection of
 * the queue has to answer the question rather than inherit `false` and make
 * every appended set silently unremovable.
 */
data class AddSetSlotKey(val exerciseId: String, val setIndexInExercise: Int, val isAddedSet: Boolean)

/**
 * Everything the app needs to know to append one more set of an exercise.
 *
 * [anchorIndex] is the slot the control REFERS to -- the exercise the appended
 * set is a set of. [insertAt] is where the new slot goes; it is an insertion
 * index, so `blocks.size` means "at the end" and is an answer rather than an
 * error. [becomesNextSet] says the appended set displaces the slot the next
 * START would have run, which is what tells the caller to re-seed the editable
 * boxes. [carriesStandingStatements] says whether the load, reps and tempo the
 * lifter is standing on are statements about the anchor's own exercise, and so
 * whether they may be written into the appended set.
 */
data class AddSetPlacement(
    val anchorIndex: Int,
    val insertAt: Int,
    val becomesNextSet: Boolean,
    val carriesStandingStatements: Boolean,
)

/**
 * What "Add another set" refers to, and where the set it adds goes.
 *
 * Here rather than beside the button for [SetLoadPolicy]'s reason: the caller
 * is a `@Composable` and a file-private function inside `RecordViewModel.kt`,
 * neither of which any test on the CI path can name. Nothing in this file
 * touches Android, Room or a sensor.
 *
 * THE PLACEMENT RULE IS #177's, MOVED, NOT CHANGED. The lifter is adding to a
 * block, not jumping the queue: when a set shows the load was wrong, the
 * plan's remaining set count is the wrong count and the sets that remain of
 * that exercise are still wanted, so the appended set goes AFTER the block's
 * remaining sets. Only where the block has none remaining is that the same
 * thing as "immediately next".
 *
 * Repeatable by construction: an appended slot carries the next index in the
 * block, so the scan runs through it and a second addition lands after the
 * first. Nothing in THIS object shortens a queue; [RemoveSetControl] takes an
 * appended set back out again, reading this object's [blockRange] so the two
 * cannot disagree about which sets belong to the block (#206).
 */
object AddSetControl {
    /**
     * The queue indices of the block the slot at [anchorIndex] belongs to,
     * inclusive at both ends, or null when [anchorIndex] is not a slot.
     *
     * Extracted from [placement] by #206 rather than restated there: removing
     * an appended set has to search the same block the add extends, and "what
     * is a block" written in two places is two rules that can disagree about
     * a session running one movement in two consecutive blocks.
     *
     * THE FORWARD HALF IS [placement]'s SCAN, MOVED UNCHANGED, and it is the
     * only half [placement] reads -- it takes `last + 1` and nothing else, so
     * the backward scan added here cannot move its answer.
     *
     * THE BACKWARD HALF IS NEW and is the mirror of the same rule: a block
     * starts at `setIndexInExercise == 0`, so the walk back stops on that
     * slot, and it stops one earlier still if the slot before it is a
     * different exercise. Both conditions are needed for the reason
     * [placement] gives: the exercise id alone cannot end a block, and the
     * index alone cannot say which exercise the block is of.
     */
    fun blockRange(blocks: List<AddSetSlotKey>, anchorIndex: Int): IntRange? {
        if (anchorIndex !in blocks.indices) return null
        val anchor = blocks[anchorIndex]
        var first = anchorIndex
        while (first > 0 &&
            blocks[first].setIndexInExercise > 0 &&
            blocks[first - 1].exerciseId == anchor.exerciseId
        ) {
            first--
        }
        var afterLast = anchorIndex + 1
        while (afterLast < blocks.size &&
            blocks[afterLast].exerciseId == anchor.exerciseId &&
            blocks[afterLast].setIndexInExercise > 0
        ) {
            afterLast++
        }
        return first..afterLast - 1
    }

    /**
     * Where a set the lifter appends goes, and what it is a set OF.
     *
     * [blocks] is the whole queue projected through [AddSetSlotKey], including
     * sets already done -- indices are into the queue itself, so nothing here
     * may quietly renumber. [queueIndex] is the slot being recorded or set up
     * and [upcomingIndex] is the slot the next START will run: they are the
     * same on READY and differ by one during rest, where [upcomingIndex] is
     * legitimately one past the last slot.
     *
     * THE ANCHOR IS [queueIndex] AND NOT [upcomingIndex] (#188). "The load was
     * wrong" is a statement about a set that has HAPPENED, so the exercise
     * being added to is the one just finished -- which during rest is
     * [queueIndex], and on READY is the same slot as the coming one. Anchoring
     * on the upcoming slot made the control name, and build its new slot from,
     * the exercise the lifter has not done yet: at a block boundary a set
     * added because the squat load was wrong became a row set queued after the
     * rows. It also refused outright after the session's final set, where the
     * upcoming index is one past the end and the anchor is not.
     *
     * [upcomingIndex] still decides the two facts about the CALLER's screen:
     * whether the appended set displaces the coming one, and whether what the
     * lifter is standing on belongs to the anchor's exercise.
     *
     * THE BLOCK ENDS WHERE THE NEXT ONE STARTS, and a block start is
     * `setIndexInExercise == 0` -- [SetLoadPolicy.sameExerciseBlock]'s rule,
     * read from here rather than restated. The exercise id alone cannot end a
     * block: a session may run one movement in two consecutive blocks, and
     * scanning on the id would queue a set added to the opening squat block
     * after the closing one, three exercises later. Both conditions are needed
     * and neither is sufficient.
     */
    fun placement(blocks: List<AddSetSlotKey>, queueIndex: Int, upcomingIndex: Int): AddSetPlacement? {
        val block = blockRange(blocks, queueIndex) ?: return null
        val anchorIndex = queueIndex
        val anchor = blocks[anchorIndex]
        val i = block.last + 1
        val upcoming = blocks.getOrNull(upcomingIndex)
        return AddSetPlacement(
            anchorIndex = anchorIndex,
            insertAt = i,
            becomesNextSet = i == upcomingIndex,
            carriesStandingStatements = upcomingIndex == queueIndex ||
                SetLoadPolicy.sameExerciseBlock(
                    lastExerciseId = anchor.exerciseId,
                    nextExerciseId = upcoming?.exerciseId,
                    nextSetIndexInExercise = upcoming?.setIndexInExercise,
                ),
        )
    }

    /**
     * What the control says.
     *
     * [anchorExercise] is the display name of the exercise the appended set is
     * a set of; [upcomingExercise] is the display name of the set the next
     * START would run, null when the session has no set left.
     *
     * It names the ANCHOR, which is the exercise just finished (#188): the
     * button said "another Lat pulldown set" at the moment the lifter wanted
     * another press set, which is both the wrong name and, before this, the
     * wrong set.
     *
     * WHERE IT LANDS is said only when the two names differ, which is the one
     * moment there is doubt to remove -- and it is true rather than
     * decorative: the anchor's block has no sets left there, so the appended
     * set runs next, ahead of the exercise named. Where the names agree there
     * is nothing to disambiguate, and the appended set goes after the block's
     * remaining sets rather than next, so a "before" clause would be false.
     * Two consecutive blocks of one exercise take the shorter form for the
     * same reason: naming the same words on both sides of "before" tells the
     * lifter nothing.
     */
    fun label(anchorExercise: String, upcomingExercise: String?): String =
        if (upcomingExercise == null || upcomingExercise == anchorExercise) {
            "Load was wrong? Add another $anchorExercise set"
        } else {
            "Load was wrong? Add another $anchorExercise set before $upcomingExercise"
        }
}
