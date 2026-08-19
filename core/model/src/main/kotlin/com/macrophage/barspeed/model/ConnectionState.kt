package com.macrophage.barspeed.model

/**
 * A sensor link's state, as `:core:ble`'s `GattClient` reports it.
 *
 * Lives here rather than in `:core:ble` for the same reason [BlePermissionPolicy]
 * does: `:core:ble` and `:app` have no test source set, so a decision that
 * consumes this type cannot be tested from either. This type has no Android
 * dependency of its own -- it never did -- so moving it costs nothing.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val deviceName: String, val batteryPct: Int? = null) : ConnectionState

    /**
     * [linkEstablished] is `false` by default, deliberately: a producer that
     * does not set it is choosing the conservative reading, not omitting one.
     * `true` is claimed only where the code path proves a device answered and
     * a real GATT profile mismatch is what stopped it -- not merely that a
     * link once existed, because a link that formed and then died looks
     * identical up to that point and must not be told apart from a sensor
     * that was never found. That is the one fact [SensorAdvicePolicy] needs
     * to stop telling a lifter to pair or power on a sensor that already
     * answered.
     *
     * Three producers in `GattClients.kt` (the permission-revoked catches in
     * `onConnectionStateChange`, `onMtuChanged` and `onServicesDiscovered`)
     * fire provably after `STATE_CONNECTED`, which is itself proof a device
     * answered at the link layer -- yet all three still carry `false`. Named
     * here rather than left for a reader to discover: `MainActivity`'s own
     * KDoc states that revoking BLUETOOTH_CONNECT from Settings kills the
     * process, which is the actual reason these three are harmless -- not
     * anything this policy or its caller does.
     */
    data class Failed(val reason: String, val linkEstablished: Boolean = false) : ConnectionState
}
