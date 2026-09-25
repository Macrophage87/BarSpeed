package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.HistoryTarget
import com.macrophage.barspeed.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Red differential for #308: the history card's verdict sentence on a v20
 * hold is re-graded through the rest screen's own [restVerdicts], off the
 * stored seconds and against the working target -- not the sentence frozen at
 * set end.
 *
 * Plan 45 s, lowered to 30 s, recorded at 20 s and corrected on the rest
 * screen to 30 s. The sentence frozen at set end graded the pre-correction
 * 20 s against the working 30 s. The history card must say what the rest
 * screen says for the corrected figure.
 *
 * On an UNCORRECTED v20 hold the frozen sentence already graded the recorded
 * seconds against the working target (`RecordViewModel.endSet`), so there the
 * re-grade reads the same words; the differential is the corrected row.
 */
class HistoryVerdictsDifferentialTest {
    @Test
    fun `working 30 planned 45 held 30 re-grades the history sentence through restVerdicts`() {
        val row =
            HistoryTarget.Row(
                actualReps = 0,
                plannedReps = null,
                workingReps = null,
                actualDurationS = 30,
                plannedDurationS = 45,
                workingDurationS = 30,
                loadKg = 0.0,
                plannedLoadKg = null,
                workingLoadKg = 0.0,
                durationEndedBy = "corrected",
            )
        val frozenAtSetEnd = listOf("Held 20s of 30s. Consider a shorter target or lighter load.")
        assertEquals(
            listOf("Held 30s — full 30s target. Nice."),
            historyVerdicts(HistoryTarget.of(row, WeightUnit.KG).verdict, frozenAtSetEnd, null),
        )
    }
}
