package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.HistoryTarget
import com.macrophage.barspeed.model.VelocityLossRegime
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [historyVerdicts], the history card's verdict lines (#308): each of
 * [HistoryTarget.Verdict]'s two answers reaches the rest screen's own
 * [restVerdicts] as the question it names.
 *
 * Green pins. What they cannot reach is `SessionDetailScreen` itself: that it
 * hands this function the verdict [HistoryTarget.of] returned and draws what
 * comes back is compile- and lint-gated only.
 */
class HistoryVerdictsTest {
    private val fatigue = "High velocity loss (32%) — significant fatigue this set."

    @Test
    fun `a regrade answer re-grades the hold off the stored seconds, as the rest screen does`() {
        assertEquals(
            listOf("Held 15s of 20s. Consider a shorter target or lighter load."),
            historyVerdicts(
                HistoryTarget.Verdict.Regrade(actualS = 15, targetS = 20),
                frozenVerdicts = listOf("Held 20s — full 20s target. Nice."),
                velocityLossRegime = null,
            ),
        )
    }

    @Test
    fun `a frozen answer shows the frozen sentence, filtered by regime`() {
        assertEquals(
            listOf("Rep 3 was the slowest."),
            historyVerdicts(
                HistoryTarget.Verdict.Frozen(null),
                frozenVerdicts = listOf("Rep 3 was the slowest.", fatigue),
                velocityLossRegime = VelocityLossRegime.CONTROLLED,
            ),
        )
    }

    @Test
    fun `a frozen caption is not drawn as a verdict line`() {
        assertEquals(
            listOf("Held 20s — full 20s target. Nice."),
            historyVerdicts(
                HistoryTarget.Verdict.Frozen("a caption"),
                frozenVerdicts = listOf("Held 20s — full 20s target. Nice."),
                velocityLossRegime = null,
            ),
        )
    }

    @Test
    fun `an uncorrected hold on a row written before v20 keeps the sentence frozen at set end`() {
        // No workingLoadKg: the build that wrote the row could not say what the
        // hold ran against, so there is nothing to re-grade it against.
        val row =
            HistoryTarget.Row(
                actualReps = 0,
                plannedReps = null,
                workingReps = null,
                actualDurationS = 30,
                plannedDurationS = 45,
                workingDurationS = null,
                loadKg = 0.0,
                plannedLoadKg = null,
                workingLoadKg = null,
                durationEndedBy = null,
            )
        val frozen = listOf("Held 30s — full 30s target. Nice.")
        assertEquals(frozen, historyVerdicts(HistoryTarget.of(row, WeightUnit.KG).verdict, frozen, null))
    }
}
