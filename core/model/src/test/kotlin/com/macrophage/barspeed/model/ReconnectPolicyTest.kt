package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reconnect loop's branch for every [ConnectionState] (#69 item 5).
 *
 * Characterization: each answer is what `AutoConnectManager.maintain`'s
 * `when` did at its parent, arm for arm. What the compiler adds and no test
 * can is that a new [ConnectionState] member does not compile until
 * [ReconnectPolicy.stepFor] names it.
 */
class ReconnectPolicyTest {
    @Test
    fun `a connected link waits for the drop`() {
        assertEquals(ReconnectStep.WAIT_FOR_DROP, ReconnectPolicy.stepFor(ConnectionState.Connected("WT901BLECL")))
        assertEquals(
            ReconnectStep.WAIT_FOR_DROP,
            ReconnectPolicy.stepFor(ConnectionState.Connected("WT901BLECL", batteryPct = 80)),
        )
    }

    @Test
    fun `a connecting link waits for the attempt in flight`() {
        assertEquals(ReconnectStep.WAIT_FOR_CONNECT, ReconnectPolicy.stepFor(ConnectionState.Connecting))
    }

    @Test
    fun `a never-connected link connects`() {
        assertEquals(ReconnectStep.CONNECT, ReconnectPolicy.stepFor(ConnectionState.Disconnected))
    }

    @Test
    fun `a failed link connects again, whatever the failure`() {
        // The loop retries every Failed today, the permission refusal
        // included: connect() re-checks the permission on each attempt, and
        // the retry is what picks up a grant made in Settings. Every flag
        // Failed carries is varied, so a stepFor that reads one of them
        // reds here.
        for (linkEstablished in listOf(false, true)) {
            for (phoneBluetoothOff in listOf(false, true)) {
                assertEquals(
                    ReconnectStep.CONNECT,
                    ReconnectPolicy.stepFor(
                        ConnectionState.Failed(
                            "reason",
                            linkEstablished = linkEstablished,
                            phoneBluetoothOff = phoneBluetoothOff,
                        ),
                    ),
                    "Failed(linkEstablished=$linkEstablished, phoneBluetoothOff=$phoneBluetoothOff)",
                )
            }
        }
    }
}
