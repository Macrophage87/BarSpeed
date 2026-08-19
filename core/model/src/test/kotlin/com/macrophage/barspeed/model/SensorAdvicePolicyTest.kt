package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What Record's SETUP card tells the lifter about a disconnected bar
 * sensor, pinned here because :app has no test source set of its own.
 *
 * All four [ConnectionState] variants are exercised, not just [ConnectionState.Failed]:
 * the gate this feeds is `!imuConnected`, so Connecting and Disconnected
 * reach the same advice site Failed does, and before this policy existed
 * all three were silently handled by whatever fell out of not being
 * Failed. Pinning only Failed's two reasons would leave the other three
 * quarters of the decision exactly where it was -- in :app, untested.
 */
class SensorAdvicePolicyTest {
    @Test
    fun `a connected sensor gets the generic advice, because SETUP never shows it`() {
        // Unreachable in practice -- SETUP's gate is !imuConnected -- but
        // forState is total over ConnectionState, so the branch exists and
        // is pinned rather than left to an unchecked `else`.
        assertEquals(
            SensorAdvice.PAIR_OR_POWER,
            SensorAdvicePolicy.forState(ConnectionState.Connected("WT901BLECL")),
        )
    }

    @Test
    fun `a connecting sensor gets the generic advice, unchanged from before this policy`() {
        assertEquals(SensorAdvice.PAIR_OR_POWER, SensorAdvicePolicy.forState(ConnectionState.Connecting))
    }

    @Test
    fun `a never-tried sensor gets the generic advice, unchanged from before this policy`() {
        assertEquals(SensorAdvice.PAIR_OR_POWER, SensorAdvicePolicy.forState(ConnectionState.Disconnected))
    }

    @Test
    fun `a failure with no established link gets the generic advice`() {
        // linkEstablished defaults false, so this also covers every producer
        // that predates the field and every future one that forgets to set it.
        assertEquals(
            SensorAdvice.PAIR_OR_POWER,
            SensorAdvicePolicy.forState(ConnectionState.Failed("Bluetooth is off or the link was refused")),
        )
    }

    @Test
    fun `a failure after a real link tells the lifter the sensor answered`() {
        // A different reason string than the case above, deliberately: the
        // policy must key on linkEstablished, not on the text -- and the
        // next test makes that the only way to pass, not just a hint.
        assertEquals(
            SensorAdvice.UNRESPONSIVE,
            SensorAdvicePolicy.forState(
                ConnectionState.Failed("Expected service/characteristic not found", linkEstablished = true),
            ),
        )
    }

    @Test
    fun `linkEstablished decides the advice, not the reason string`() {
        // Both calls share one reason string. An implementation that matches
        // on the text -- for example, branching on whether the string
        // mentions "not found" -- passes every test above by accident and
        // reds only here, where the string cannot tell the two apart and
        // only the boolean can.
        val sameReason = "identical reason text, deliberately"
        val notEstablished = SensorAdvicePolicy.forState(ConnectionState.Failed(sameReason, linkEstablished = false))
        val established = SensorAdvicePolicy.forState(ConnectionState.Failed(sameReason, linkEstablished = true))
        assertEquals(SensorAdvice.PAIR_OR_POWER, notEstablished)
        assertEquals(SensorAdvice.UNRESPONSIVE, established)
        assertNotEquals(notEstablished, established)
    }
}
