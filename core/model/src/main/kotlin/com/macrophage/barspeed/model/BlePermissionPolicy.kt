package com.macrophage.barspeed.model

/**
 * Which foreground-service type the recording service may claim.
 *
 * [manifestToken] is the `android:foregroundServiceType` token the app
 * manifest has to declare for that choice to be legal. `ForegroundServiceContractTest`
 * pins the two together: the platform rejects a type the manifest never
 * declared, and nothing else in this build checks the pair.
 */
enum class FgsTypeChoice(val manifestToken: String) {
    CONNECTED_DEVICE("connectedDevice"),
    SPECIAL_USE("specialUse"),
}

/**
 * Pure decisions about Android's runtime Bluetooth permissions.
 *
 * These live here rather than in `:core:ble` or `:app` for one reason: neither
 * of those modules has a test source set, so a decision written there cannot
 * be tested at all. Nothing in this file calls Android. Callers pass
 * `Build.VERSION.SDK_INT` and the result of `checkSelfPermission`, and get a
 * decision back.
 */
object BlePermissionPolicy {
    /** First SDK level on which `BLUETOOTH_CONNECT` exists as a runtime permission. */
    const val BLUETOOTH_RUNTIME_PERMISSIONS_SDK = 31

    /** First SDK level that checks a foreground-service type against the permissions held. */
    const val FGS_TYPE_ENFORCED_SDK = 34

    /**
     * May a GATT connection be attempted?
     *
     * The `sdkInt <` term carries the whole risk here. `BLUETOOTH_CONNECT` did
     * not exist before 31, so `checkSelfPermission` reports it denied on every
     * API 26-30 device. Gating on the permission alone would turn a crash that
     * affects some devices into no capture at all on every older one, which is
     * the worse failure by a wide margin: a crash is visible, a set that was
     * never recorded is discovered after the session.
     */
    fun mayConnect(sdkInt: Int, hasBluetoothConnect: Boolean): Boolean =
        sdkInt < BLUETOOTH_RUNTIME_PERMISSIONS_SDK || hasBluetoothConnect

    /**
     * Which foreground-service type the recording service may claim.
     *
     * The `sdkInt <` arm is required, not merely truthful. Its consumer
     * dispatches through `androidx.core.app.ServiceCompat`, which masks the
     * type with `0xFF` on API 29-33 and with `0x40000FFF` from 34.
     * `SPECIAL_USE` is `0x40000000`, so returning it below 34 would be masked
     * to `FOREGROUND_SERVICE_TYPE_NONE` before the platform ever saw it;
     * `CONNECTED_DEVICE` is `0x10` and survives both. Below 34 the platform
     * also does not check a declared type against the permissions held, so the
     * arm is the truthful answer as well — but it is the mask that makes it
     * mandatory. Whether `SPECIAL_USE` would be accepted on that band is
     * unverified, and this deliberately never asks.
     *
     * From 34 the check is enforced, and failing it throws at the exact moment
     * the lifter taps START SET. Two published sources describe what satisfies
     * `connectedDevice` and they do not agree; the enforcement source itself
     * (`ForegroundServiceTypePolicy.java`) could not be read, so both are
     * named rather than one being picked. The platform's own
     * `attrs_manifest.xml` lists `BLUETOOTH_CONNECT`, `CHANGE_NETWORK_STATE`,
     * `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`
     * and USB. Google's `services/fgs/service-types` page splits the
     * requirement in two and accepts any one of `BLUETOOTH_CONNECT`,
     * `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` or `UWB_RANGING` at runtime —
     * and this app declares `BLUETOOTH_SCAN` (`AndroidManifest.xml:5`) as well
     * as `BLUETOOTH_CONNECT` (`:8`), so under that reading `connectedDevice`
     * could still be legal with `BLUETOOTH_CONNECT` alone denied.
     *
     * Keying on `BLUETOOTH_CONNECT` is therefore a deliberate over-degrade,
     * and it is fail-safe under either reading: `MainActivity.kt:41-44`
     * requests SCAN and CONNECT in one `RequestMultiplePermissions` call so
     * they move together, and the only available error is claiming
     * `SPECIAL_USE` where `CONNECTED_DEVICE` would also have been allowed,
     * which costs a Play-policy label rather than a recording.
     */
    fun foregroundServiceType(sdkInt: Int, hasBluetoothConnect: Boolean): FgsTypeChoice = when {
        sdkInt < FGS_TYPE_ENFORCED_SDK -> FgsTypeChoice.CONNECTED_DEVICE
        hasBluetoothConnect -> FgsTypeChoice.CONNECTED_DEVICE
        else -> FgsTypeChoice.SPECIAL_USE
    }
}
