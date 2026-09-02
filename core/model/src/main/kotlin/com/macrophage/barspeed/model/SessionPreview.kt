package com.macrophage.barspeed.model

/**
 * One planned set as the session preview and the record flow's set card read
 * it, projected out of the queue slot the record flow is going to run
 * (#202).
 *
 * A projection, not a second model of the plan. Every field here is copied
 * one-for-one off the flattened queue slot with no arithmetic and no
 * re-reading of the plan, which is the whole point: the preview the lifter
 * reads before pressing Start and the card they read on the first set are the
 * same numbers passed through the same rendering, so the two cannot disagree.
 * A preview that disagrees with the first set is worse than no preview.
 *
 * IT CARRIES THE STANDING VALUES, NOT THE FROZEN ONES. `PlannedSlot` holds
 * both -- `loadKg` beside `plannedLoadKg`, `reps` beside `plannedReps`,
 * `tempo` beside `plannedTempo` -- and this takes the first of each pair,
 * because the question the preview answers is "what am I about to lift", not
 * "what did the plan ask for". Before a session's first set the two are equal
 * by construction; they stop being equal the moment a correction is standing,
 * and it is the standing value the record flow will run.
 *
 * [setIndexInExercise] and [setsInExercise] are carried rather than derived
 * from the list position, and that is load-bearing -- see
 * [SessionPreviewPolicy.of] for what they decide.
 *
 * [implementCount] and [restS] are carried and not yet drawn:
 * [SessionPreviewPolicy.setLine] reads neither, unlike the running set's
 * `SlotCard`, whose secondary line adds a "Pick up: …" implement split and a
 * "rest mm:ss" clause the preview's set line does not repeat.
 */
data class PreviewSet(
    val exerciseName: String,
    val kind: ExerciseKind,
    val bodyweight: Boolean,
    val setIndexInExercise: Int,
    val setsInExercise: Int,
    val reps: Int?,
    val durationS: Int?,
    /** ADDED load, as the slot carries it: on body-weight work the lifter's own mass is not in it. */
    val loadKg: Double?,
    val tempo: String?,
    val side: String?,
    val implementCount: Int?,
    val restS: Int?,
    /** What the PLAN declared this set was for. No lifter mark exists yet; the session has not run. */
    val warmup: Boolean,
)

/**
 * One exercise block of the upcoming session, in the order the queue runs it.
 *
 * A block, not an exercise: one session may hold two blocks of the same
 * movement -- a ramp and then working sets is the ordinary case -- and they
 * carry independent set counts. See [SessionPreviewPolicy.of].
 */
data class PreviewBlock(val exerciseName: String, val sets: List<PreviewSet>)

/**
 * The whole of one upcoming session, as the record flow will run it.
 *
 * [totalSets] counts every set including the warm-ups, and [warmupSets] says
 * how many of them are warm-ups; the two are stated separately rather than as
 * a working-set count, because "8 sets, 3 of them warm-ups" and "5 sets" are
 * different sessions and the lifter deciding whether to train wants the first.
 */
data class SessionPreview(val blocks: List<PreviewBlock>, val totalSets: Int, val warmupSets: Int) {
    /** Blocks, not distinct movements: two blocks of the same lift count twice. */
    val blockCount: Int get() = blocks.size

    /** Nothing to preview. An ad-hoc session is always this, and never gets a preview screen. */
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/**
 * What the preview of one upcoming session lists, given the queue that session
 * will run (#202).
 *
 * Pure, and in `:core:model` rather than beside the screen that draws it, for
 * [RecordExitPolicy]'s reason: the caller is a `@Composable` inside an
 * `AndroidViewModel`'s state, which no test on the CI path can construct, so a
 * decision written beside it could not be tested at all.
 */
object SessionPreviewPolicy {
    /**
     * Group the queue into the exercise blocks the preview draws.
     *
     * THE BOUNDARY IS [PreviewSet.setIndexInExercise] RETURNING TO ZERO, NOT A
     * CHANGE OF NAME. A session may run two blocks of the same movement back to
     * back -- a ramp and then the working sets is the ordinary shape of it --
     * and `flattenPlan` gives each its own `setsInExercise`, counting them
     * independently. Grouping by name merged those two into one block of five
     * while the screen the lifter lands on said "Set 1/3", which is exactly the
     * preview-disagrees-with-the-first-set failure this whole surface exists to
     * avoid. Reading the same field the record flow counts with is what keeps
     * the two in step.
     *
     * The first slot of the queue always opens a block, whatever its index.
     * Every queue the app calls this with comes straight from flattenPlan,
     * which numbers each exercise's sets from zero, so a block's first slot
     * carries index 0 by construction.
     *
     * Order is the queue's order and is never sorted: the queue is the running
     * order, and a preview that re-ordered it would be describing a session
     * nobody is going to perform.
     *
     * [PreviewSet.exerciseName] is read only where a block OPENS. A slot whose
     * name differs from the open block's but whose index is not zero is
     * appended to that block anyway, and the block keeps the first slot's
     * name -- no queue `flattenPlan` builds can produce that shape, so the
     * case is unreachable rather than handled, and nothing here pins it.
     */
    fun of(sets: List<PreviewSet>): SessionPreview {
        val blocks = mutableListOf<PreviewBlock>()
        for (set in sets) {
            val open = blocks.lastOrNull()
            if (open == null || set.setIndexInExercise == 0) {
                blocks += PreviewBlock(set.exerciseName, listOf(set))
            } else {
                blocks[blocks.lastIndex] = open.copy(sets = open.sets + set)
            }
        }
        return SessionPreview(
            blocks = blocks,
            totalSets = sets.size,
            warmupSets = sets.count { it.warmup },
        )
    }

    /**
     * The one line that states what a set is: side, count or hold, load and
     * tempo, in that order, separated by " · ".
     *
     * ONE SOURCE FOR THE BASE TEXT OF A SET ON THESE TWO SURFACES.
     * PlanDetailScreen's SetGroupRow still builds its own line from the same
     * vocabulary and is not unified here.
     * `SlotCard` in `:app` built this string inline until #202. It now draws
     * [SetCardValues.of]'s pairs, so that the plan's figure can be STRUCK
     * THROUGH and the figure the set will actually record put beside it
     * (#204) -- and a plain string cannot be struck halfway, which is why the
     * card needs pairs and this does not. So rather than keep two spellings
     * of one vocabulary, this is [SetCardValues.of] rendered by
     * [SetCardValues.plain]: the same words, the same order, the same
     * separator, from the same code. An unrun set reads identically in the
     * preview and on the card because it IS the same string.
     *
     * NOTHING IS EVER STRUCK HERE. Every `planned` argument below is passed
     * the standing value beside it, so [SetCardValues.of] finds no deviation
     * to mark; the preview draws sets before the session has started and no
     * lifter has changed anything yet.
     *
     * The rules the line follows are unchanged and are [SetCardValues.of]'s
     * now: the ADDED load on body-weight work said as an addition to the
     * lifter and never as a weight on its own (#160), and "bodyweight" said
     * only for a TIMED set that carries no load -- a plank has nothing else to
     * say, while a rep set the plan gave no load for has a load the lifter is
     * about to state, and naming it "bodyweight" would be an invention.
     */
    fun setLine(set: PreviewSet, unit: WeightUnit): String = SetCardValues.plain(
        SetCardValues.of(
            kind = set.kind,
            bodyweight = set.bodyweight,
            // The same rule PlannedSlot.isTimed carries in :app: a set is
            // measured on the clock when it declares a duration.
            timed = set.durationS != null,
            unit = unit,
            side = set.side,
            plannedLoadKg = set.loadKg,
            statedLoadKg = null,
            declaredLoadKg = set.loadKg,
            plannedReps = set.reps,
            reps = set.reps,
            plannedDurationS = set.durationS,
            durationS = set.durationS,
            plannedTempo = set.tempo,
            tempo = set.tempo,
        ),
    )
}
