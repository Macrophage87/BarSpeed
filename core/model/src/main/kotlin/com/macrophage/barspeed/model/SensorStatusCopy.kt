package com.macrophage.barspeed.model

/**
 * The raw [ConnectionState.Failed.reason] strings `GattClient` and
 * `BleScanner` produce, named once so [SensorStatusCopy] can map them
 * without a second copy of the text drifting away from the first.
 */
object ConnectionFailureReason {
    /** Issue #20's own wording. */
    const val PERMISSION_DENIED = "Bluetooth permission not granted"

    /** `connectGatt` returned null and the adapter read disabled (#103); also the scan's. */
    const val PHONE_BLUETOOTH_OFF = "Phone Bluetooth is off"

    /** `connectGatt` returned null with the adapter on (#103). */
    const val NO_LINK_OPENED = "No link could be opened"

    /** No Bluetooth adapter at all. */
    const val BLUETOOTH_UNAVAILABLE = "Bluetooth unavailable"
}

/**
 * One line of plain words about a link, with the raw reason kept for the
 * small grey line beneath it, and which buttons go with it.
 *
 * [detail] is the developer's string, verbatim, or null when it would only
 * repeat [line]. It is kept rather than dropped because it is what a bug
 * report needs.
 */
data class StatusCopy(
    val line: String,
    val detail: String? = null,
    val offerRetry: Boolean = false,
    val offerTurnOnBluetooth: Boolean = false,
)

/**
 * What the Devices screen says about a link, in a lifter's words, UX review
 * 2026-11-06 (ble-devices F5, F7).
 *
 * Devices is where the lifter goes to fix a sensor, and it printed
 * "Service discovery failed (133)" verbatim, or an amber "IMU …" with no
 * words at all for the commonest case of a sensor switched off or out of
 * range. Which advice applies comes from [ConnectionState.Failed]'s booleans
 * first, as [SensorAdvicePolicy] does, and only then from the reason string.
 */
object SensorStatusCopy {
    /**
     * How long a link may sit in [ConnectionState.Connecting] before the row
     * says the unit has not been found. `GattClient` connects with
     * `autoConnect = true`, which waits indefinitely and says nothing.
     */
    const val NOT_FOUND_AFTER_MS = 10_000L

    /**
     * The name a row, chip or sentence uses for a paired device: "Sensor A",
     * "Sensor B", "Bar sensor" while unlabelled, or "Heart-rate strap" (the
     * nouns the copy pass settled on, copy F4).
     */
    fun deviceName(isStrap: Boolean, role: SensorRole?): String = when {
        isStrap -> "Heart-rate strap"
        role == null -> "Bar sensor"
        else -> "Sensor ${role.name}"
    }

    /**
     * What to say about a link that has failed. [isStrap] changes only the
     * noun and the physical advice: a strap has no power button.
     */
    fun failed(state: ConnectionState.Failed, isStrap: Boolean): StatusCopy {
        val noun = if (isStrap) "heart-rate strap" else "bar sensor"
        return when {
            state.phoneBluetoothOff ->
                StatusCopy("Your phone's Bluetooth is off.", offerTurnOnBluetooth = true)
            state.reason == ConnectionFailureReason.PERMISSION_DENIED ->
                StatusCopy("BarSpeed isn't allowed to use Bluetooth. Grant Nearby devices above.", state.reason)
            state.reason == ConnectionFailureReason.BLUETOOTH_UNAVAILABLE ->
                StatusCopy("This phone's Bluetooth can't be used.", state.reason)
            state.linkEstablished ->
                StatusCopy(
                    "It answered, but it isn't a $noun BarSpeed can read. Check you paired the right device.",
                    state.reason,
                )
            isStrap ->
                StatusCopy(
                    "Couldn't connect. Check the heart-rate strap is on and close by, then tap Retry.",
                    state.reason,
                    offerRetry = true,
                )
            else ->
                StatusCopy(
                    "Couldn't connect. Turn the sensor off and on, then tap Retry.",
                    state.reason,
                    offerRetry = true,
                )
        }
    }

    /**
     * What to say about a link that is still trying, or null while it is too
     * early to say anything. [name] is [deviceName]'s answer.
     */
    fun connecting(name: String, waitedMs: Long): String? =
        if (waitedMs < NOT_FOUND_AFTER_MS) null else "$name: not found nearby. Is it on and close by?"

    /** What to say when a scan could not start, from the scanner's message. */
    fun scanFailed(raw: String): StatusCopy = when (raw) {
        ConnectionFailureReason.PHONE_BLUETOOTH_OFF ->
            StatusCopy("Your phone's Bluetooth is off.", offerTurnOnBluetooth = true)
        ConnectionFailureReason.BLUETOOTH_UNAVAILABLE ->
            StatusCopy("This phone's Bluetooth can't be used.", raw)
        else -> StatusCopy("Couldn't scan. Turn Bluetooth off and on, then scan again.", raw)
    }
}
