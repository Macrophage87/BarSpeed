package com.macrophage.barspeed.model

/**
 * What the history screen's set card grades a stored set against, and how it
 * says so (#308, #157).
 *
 * Four sites on `SessionDetailScreen` read a set's target: the header's
 * "(target …)", the "Held a/t" chip and its tone, the slots `RepBars` draws,
 * and the amber load "Deviation" line. The verdict sentence under the chips is
 * a fifth: it is either the sentence frozen into the row's analysis at set
 * end, or re-graded here off the stored seconds. Each of those decisions lived
 * inline in a Compose function where no test on the CI path reaches it; lifted
 * here, every branch is a literal in a test that runs on every push.
 *
 * ## What this lift preserves
 *
 * Every figure is read off the PLAN's columns (`plannedDurationS`,
 * `plannedReps`, `plannedLoadKg`), and the verdict sentence is always the
 * frozen one, filtered by regime in `:app`. That is what the screen drew
 * before this type existed. The working columns and `durationEndedBy` are
 * parameters of [Row] but nothing reads them yet.
 *
 * ## What this cannot check
 *
 * Which entity columns `SessionDetailScreen` copies into [Row], and that it
 * draws every field of the answer. The screen is compile- and lint-gated
 * only, not test-gated.
 */
data class HistoryTarget(
    /** Whose figure the set is graded against, or [Source.NONE] where it has none. */
    val source: Source,
    /** The header's work text on a timed set, e.g. "30s (target 45s)"; null on a rep set. */
    val holdHeader: String?,
    /** The "Held" chip on a timed set; null on a rep set. */
    val heldChip: HeldChip?,
    /** The rep slots `RepBars` draws, or null for none. */
    val repSlots: Int?,
    /** A neutral line under the header stating a target's provenance, or null. */
    val note: String?,
    /** The amber load line, or null where the load did not deviate. */
    val loadDeviation: String?,
    /** Which verdict sentence the card shows. */
    val verdict: Verdict,
) {
    /** Whose figure a target is. */
    enum class Source {
        /** The target the set ran against, stored from database v20. */
        WORKING,

        /** The plan's frozen figure. */
        PLAN,

        /** The set has no target to grade against. */
        NONE,
    }

    /** The "Held" chip's tone. `:app` maps it onto its own chip colours. */
    enum class Tone { OK, WARN, BAD }

    /** The "Held" chip: its text and tone. */
    data class HeldChip(val text: String, val tone: Tone)

    /** Which verdict sentence the card shows. */
    sealed interface Verdict {
        /** Re-grade the hold off [actualS] against [targetS], as the rest screen does. */
        data class Regrade(val actualS: Int, val targetS: Int?) : Verdict

        /**
         * Show the sentence frozen at set end, preceded by [caption] where
         * there is one to say about it.
         */
        data class Frozen(val caption: String? = null) : Verdict
    }

    /**
     * The stored set's columns this decision reads, copied from
     * `SetRecordEntity` field for field.
     *
     * [workingLoadKg] is the marker of a row written from database v20: every
     * set has a load, so every row v20 inserts carries one, and a null says
     * the build that wrote the row could not say what the set ran against.
     */
    data class Row(
        val actualReps: Int,
        val plannedReps: Int?,
        val workingReps: Int?,
        val actualDurationS: Int?,
        val plannedDurationS: Int?,
        val workingDurationS: Int?,
        val loadKg: Double,
        val plannedLoadKg: Double?,
        val workingLoadKg: Double?,
        val durationEndedBy: String?,
    )

    companion object {
        /** The card's targets for [row], loads printed in [unit]. */
        fun of(row: Row, unit: WeightUnit): HistoryTarget {
            val targetS = row.plannedDurationS
            val timed = row.actualDurationS != null
            val figure = if (timed) targetS else row.plannedReps
            return HistoryTarget(
                source = if (figure != null) Source.PLAN else Source.NONE,
                holdHeader = row.actualDurationS?.let { a -> "${a}s" + (targetS?.let { " (target ${it}s)" } ?: "") },
                heldChip = row.actualDurationS?.let { heldChip(it, targetS) },
                repSlots = row.plannedReps,
                note = null,
                loadDeviation =
                row.plannedLoadKg?.takeIf { it != row.loadKg }?.let { "Deviation (planned ${unit.format(it)})" },
                verdict = Verdict.Frozen(),
            )
        }

        /**
         * The chip for [actualS] held against [targetS]: met at or past the
         * target, [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] of it or more is
         * close, anything less is well short. No target is not graded.
         */
        private fun heldChip(actualS: Int, targetS: Int?) = HeldChip(
            text = if (targetS != null) "Held $actualS/${targetS}s" else "Held ${actualS}s",
            tone = when {
                targetS == null || actualS >= targetS -> Tone.OK
                actualS >= (targetS * TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION).toInt() -> Tone.WARN
                else -> Tone.BAD
            },
        )
    }
}
