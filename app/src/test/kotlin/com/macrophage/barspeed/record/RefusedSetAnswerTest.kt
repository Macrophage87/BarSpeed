package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.Stage
import kotlinx.coroutines.CompletableDeferred
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
 * it suspends, so a write that never suspends completes before the call
 * returns and the assertions can be flat.
 *
 * THE SCHEDULING IN THE LAST TEST IS DELIBERATE AND IS THE POINT. A write
 * that parks on `CompletableDeferred.await()` parks the coroutine where the
 * real DataStore write parks it, with control back in the test, and the
 * second call then arrives inside that window. What is NOT claimed is that a
 * real device produces this interleaving on any particular pair of taps: a
 * DataStore write is fast and the window is short. This pins the state
 * machine, not the hardware. Whether two taps land inside it on a phone is a
 * [Field] question and is not answered here.
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

    /**
     * RED at this commit. `answerRefusedSet` clears `bodyWeightRequiredForSet`
     * only INSIDE the launched coroutine, after the settings write has been
     * awaited. A second answer arriving while that write is in flight still
     * reads the flag as true, so it writes again and calls `onBegin` again --
     * two sets started from one refusal, the second of them recording over
     * whatever the first was doing.
     *
     * `answerBodyWeight`, the prompt's answer twenty lines further down, does
     * not have this: it clears `pendingBodyWeightSession` synchronously before
     * its own `launch`. The two doors were written to the same rule and only
     * one of them keeps it.
     *
     * The KDoc on `answerRefusedSet` claimed the immunity outright -- "so a
     * double tap on either button cannot start two sets" -- which is what
     * makes this a defect rather than a gap: the claim is in the source.
     */
    @Test
    fun `a second answer during the write neither writes twice nor begins twice`() {
        val state = MutableStateFlow(refused())
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        var begins = 0
        val write: suspend (Double) -> Unit = {
            writes++
            gate.await()
        }
        val scope = scope()
        scope.answerRefusedSet(state, write, 82.5) { begins++ }
        scope.answerRefusedSet(state, write, 82.5) { begins++ }
        gate.complete(Unit)
        assertEquals(1, writes, "the second tap started a second settings write")
        assertEquals(1, begins, "the second tap started a second set")
        assertFalse(state.value.bodyWeightRequiredForSet, "the refusal is still standing")
    }
}
