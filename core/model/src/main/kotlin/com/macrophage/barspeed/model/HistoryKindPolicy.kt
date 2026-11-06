package com.macrophage.barspeed.model

/**
 * Which kind History reads a recorded set as (#57).
 *
 * The kind decides one thing on the session detail screen: whether the
 * velocity bars chart PEAK concentric velocity (an explosive lift) or MEAN
 * concentric velocity (everything else). The rest screen already reads the
 * kind the set was recorded under -- the plan's declaration where it made one
 * -- so History has to read the same answer, or one set shows peak on one
 * screen and mean on the other, and only History's reading survives the
 * session.
 *
 * Pure and here for [AbandonedSetPolicy]'s reason: `SessionDetailScreen` is
 * reached by no test, and a decision lifted here is pinned on every push.
 */
object HistoryKindPolicy {
    /**
     * [stored] is the kind frozen on the row -- `ResolvedGeometry.kind` inside
     * `geometryJson`, which is the effective kind the set was analysed under --
     * or null when the row carries no geometry. [seeded] is the built-in
     * definition's kind for the row's exercise id, or null for a custom one.
     *
     * THE STORED KIND WINS wherever the row has one: it is what the set was
     * recorded and analysed under, and what the rest screen charted. The seed
     * is the fallback for a row with no geometry -- every set recorded before
     * geometry capture -- and it is a display fallback only: it is the reading
     * History always made for such rows, and nothing writes it back or
     * publishes it, so the export still carries no geometry for them rather
     * than a guessed kind.
     */
    fun of(stored: ExerciseKind?, seeded: ExerciseKind?): ExerciseKind? = stored ?: seeded
}
