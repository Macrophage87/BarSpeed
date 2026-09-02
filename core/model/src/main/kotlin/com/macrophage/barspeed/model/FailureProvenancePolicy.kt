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
}
