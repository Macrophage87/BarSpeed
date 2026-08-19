package com.macrophage.barspeed.model

/** What Record's SETUP card tells the lifter about a disconnected bar sensor. */
enum class SensorAdvice {
    /** Pair or power on the sensor -- or, when that is not the cause, check the phone's own Bluetooth. */
    PAIR_OR_POWER,

    /** The sensor answered at the GATT level and something else is wrong; pairing or power is not the fix. */
    UNRESPONSIVE,
}

/**
 * Which advice a [ConnectionState] earns, for the one screen that shows
 * more than a dot.
 *
 * Takes the whole state, not just [ConnectionState.Failed]'s reason, because
 * the card this feeds is gated on `!imuConnected` and reaches Connecting and
 * Disconnected too -- both keep the advice they already had, unchanged,
 * because neither promises anything more specific is true than "not
 * connected yet." Only [ConnectionState.Failed.linkEstablished] changes the
 * answer, deliberately: keying on the reason string instead would pass every
 * test in this file that uses two different strings and still be wrong,
 * which is why one test holds the string fixed and varies only the boolean.
 */
object SensorAdvicePolicy {
    fun forState(state: ConnectionState): SensorAdvice = when (state) {
        is ConnectionState.Failed ->
            if (state.linkEstablished) SensorAdvice.UNRESPONSIVE else SensorAdvice.PAIR_OR_POWER
        is ConnectionState.Connected, is ConnectionState.Connecting, is ConnectionState.Disconnected ->
            SensorAdvice.PAIR_OR_POWER
    }
}
