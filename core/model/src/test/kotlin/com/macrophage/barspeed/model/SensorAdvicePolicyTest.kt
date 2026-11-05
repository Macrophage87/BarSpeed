package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What Record's SETUP card tells the lifter about a disconnected bar
 * sensor, pinned here because no test on the CI path renders that card.
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
    fun `a failure with the phone's Bluetooth read on keeps the generic advice`() {
        // #103. phoneBluetoothOff false is every producer that did not read
        // the adapter disabled, so it must leave the existing two answers
        // exactly where they were.
        assertEquals(
            SensorAdvice.PAIR_OR_POWER,
            SensorAdvicePolicy.forState(ConnectionState.Failed("The link was refused", phoneBluetoothOff = false)),
        )
        assertEquals(
            SensorAdvice.UNRESPONSIVE,
            SensorAdvicePolicy.forState(
                ConnectionState.Failed(
                    "Expected service/characteristic not found",
                    linkEstablished = true,
                    phoneBluetoothOff = false,
                ),
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

    @Test
    fun `a failure with the phone's Bluetooth read off says to turn it on`() {
        // #103: GattClient.connect() read the adapter disabled when
        // connectGatt returned null.
        assertEquals(
            SensorAdvice.TURN_ON_PHONE_BLUETOOTH,
            SensorAdvicePolicy.forState(ConnectionState.Failed("Phone Bluetooth is off", phoneBluetoothOff = true)),
        )
    }

    @Test
    fun `phoneBluetoothOff decides the advice, not the reason string`() {
        // The same construction as the linkEstablished test above: one
        // reason string, only the boolean varied, so an implementation
        // matching on the text ("off", "Bluetooth") cannot pass.
        val sameReason = "identical reason text, deliberately"
        val on = SensorAdvicePolicy.forState(ConnectionState.Failed(sameReason, phoneBluetoothOff = false))
        val off = SensorAdvicePolicy.forState(ConnectionState.Failed(sameReason, phoneBluetoothOff = true))
        assertEquals(SensorAdvice.PAIR_OR_POWER, on)
        assertEquals(SensorAdvice.TURN_ON_PHONE_BLUETOOTH, off)
    }

    @Test
    fun `the phone's Bluetooth being off outranks an established link`() {
        // No producer sets both today -- phoneBluetoothOff is set only where
        // connectGatt returned null, before any link -- but forState is total
        // over Failed's fields, so the precedence is decided here rather than
        // left to arm order. With the phone's radio off nothing about the
        // sensor can be fixed first, so that advice wins.
        assertEquals(
            SensorAdvice.TURN_ON_PHONE_BLUETOOTH,
            SensorAdvicePolicy.forState(
                ConnectionState.Failed("reason", linkEstablished = true, phoneBluetoothOff = true),
            ),
        )
    }
}
