package com.macrophage.barspeed.model

/**
 * The two facts [AddSetControl.placement] needs about one queued slot.
 *
 * Moved here from `:app`'s `QueueBlockKey` unchanged (#188). It was a `:app`
 * type because `PlanQueueTest` runs on a JDK 17 launcher while `:core:model`
 * emits class file version 65, so an `:app` test that LOADED a `:core:model`
 * type would die with `UnsupportedClassVersionError` before asserting
 * anything -- projecting two primitives kept the rule inside the one `:app`
 * file a test could reach. The rule now lives in a module whose tests run on
 * 21, so the projection stays but the trap does not apply to it.
 *
 * [setIndexInExercise] is carried because an exercise id alone cannot say
 * where a block ENDS. A session may run the same movement in two consecutive
 * blocks, and the second is a fresh prescription rather than a continuation of
 * the first; index 0 marks exactly the first set of a block. That is
 * [SetLoadPolicy.sameExerciseBlock]'s rule, read from here rather than
 * restated.
 */
data class AddSetSlotKey(val exerciseId: String, val setIndexInExercise: Int)

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
 * first. Removal is out of scope (#177 item 5) and nothing here shortens a
 * queue.
 */
object AddSetControl {
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
     * THE BLOCK ENDS WHERE THE NEXT ONE STARTS, and a block start is
     * `setIndexInExercise == 0` -- [SetLoadPolicy.sameExerciseBlock]'s rule,
     * read from here rather than restated. The exercise id alone cannot end a
     * block: a session may run one movement in two consecutive blocks, and
     * scanning on the id would queue a set added to the opening squat block
     * after the closing one, three exercises later. Both conditions are needed
     * and neither is sufficient.
     */
    fun placement(blocks: List<AddSetSlotKey>, queueIndex: Int, upcomingIndex: Int): AddSetPlacement? {
        if (upcomingIndex !in blocks.indices) return null
        val anchorIndex = upcomingIndex
        val anchor = blocks[anchorIndex]
        var i = anchorIndex + 1
        while (i < blocks.size && blocks[i].exerciseId == anchor.exerciseId && blocks[i].setIndexInExercise > 0) {
            i++
        }
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
     */
    fun label(anchorExercise: String, upcomingExercise: String?): String =
        "Load was wrong? Add another ${upcomingExercise ?: anchorExercise} set"
}
