package com.macrophage.barspeed.model

/**
 * One planned set as any surface that LISTS a set reads it, projected out of
 * the queue slot the record flow is going to run (#202).
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
     * Order is the queue's order and is never sorted: the queue is the running
     * order, and a preview that re-ordered it would be describing a session
     * nobody is going to perform.
     */
    fun of(sets: List<PreviewSet>): SessionPreview {
        val blocks = mutableListOf<PreviewBlock>()
        for (set in sets) {
            val open = blocks.lastOrNull()
            if (open != null && open.exerciseName == set.exerciseName) {
                blocks[blocks.lastIndex] = open.copy(sets = open.sets + set)
            } else {
                blocks += PreviewBlock(set.exerciseName, listOf(set))
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
     * THE SAME FUNCTION THE RECORD FLOW'S OWN CARD USES. `SlotCard` built this
     * string inline until #202 and now calls this, so the preview and the
     * "Up next" card on the first set cannot phrase the same set two ways.
     * Extracting it is what makes the vocabulary one vocabulary rather than a
     * second rendering of the plan, and it moves the phrasing into a module
     * where a test runs on it every push.
     *
     * "bodyweight" is said only for a TIMED set that carries no load, which is
     * the rule the record card already shipped: a plank has nothing else to
     * say, while a rep set the plan gave no load for has a load the lifter is
     * about to state and naming it "bodyweight" would be an invention.
     */
    fun setLine(set: PreviewSet, unit: WeightUnit): String = listOfNotNull(
        set.side?.replaceFirstChar { it.uppercase() },
        set.reps?.let { "$it reps" },
        set.durationS?.let { "${it}s " + if (set.kind == ExerciseKind.CARRY) "carry" else "hold" },
        if (set.bodyweight) {
            BodyweightLoadDisplay.label(set.loadKg, unit)
        } else {
            set.loadKg?.takeIf { it > 0 }?.let { unit.format(it) }
                ?: "bodyweight".takeIf { set.durationS != null }
        },
        set.tempo?.let { "tempo $it" },
    ).joinToString(" · ")
}
