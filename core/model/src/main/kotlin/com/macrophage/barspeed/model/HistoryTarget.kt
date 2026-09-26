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
 * ## A row written from database v20: the WORKING figure
 *
 * The row stores the target the set ran against (`workingReps`,
 * `workingDurationS`, `workingLoadKg`) beside the plan's frozen figure, and
 * every site reads the working one. It is the figure `SetShortfallPolicy`
 * judged `failed` by at set end and the one the rest screen's verdict grades
 * against, so the history card now agrees with both.
 *
 * - A hold is re-graded off its stored seconds against the working target,
 *   through the rest screen's own `restVerdicts` (`historyVerdicts` in
 *   `:app`). The stored seconds are the corrected figure where the lifter
 *   corrected the hold, so a correction no longer leaves the frozen sentence
 *   contradicting the header. On an uncorrected hold the frozen sentence
 *   already graded the recorded seconds against the working target, so the
 *   words there are the same.
 * - A LOWERED target met is completed. The owner's ruling, 2026-09-25: "It's
 *   completed even if the target is lowered, just note the discrepancy." The
 *   card notes it as a fact ("6 of 6 · lowered from 10", "target 30s ·
 *   lowered from 45s") and grades nothing against the plan's figure.
 * - A RAISED target is the in-app buttons' intended use: met reads met, and
 *   missed is graded against the raised figure ("if you can't do it, it's
 *   failed"). The raise is noted the same way.
 * - A working load that differs from the plan's is a note too, not a
 *   deviation. The amber line is kept for the load the lifter restated after
 *   the set (`overrideLoad` rewrites `loadKg`, never `workingLoadKg`), which
 *   is the actual figure departing from the one the set ran against.
 *
 * ## A row written before v20: the PLAN's figure, labelled as the plan's
 *
 * No build before v20 stored the working target, so the row cannot say what
 * the set ran against. The plan's figure stays, and the card says it is the
 * plan's. The frozen sentence stays too, because nothing on the row is a
 * target it could be re-graded against. Where `durationEndedBy` says the
 * lifter corrected the hold, that sentence was graded before the correction,
 * and the card says so above it.
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
    /** The header's work text on a timed set, e.g. "30s (target 30s · lowered from 45s)"; null otherwise. */
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
        /**
         * The caption above a frozen hold sentence on a corrected row written
         * before v20.
         */
        const val GRADED_BEFORE_CORRECTION = "Graded at set end, before the hold was corrected:"

        /** The card's targets for [row], loads printed in [unit]. */
        fun of(row: Row, unit: WeightUnit): HistoryTarget {
            val workingLoadKg = row.workingLoadKg
            return if (workingLoadKg == null) planRow(row, unit) else workingRow(row, workingLoadKg, unit)
        }

        /** A row written from v20: every site reads the working figure. */
        private fun workingRow(row: Row, workingLoadKg: Double, unit: WeightUnit): HistoryTarget {
            val actualS = row.actualDurationS
            val targetS = row.workingDurationS
            val targetReps = row.workingReps
            val repNote =
                if (actualS == null && targetReps != null) {
                    "${row.actualReps} of $targetReps" + noted(shift(row.plannedReps, targetReps, ""))
                } else {
                    null
                }
            val loadNote = loadShift(row.plannedLoadKg, workingLoadKg, unit)?.let { "load $it" }
            val holdTarget =
                targetS?.let { t -> " (target ${t}s" + noted(shift(row.plannedDurationS, t, "s")) + ")" } ?: ""
            return HistoryTarget(
                source = sourceOf(if (actualS != null) targetS else targetReps, Source.WORKING),
                holdHeader = actualS?.let { "${it}s$holdTarget" },
                heldChip = actualS?.let { heldChip(it, targetS) },
                repSlots = targetReps,
                note = listOfNotNull(repNote, loadNote).joinToString(" · ").ifEmpty { null },
                loadDeviation =
                workingLoadKg.takeIf { it != row.loadKg }?.let { "Deviation (working ${unit.format(it)})" },
                verdict = if (actualS != null) Verdict.Regrade(actualS, targetS) else Verdict.Frozen(),
            )
        }

        /** A row written before v20: the plan's figure, labelled as the plan's. */
        private fun planRow(row: Row, unit: WeightUnit): HistoryTarget {
            val actualS = row.actualDurationS
            val targetS = row.plannedDurationS
            val corrected = HoldEndSource.ofPublished(row.durationEndedBy) == HoldEndSource.CORRECTED
            return HistoryTarget(
                source = sourceOf(if (actualS != null) targetS else row.plannedReps, Source.PLAN),
                holdHeader = actualS?.let { a -> "${a}s" + (targetS?.let { " (plan's target ${it}s)" } ?: "") },
                heldChip = actualS?.let { heldChip(it, targetS) },
                repSlots = row.plannedReps,
                note = if (actualS == null) row.plannedReps?.let { "Plan's target: $it reps" } else null,
                loadDeviation =
                row.plannedLoadKg?.takeIf { it != row.loadKg }?.let { "Deviation (planned ${unit.format(it)})" },
                verdict = Verdict.Frozen(if (actualS != null && corrected) GRADED_BEFORE_CORRECTION else null),
            )
        }

        private fun sourceOf(figure: Int?, whose: Source) = if (figure != null) whose else Source.NONE

        /** " · [discrepancy]", or nothing where there is none. */
        private fun noted(discrepancy: String?) = discrepancy?.let { " · $it" } ?: ""

        /**
         * How the working figure moved off the plan's, e.g. "lowered from
         * 10", or null where the plan gave none or they agree. A fact, never a
         * grade.
         */
        private fun shift(planned: Int?, working: Int, suffix: String): String? = when {
            planned == null || planned == working -> null
            working < planned -> "lowered from $planned$suffix"
            else -> "raised from $planned$suffix"
        }

        /**
         * [shift] for a load, compared as PRINTED in [unit]: a difference too
         * small to print would read "raised from 100 kg" beside a working load
         * that also prints 100 kg.
         */
        private fun loadShift(plannedKg: Double?, workingKg: Double, unit: WeightUnit): String? {
            if (plannedKg == null) return null
            val planned = unit.format(plannedKg)
            if (planned == unit.format(workingKg)) return null
            return if (workingKg < plannedKg) "lowered from $planned" else "raised from $planned"
        }

        /**
         * The chip for [actualS] held against [targetS]: met at or past the
         * target, [TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION] of it or more is
         * close, anything less is well short. No target is not graded.
         *
         * Public since #320: `RecordScreen`'s post-set "Held" chip calls this
         * as well, with the hold's effective seconds and the set's target,
         * where it used to keep its own copy of the 0.9 rule. The two screens
         * now get the chip from this one function. `HeldChipTest` pins it
         * directly. Which arguments the record screen passes is compile- and
         * lint-gated only.
         */
        fun heldChip(actualS: Int, targetS: Int?): HeldChip = HeldChip(
            text = if (targetS != null) "Held $actualS/${targetS}s" else "Held ${actualS}s",
            tone = when {
                targetS == null || actualS >= targetS -> Tone.OK
                actualS >= (targetS * TimedSetEndPolicy.CLOSE_ENOUGH_FRACTION).toInt() -> Tone.WARN
                else -> Tone.BAD
            },
        )
    }
}
