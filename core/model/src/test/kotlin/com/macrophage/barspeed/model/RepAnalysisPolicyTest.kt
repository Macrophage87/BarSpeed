package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Which finished sets are handed to the rep segmenter (#63).
 *
 * The rows a carry does not touch: a timed set never is, a rep-prescribed
 * dynamic, explosive or hold set always is. These held before the policy was
 * lifted out of `RecordViewModel`, where the timed branch was the only one that
 * kept a stream off the segmenter.
 */
class RepAnalysisPolicyTest {
    @Test
    fun `a timed set is never segmented, whatever its kind`() {
        for (kind in ExerciseKind.entries) {
            assertFalse(RepAnalysisPolicy.segmentsReps(isTimed = true, kind = kind), "timed $kind")
        }
    }

    @Test
    fun `a rep-prescribed set of every kind but a carry is segmented`() {
        for (kind in ExerciseKind.entries - ExerciseKind.CARRY) {
            assertEquals(true, RepAnalysisPolicy.segmentsReps(isTimed = false, kind = kind), "reps $kind")
        }
    }

    /**
     * #63. A carry prescribed in reps is counted by the lifter
     * (`CountingPolicy.counterFor` gives it MANUAL, pinned in
     * CountingPolicyTest), but its stream went to the segmenter, which
     * publishes velocity, ROM and power for whatever runs it finds in a
     * walker's gait. What those figures measure has never been captured
     * ([Field], #63), so none is published; the stream is kept.
     */
    @Test
    fun `a rep-prescribed carry is not segmented`() {
        assertFalse(RepAnalysisPolicy.segmentsReps(isTimed = false, kind = ExerciseKind.CARRY))
    }
}
