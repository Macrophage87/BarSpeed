package com.macrophage.barspeed.model

/** What Record's SETUP card tells the lifter about a disconnected bar sensor. */
enum class SensorAdvice {
    /** Pair or power on the sensor -- or, when that is not the cause, check the phone's own Bluetooth. */
    PAIR_OR_POWER,

    /** The sensor answered at the GATT level and something else is wrong; pairing or power is not the fix. */
    UNRESPONSIVE,

    /**
     * The phone's own Bluetooth read disabled when the connect failed (#103):
     * `GattClient.connect()` reads the adapter when `connectGatt` returns
     * null and sets [ConnectionState.Failed.phoneBluetoothOff] from it.
     */
    TURN_ON_PHONE_BLUETOOTH,
}

/**
 * Which advice a [ConnectionState] earns, for the one screen that shows
 * more than a dot.
 *
 * Takes the whole state, not just [ConnectionState.Failed]'s reason, because
 * the card this feeds is gated on `!imuConnected` and reaches Connecting and
 * Disconnected too -- both keep the advice they already had, unchanged,
 * because neither promises anything more specific is true than "not
 * connected yet." Only [ConnectionState.Failed]'s two booleans change the
 * answer, deliberately: keying on the reason string instead would pass every
 * test in this file that uses two different strings and still be wrong,
 * which is why one test per boolean holds the string fixed and varies only
 * that boolean.
 *
 * [ConnectionState.Failed.phoneBluetoothOff] is read first (#103): with the
 * phone's radio off nothing about the sensor can be fixed before it, so that
 * advice outranks an established link. No producer sets both today.
 */
object SensorAdvicePolicy {
    fun forState(state: ConnectionState): SensorAdvice = when (state) {
        is ConnectionState.Failed ->
            when {
                state.phoneBluetoothOff -> SensorAdvice.TURN_ON_PHONE_BLUETOOTH
                state.linkEstablished -> SensorAdvice.UNRESPONSIVE
                else -> SensorAdvice.PAIR_OR_POWER
            }
        is ConnectionState.Connected, is ConnectionState.Connecting, is ConnectionState.Disconnected ->
            SensorAdvice.PAIR_OR_POWER
    }
}
