package com.macrophage.barspeed.model

/**
 * Whether a failed set may say WHOSE verdict the failure is (#216, #169).
 *
 * The app has always held two facts and OR-ed them -- the lifter's own tap and
 * the derived shortfall -- and published only the OR. So a set the lifter
 * called a grinder and a set the app marked short of its prescription arrive
 * at a reader identical, and `limiter` cannot separate them: it is an optional
 * answer to a different question, absent on every set nobody was asked and on
 * every set the lifter skipped.
 *
 * Pure and here for [AbandonedSetPolicy]'s reason.
 */
object FailureProvenancePolicy {
    /**
     * The value to publish under `failedByLifter`, or null to publish nothing.
     *
     * Two distinct absences, both correct and neither a "no":
     *
     * - The set did not fail. There is no failure whose author could be named,
     *   and a `false` here would read as a derived failure that never
     *   happened.
     * - [failedByLifter] null. The row predates the column, so nobody knows.
     *   Every set recorded before database v15 is permanently in that state
     *   and nothing may backfill it -- the tap was held in memory for the life
     *   of the rest screen and discarded, so there is no source to backfill
     *   from.
     *
     * A published `false` is therefore a real statement: this set failed, the
     * app derived it, and the lifter did not say so.
     */
    fun published(failed: Boolean, failedByLifter: Boolean?): Boolean? = if (failed) failedByLifter else null

    /** The word for a failure the LIFTER called, on History's chip. */
    const val TAPPED_WORD = "Failed"

    /**
     * The word for a failure only the app derived -- the set fell short of its
     * prescription and the lifter never said it failed (#169, owner default
     * for 0.2.0).
     */
    const val DERIVED_WORD = "Short of target"

    /**
     * The word History's failure chip draws for one stored row, or null when
     * it draws none (#169 item 1's display half).
     *
     * One decision, in `:core:model` where a test reaches it, rather than a
     * branch in `SessionDetailScreen`, which nothing under `app/src/test`
     * reaches.
     *
     * THREE ANSWERS FOR A FAILED SET, keyed off the stored author:
     *
     * - [failedByLifter] true: the lifter called it -- [TAPPED_WORD].
     * - false: the app derived it from a shortfall and the lifter never said
     *   so -- [DERIVED_WORD]. Until this split the chip drew the lifter's
     *   word on a set the lifter never called failed.
     * - null: the row predates v15 and nobody knows whose verdict it was.
     *   [TAPPED_WORD], the one word History has always drawn for both, because
     *   splitting it here would guess an author for a set nobody recorded one
     *   for; [published] withholds the author in the export for the same row.
     */
    fun historyWord(failed: Boolean, failedByLifter: Boolean?): String? = when {
        !failed -> null
        failedByLifter == false -> DERIVED_WORD
        else -> TAPPED_WORD
    }
}
