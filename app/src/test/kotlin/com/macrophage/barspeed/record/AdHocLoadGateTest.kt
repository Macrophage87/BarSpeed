package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.Stage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [adHocLoadRefused]: the projection `beginSet` and the START buttons share. */
class AdHocLoadGateTest {
    private fun state(adHoc: Boolean, loadInput: String, exerciseId: String = "back_squat") = RecordState(
        stage = Stage.READY,
        adHoc = adHoc,
        loadInput = loadInput,
        selectedExerciseId = exerciseId,
    )

    @Test
    fun `an ad-hoc back squat with a blank or unparseable load is refused`() {
        assertTrue(state(adHoc = true, loadInput = "").adHocLoadRefused())
        assertTrue(state(adHoc = true, loadInput = "60kg").adHocLoadRefused())
    }

    @Test
    fun `an ad-hoc set with a number, a comma decimal or an explicit 0 starts`() {
        assertFalse(state(adHoc = true, loadInput = "60").adHocLoadRefused())
        assertFalse(state(adHoc = true, loadInput = "52,5").adHocLoadRefused())
        assertFalse(state(adHoc = true, loadInput = "0").adHocLoadRefused())
    }

    @Test
    fun `an ad-hoc body-weight movement starts with a blank box`() {
        val id = ExerciseDef.BODYWEIGHT_IDS.first()
        assertFalse(state(adHoc = true, loadInput = "", exerciseId = id).adHocLoadRefused())
    }

    @Test
    fun `a plan session is never refused here`() {
        assertFalse(state(adHoc = false, loadInput = "abc").adHocLoadRefused())
    }
}
