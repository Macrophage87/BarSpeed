package com.macrophage.barspeed.model

/** What one link's reconnect loop does next, given the state its client is in. */
enum class ReconnectStep {
    /** Connected: wait until the link drops, then look again. Resets the backoff. */
    WAIT_FOR_DROP,

    /** Connecting: give the attempt in flight two seconds before looking again. */
    WAIT_FOR_CONNECT,

    /** Disconnected or Failed: connect to the address, then back off. */
    CONNECT,
}

/**
 * The reconnect loop's branch, as a total function over [ConnectionState]
 * (#69 item 5).
 *
 * `AutoConnectManager.maintain` branched with an `else ->` arm that took
 * Disconnected and Failed together. Both belong there today, but an `else`
 * also takes any member [ConnectionState] grows later, so a future state
 * meaning "stop trying" -- a refused permission the lifter has to fix, say --
 * would silently join the retry ramp. Here every member is named, so a new
 * one does not compile until someone decides where it goes; `Chips.kt`
 * already enumerates the same four for the same type.
 *
 * In `:core:model` rather than `:core:ble` because `:core:ble` has no test
 * source set; `ReconnectPolicyTest` pins all four. The loop's timings, its
 * backoff and its blanket catch stay where they were.
 */
object ReconnectPolicy {
    fun stepFor(state: ConnectionState): ReconnectStep = when (state) {
        is ConnectionState.Connected -> ReconnectStep.WAIT_FOR_DROP
        ConnectionState.Connecting -> ReconnectStep.WAIT_FOR_CONNECT
        ConnectionState.Disconnected, is ConnectionState.Failed -> ReconnectStep.CONNECT
    }
}
