package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The decision behind issue #109's write, pinned where a test can reach it.
 *
 * The write itself runs against Room and is triggered from `:app`; neither is
 * testable here, which is exactly why the decision is not in either.
 */
class FinalRestWindowPolicyTest {
    private fun decide(sampleCount: Int, hasSetToAttachTo: Boolean = true, alreadyWritten: Boolean = false) =
        FinalRestWindowPolicy.decide(sampleCount, hasSetToAttachTo, alreadyWritten)

    @Test
    fun `a window with samples on a session that recorded a set is written`() {
        assertEquals(FinalRestWindowDecision.WRITE, decide(sampleCount = 3))
    }

    /**
     * One sample is a window. The lifter who tapped Finish four seconds after
     * racking the bar still recorded a beat, and the file that carries it is
     * the only statement of what the rate was as the session ended.
     */
    @Test
    fun `a single sample is enough to be written`() {
        assertEquals(FinalRestWindowDecision.WRITE, decide(sampleCount = 1))
    }

    @Test
    fun `nothing arriving after the last set writes no file`() {
        assertEquals(FinalRestWindowDecision.NO_SAMPLES, decide(sampleCount = 0))
    }

    /**
     * A negative count is a caller error and is refused rather than clamped.
     * Clamping to zero would reach the same branch here, but stating it as a
     * count that cannot be written keeps the refusal readable if this ever
     * takes a computed length instead of a list size.
     */
    @Test
    fun `a negative count writes no file`() {
        assertEquals(FinalRestWindowDecision.NO_SAMPLES, decide(sampleCount = -1))
    }

    @Test
    fun `a session with no set row has nothing to attach the window to`() {
        assertEquals(
            FinalRestWindowDecision.NO_SET_TO_ATTACH_TO,
            decide(sampleCount = 5, hasSetToAttachTo = false),
        )
    }

    @Test
    fun `a window already on the set is not written a second time`() {
        assertEquals(
            FinalRestWindowDecision.ALREADY_WRITTEN,
            decide(sampleCount = 5, alreadyWritten = true),
        )
    }

    /**
     * Precedence, stated rather than inferred: an empty buffer on a session
     * with no set row reports the empty buffer.
     *
     * The two refusals are different facts and a reader needs the one that is
     * true of the session in front of them, not whichever branch the `when`
     * happened to reach first.
     */
    @Test
    fun `no samples outranks no set to attach to`() {
        assertEquals(
            FinalRestWindowDecision.NO_SAMPLES,
            decide(sampleCount = 0, hasSetToAttachTo = false),
        )
    }

    /**
     * And the missing set outranks the already-written answer, which on a
     * session with no set row is a claim about a row that does not exist.
     */
    @Test
    fun `no set to attach to outranks a window already written`() {
        assertEquals(
            FinalRestWindowDecision.NO_SET_TO_ATTACH_TO,
            decide(sampleCount = 4, hasSetToAttachTo = false, alreadyWritten = true),
        )
    }
}
