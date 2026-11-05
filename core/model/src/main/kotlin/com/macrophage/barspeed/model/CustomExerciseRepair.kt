package com.macrophage.barspeed.model

/**
 * The two inferred values a `CustomExerciseEntity` row stores for an id the
 * app ships no entry for, as `:core:data` writes them: the display name and
 * the start phase's [StartPhase.name]. A plain pair of strings rather than
 * the entity, because `:core:model` cannot see `:core:data`.
 */
data class StoredExerciseRow(val displayName: String, val startsWith: String)

/**
 * Whether a stored custom-exercise row still says what the app would infer
 * for its id today, and what to write over it when it does not (#59).
 *
 * The row is written ONCE, the first time a plan uses the id, and until this
 * existed nothing ever wrote it again: `ExerciseDao` had insert, observe,
 * all and byId and no statement that updates. So correcting a hint list --
 * `"pushdown"` joining [ExerciseDef.inferStartPhase]'s, which moved
 * `tricep_pushdown` from ECCENTRIC to CONCENTRIC -- corrected every install
 * that had never seen the id and none that had. On those, a plan omitting
 * `start` on a pushdown kept pairing every rep's phases backwards.
 *
 * **Every stored value is an inference, never a declaration.** The only
 * writer of the table is `SessionRepository.ensureExerciseExists`, and it
 * writes [ExerciseDef.inferredDisplayName] and
 * [ExerciseDef.inferStartPhase]; there is no screen on which a lifter names
 * an exercise or picks its start. So a stored value that differs from
 * today's inference is a stale inference, which is what "known-wrong" means
 * here: the app's own rule has since changed its answer. A plan that wants a
 * particular start declares `start`, which beats the stored row whatever it
 * says ([SetGeometryPolicy.resolve]), so rewriting the row loses no lifter's
 * statement.
 *
 * What a rewrite reaches, stated so it is not over-read: only sets recorded
 * AFTER it. A recorded set freezes its own resolved geometry onto its row at
 * set end, so no recorded set is re-labelled.
 */
object CustomExerciseRepair {
    /** What `ensureExerciseExists` writes for [id] today. */
    fun inferredRow(id: String): StoredExerciseRow =
        StoredExerciseRow(ExerciseDef.inferredDisplayName(id), ExerciseDef.inferStartPhase(id).name)

    /**
     * The row to write over [stored], or null when [stored] already says what
     * the app infers for [id] today and nothing should be written.
     */
    fun repaired(id: String, stored: StoredExerciseRow): StoredExerciseRow? = inferredRow(id).takeIf { it != stored }
}
