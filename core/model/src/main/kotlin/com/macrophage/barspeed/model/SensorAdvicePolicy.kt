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
 * Returns [SensorAdvice.PAIR_OR_POWER] unconditionally for now -- a
 * genuinely true identity lift, not a placeholder: nothing today reads
 * [ConnectionState.Failed.linkEstablished], so every disconnected state
 * already earns the same generic advice this returns. The next commit reds
 * a test that requires this to change; the one after makes it change.
 */
object SensorAdvicePolicy {
    @Suppress("UnusedParameter", "UNUSED_PARAMETER") // Read starting the next commit.
    fun forState(state: ConnectionState): SensorAdvice = SensorAdvice.PAIR_OR_POWER
}
