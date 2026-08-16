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
 * Where a permission request has got to, in THIS process.
 *
 * Deliberately four states and deliberately not persisted. The obvious shape is
 * a boolean "have we asked before" in `SettingsStore`, and it is wrong in a way
 * that costs the lifter the sensor: a stored flag survives Android's unused-app
 * auto-reset, which revokes the permission and clears the platform's
 * don't-ask-again state at the same moment. The app would then read "asked
 * before, no rationale, denied" as permanently denied and send the lifter to
 * Settings for a permission the system would have granted on one tap. Scoped to
 * the process, the flag dies with the reset and the next launch simply asks
 * again.
 *
 * [ANSWERED] and [ABANDONED] are separated because the map the platform hands
 * back does not mean one thing. `RequestMultiplePermissions.parseResult` in
 * activity 1.9.3 returns an empty map on three early exits -- `resultCode` not
 * `RESULT_OK`, a null `Intent`, either extra array null -- and a fourth way
 * exists past them: a dismissed dialog delivers `RESULT_OK` with EMPTY
 * permission and grant-result arrays, which reach `CollectionsKt.zip` at offset
 * 155 and produce an empty map from a request nobody answered. A real answer
 * carries one entry per requested permission. So the discriminator is
 * `result.isEmpty()`, and getting it wrong is not cosmetic: an empty map read
 * as [ANSWERED] leaves `shouldShowRequestPermissionRationale` false and lands
 * on [BlePermissionStep.SETTINGS_ONLY] for a lifter who never denied anything.
 */
enum class PermissionAsk {
    /** No request has been issued in this process. */
    NEVER_ASKED,

    /**
     * A request was issued and its result has not arrived.
     *
     * Set only after `launch` returns normally. `ActivityResultLauncher.launch`
     * throws `IllegalStateException` on an unregistered or destroyed launcher,
     * and this state renders the one screen with no button on it, so marking it
     * before the call would latch the buttonless state for the life of the
     * process -- rebuilding the dead end this whole change exists to close.
     */
    IN_FLIGHT,

    /** A result arrived carrying a verdict: one entry per requested permission. */
    ANSWERED,

    /** A result arrived carrying no verdict: an empty map. The request was closed unanswered. */
    ABANDONED,
}

/**
 * What the app must do next about the runtime permissions BLE capture needs.
 *
 * The screen renders this; the wording of each button stays with the screen, as
 * it does for [ExitPrompt].
 */
enum class BlePermissionStep {
    /** Held. Nothing is drawn. */
    GRANTED,

    /**
     * Not held, and the system dialog is either up or about to be.
     *
     * Drawn with no action, because there is nothing to offer that the dialog in
     * front of it does not already offer. It exists as a state rather than as
     * silence so that a request which never happens leaves something on screen:
     * silence is how the defect in issue #41 hid for the whole life of the app.
     */
    AWAITING_ANSWER,

    /** Denied, and the platform will still show the dialog. One tap fixes it. */
    ASK_AGAIN,

    /**
     * The request came back unanswered, and which route works cannot be known
     * from here.
     *
     * `shouldShowRequestPermissionRationale` is false both for a permission
     * never denied and for one denied permanently, and a process-scoped
     * [PermissionAsk] cannot separate them after an [PermissionAsk.ABANDONED]
     * result. Under the first reading asking again works in one tap and Settings
     * is a five-tap detour; under the second, asking again is a button that does
     * nothing visible -- the dead end itself -- and Settings is the only way
     * back. Offering both is the only output that is never a no-op and never a
     * lie. This is an ambiguity in the input, not indecision about the output,
     * and it is resolvable if it ever needs to be: one persisted `hasEverAsked`
     * boolean collapses it exactly, at the cost of the auto-reset behaviour
     * [PermissionAsk] documents.
     */
    ASK_AGAIN_OR_SETTINGS,

    /** Denied with a verdict and no rationale: the platform will not ask again. */
    SETTINGS_ONLY,
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

    /** First SDK level on which `POST_NOTIFICATIONS` exists as a runtime permission. */
    const val POST_NOTIFICATIONS_SDK = 33

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

    /**
     * The runtime permissions BLE capture needs at [sdkInt], and the set whose
     * grant state the app reads back.
     *
     * Names rather than an Android type, for the reason [FgsTypeChoice] carries
     * `"connectedDevice"` as a string: this module cannot see `android.Manifest`,
     * and `BlePermissionManifestTest` checks each name against the manifest that
     * has to declare it. A `List`, never an `Array` -- `assertEquals` on two
     * arrays compares identity, so an array-typed pin fails against a correct
     * implementation and the natural repair is to weaken it to a size check,
     * which is a pin that cannot fail. The caller converts at `launch`.
     *
     * This is NOT the set the app asks for; see [runtimePermissions]. The two
     * differ by `POST_NOTIFICATIONS`, and keeping them apart is the whole point:
     * folding notifications in here would raise "the bar sensor is blocked" at a
     * lifter who merely declined notifications.
     *
     * Below 31 this is location, not Bluetooth, and that is not a quirk of the
     * request: scan results are withheld without it. Connecting to an
     * already-paired sensor is unaffected, which is what [mayConnect] encodes and
     * what [denialBlocksRecording] reports.
     */
    fun blePermissions(sdkInt: Int): List<String> = if (sdkInt >= BLUETOOTH_RUNTIME_PERMISSIONS_SDK) {
        listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
    } else {
        listOf(ACCESS_FINE_LOCATION)
    }

    /**
     * Every runtime permission the app asks for in one request at [sdkInt].
     *
     * A superset of [blePermissions] by `POST_NOTIFICATIONS` from 33. One
     * request rather than two because two launchers cannot be in flight at once
     * and two dialogs back to back is worse than one; the decision that follows
     * reads only [blePermissions], never this.
     */
    fun runtimePermissions(sdkInt: Int): List<String> = blePermissions(sdkInt) +
        if (sdkInt >= POST_NOTIFICATIONS_SDK) listOf(POST_NOTIFICATIONS) else emptyList()

    /**
     * What the app must do next, given what it holds and what it has asked.
     *
     * [granted] is every name in [blePermissions] being held, read from
     * `checkSelfPermission` at the moment of the call and never from the result
     * map -- the map is empty on an abandoned request, and permissions change
     * while the app is backgrounded.
     *
     * Order matters twice. [granted] wins outright, so a grant arriving through
     * Settings clears the banner without anything else being consulted. Then
     * [ask] is read before [shouldShowRationale], which decides the one tuple
     * where both have a claim: `(denied, rationale = true, NEVER_ASKED)` happens
     * routinely, because this state dies with the process while the platform's
     * rationale flag does not, and [BlePermissionStep.AWAITING_ANSWER] is the
     * right answer there -- the launch that clears [PermissionAsk.NEVER_ASKED]
     * is going out in the same frame, and an "Ask again" button under a dialog
     * already rising is a double prompt.
     */
    fun permissionStep(granted: Boolean, shouldShowRationale: Boolean, ask: PermissionAsk): BlePermissionStep = when {
        granted -> BlePermissionStep.GRANTED
        ask == PermissionAsk.NEVER_ASKED || ask == PermissionAsk.IN_FLIGHT -> BlePermissionStep.AWAITING_ANSWER
        shouldShowRationale -> BlePermissionStep.ASK_AGAIN
        ask == PermissionAsk.ABANDONED -> BlePermissionStep.ASK_AGAIN_OR_SETTINGS
        else -> BlePermissionStep.SETTINGS_ONLY
    }

    /**
     * Whether a denial at [sdkInt] costs the recording or only the discovery.
     *
     * From 31 `BLUETOOTH_CONNECT` gates the link itself, so a denial means no
     * samples at all. Below it [mayConnect] still allows a remembered sensor to
     * connect and only the scan for a new one is lost. The banner says one or
     * the other; saying "cannot record" to a lifter on API 30 whose sensor is
     * already paired would be false.
     */
    fun denialBlocksRecording(sdkInt: Int): Boolean = sdkInt >= BLUETOOTH_RUNTIME_PERMISSIONS_SDK

    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
}
