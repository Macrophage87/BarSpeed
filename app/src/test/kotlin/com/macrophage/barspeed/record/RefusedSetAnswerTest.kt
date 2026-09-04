package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What answering a refused body-weight set does, and what a SECOND answer
 * arriving before the first one's write has landed does (#61).
 *
 * WHY THIS RUNS AT ALL. `answerRefusedSet` used to take the concrete
 * `SettingsStore`, which wraps an Android `Context` no `:app` unit test can
 * build. It now takes the durable write as a `suspend (Double) -> Unit`, so
 * the whole decision -- including the ORDER of the state change against the
 * suspension -- is reachable from here. Nothing in this file touches Android,
 * Room or DataStore.
 *
 * REACHABLE FOR [AppendedSlotTest]'s REASON -- `app/build.gradle.kts` pins the
 * test JVM to 21, so a `:app` test may load a `:core:model` type such as
 * [Stage].
 *
 * [Dispatchers.Unconfined] runs a `launch` body on the calling thread until
 * it suspends, so a write that never suspends -- as all three below are --
 * completes before the call returns and the assertions can be flat.
 */
class RefusedSetAnswerTest {
    private fun refused() = RecordState(
        stage = Stage.READY,
        adHoc = true,
        bodyWeightRequiredForSet = true,
    )

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `an answer writes the weight, clears the refusal and begins the set`() {
        val state = MutableStateFlow(refused())
        var written: Double? = null
        var begins = 0
        scope().answerRefusedSet(state, { written = it }, 82.5) { begins++ }
        assertEquals(82.5, written, "the weight was not written")
        assertEquals(82.5, state.value.bodyWeightKg, "the state did not take the answer")
        assertFalse(state.value.bodyWeightRequiredForSet, "the refusal is still standing")
        assertEquals(1, begins, "the set did not begin")
    }

    @Test
    fun `a cancel clears the refusal, writes nothing and begins nothing`() {
        val state = MutableStateFlow(refused())
        var written: Double? = null
        var begins = 0
        scope().answerRefusedSet(state, { written = it }, null) { begins++ }
        assertNull(written, "a cancel wrote a body weight")
        assertNull(state.value.bodyWeightKg, "a cancel put a body weight in the state")
        assertFalse(state.value.bodyWeightRequiredForSet, "the refusal is still standing")
        assertEquals(0, begins, "a cancel started a set")
    }

    @Test
    fun `an answer with no set refused does nothing at all`() {
        val state = MutableStateFlow(refused().copy(bodyWeightRequiredForSet = false))
        var written: Double? = null
        var begins = 0
        scope().answerRefusedSet(state, { written = it }, 82.5) { begins++ }
        assertNull(written, "a stray answer wrote a body weight")
        assertEquals(0, begins, "a stray answer started a set")
        assertTrue(state.value.bodyWeightKg == null, "a stray answer changed the state")
    }
}
