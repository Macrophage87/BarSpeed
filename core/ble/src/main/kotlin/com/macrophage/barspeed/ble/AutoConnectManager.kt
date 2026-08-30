package com.macrophage.barspeed.ble

import android.content.Context
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.DeviceLinkRole
import com.macrophage.barspeed.model.DevicePairingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Keeps both sensors connected (spec 4.1): each role connects independently and
 * in parallel — the HRM being absent never blocks the IMU. Reconnects with
 * capped exponential backoff; pairing is remembered via [DeviceRegistry].
 */
class AutoConnectManager(
    context: Context,
    private val registry: DeviceRegistry,
    private val scope: CoroutineScope,
) {
    val imuClient = WitmotionClient(context)
    val hrmClient = HrmClient(context)

    /**
     * The second accelerometer, issue #156.
     *
     * A second CLIENT and not a second address fed to the first one, and that
     * is forced rather than preferred. [WitmotionStreamDecoder] holds a single
     * `ArrayDeque` that `feed` drains with a resync-on-0x55 loop, and the
     * WT901's 20-byte frames carry NO CHECKSUM -- the decoder's own KDoc says
     * so. Interleaving two devices' notification payloads into one buffer
     * therefore lets the resync assemble a "frame" from the tail of one unit's
     * packet and the head of the other's. That does not merely confuse the
     * stream; it fabricates plausible samples. `BluetoothGatt` is per-remote
     * device in any case.
     *
     * [imuClient], [imuState] and [imuSamples] keep their exact present
     * meaning -- THE ANALYSED SENSOR -- and everything that reads them is
     * untouched. This is the sibling, and the app decides which physical unit
     * it maintains.
     */
    val imuClientB = WitmotionClient(context)

    val imuState: StateFlow<ConnectionState> = imuClient.connectionState
    val imuStateB: StateFlow<ConnectionState> = imuClientB.connectionState
    val hrmState: StateFlow<ConnectionState> = hrmClient.connectionState
    val imuSamples: SharedFlow<com.macrophage.barspeed.model.ImuSample> = imuClient.samples
    val imuSamplesB: SharedFlow<com.macrophage.barspeed.model.ImuSample> = imuClientB.samples
    val hrSamples: SharedFlow<com.macrophage.barspeed.model.HrSample> = hrmClient.samples

    private var imuJob: Job? = null
    private var imuJobB: Job? = null
    private var hrmJob: Job? = null

    /**
     * Which device the second link maintains, or null to leave it down.
     *
     * A plain address handed in from outside rather than a third
     * [DeviceRole], because [DeviceRole] cannot safely grow one:
     * [DeviceRegistry.keyFor] is a binary `if` that maps anything other than
     * IMU to `preferred_hrm`, so pairing under a new role would overwrite the
     * heart-rate strap's preferred address, and [KnownDevice] is
     * `@Serializable` with the enum on the wire -- a build that has never seen
     * the new value throws on decode and its `catch` returns an EMPTY list,
     * losing every paired device. Both WT901s stay ordinary `DeviceRole.IMU`
     * rows, which is the shape [DeviceRegistry]'s own KDoc already describes.
     */
    private val secondaryImuAddress = MutableStateFlow<String?>(null)

    /**
     * The address the second link is actually pointed at, or null when it is
     * pointed at nothing.
     *
     * Published because the Devices screen was GUESSING it -- "the first
     * paired IMU that is not the preferred one" -- and then rendering that
     * guess's connection state as if a link were maintaining it. Under every
     * `DualShortfall` there is no such link, so a healthy unlabelled unit drew
     * a Disconnected chip: a link failure reported where there is no link
     * (#184). The screen now asks rather than guesses, and draws a distinct
     * state when the answer is null.
     */
    val secondaryImuAddressNow: StateFlow<String?> = secondaryImuAddress

    /**
     * Point the second link at a device, or take it down.
     *
     * Disconnecting on null is done HERE rather than inside the reconnect
     * loop, so that loop's behaviour is byte-for-byte what it was for the two
     * links that already used it: a null address there has always meant
     * "nothing paired yet, look again in three seconds", and turning that into
     * "disconnect" would change what happens to the analysed sensor and the
     * strap while the registry is momentarily empty.
     */
    fun setSecondaryImuAddress(address: String?) {
        if (secondaryImuAddress.value == address) return
        secondaryImuAddress.value = address
        // Dropped on EVERY change, not only on null. The loop's Connected
        // branch waits for the link to fall over before doing anything else,
        // so a client already holding the old device would sit there for the
        // rest of the session and the new one would never be reached.
        imuClientB.disconnect()
    }

    /** Begin maintaining connections to the preferred devices in parallel. */
    fun start() {
        if (imuJob == null) {
            imuJob = scope.launch { maintain(imuClient) { registry.preferredNow(DeviceRole.IMU)?.address } }
        }
        if (hrmJob == null) {
            hrmJob = scope.launch { maintain(hrmClient) { registry.preferredNow(DeviceRole.HRM)?.address } }
        }
        // The third link runs whether or not an address has been set: with
        // none it sits in the same three-second idle the other two use before
        // anything is paired, so arming dual mid-session costs no restart.
        if (imuJobB == null) {
            imuJobB = scope.launch { maintain(imuClientB) { secondaryImuAddress.value } }
        }
    }

    fun stop() {
        imuJob?.cancel()
        imuJobB?.cancel()
        hrmJob?.cancel()
        imuJob = null
        imuJobB = null
        hrmJob = null
        imuClient.disconnect()
        imuClientB.disconnect()
        hrmClient.disconnect()
    }

    /**
     * Pair a device, and connect this role's link to it only if it became the
     * preferred one.
     *
     * [DeviceRegistry.pair] no longer prefers whatever was paired last (#184),
     * so this can no longer assume the newly paired device owns the role's
     * link. Grabbing [imuClient] for a second bar sensor while `preferred_imu`
     * still names the first would point the ANALYSED client at one unit and
     * the reconnect loop's address provider at another, and the loop's
     * Connected branch waits for the link to drop before looking again -- so
     * the disagreement would survive for the rest of the session.
     *
     * The registry is asked rather than the policy re-run here: one answer,
     * read back from where it was written.
     */
    suspend fun pairAndConnect(device: KnownDevice) {
        registry.pair(device)
        if (registry.preferredNow(device.role)?.address == device.address) {
            clientFor(device.role).connect(device.address)
        }
    }

    /**
     * Make an already-paired device this role's preferred one and move the
     * link to it.
     *
     * The link is dropped rather than redirected: `maintain`'s Connected
     * branch is parked on `connectionState.first { it !is Connected }`, so a
     * client already holding the old device would sit there indefinitely and
     * the new address would never be read. Dropping it wakes that branch, and
     * the next pass reads the new preferred address -- the same reasoning
     * [setSecondaryImuAddress] already uses.
     *
     * WHICH links to drop is
     * `DevicePairingPolicy.linksToDropOnPrefer`'s answer rather than this
     * function's, for [forgetAndDrop]'s reason: `:core:ble` has no test source
     * set. The rule also takes the SECOND link down when the promoted address
     * is the one that link is already holding, so this is no longer equivalent
     * to the bare `clientFor(device.role).disconnect()` it replaced. An earlier
     * draft of this paragraph, written when the rule was a pure lift, said
     * nothing about what this function does had changed; that stopped being
     * true when the rule gained its SECOND clause, and it is deleted rather
     * than reworded.
     */
    suspend fun setPreferredAndConnect(device: KnownDevice) {
        val ownedLink =
            if (device.role == DeviceRole.IMU) DeviceLinkRole.ANALYSED else DeviceLinkRole.HEART_RATE
        val drop =
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = ownedLink,
                newlyPreferred = device.address,
                secondImu = secondaryImuAddress.value,
            )
        registry.setPreferred(device.address, device.role)
        // Before the two GATT drops, for [forgetAndDrop]'s reason: this one
        // also nulls the address the third link reads, so waking it cannot
        // have it reconnect to a unit another link is now on.
        if (DeviceLinkRole.SECOND in drop) setSecondaryImuAddress(null)
        if (DeviceLinkRole.ANALYSED in drop) imuClient.disconnect()
        if (DeviceLinkRole.HEART_RATE in drop) hrmClient.disconnect()
    }

    /**
     * Forget a device, and drop whichever links were pointed at it.
     *
     * `DeviceRegistry.forget` promotes a survivor into the role's preferred
     * address (#184), and `maintain`'s Connected branch is parked on
     * `connectionState.first { it !is Connected }`, so a client holding the
     * FORGOTTEN unit would keep streaming it while the screen and
     * `SensorCapturePolicy.roster` both name the survivor. Dropping wakes that
     * branch, and the next pass reads the promoted address -- the same
     * reasoning [setSecondaryImuAddress] and [setPreferredAndConnect] already
     * use.
     *
     * Which links to drop is asked BEFORE the forget, because the forget is
     * what moves the preference: afterwards there is nothing left to compare
     * the forgotten address against. The paired bar sensors are read here for
     * the same reason: `DevicePairingPolicy.linksToDropOnForget` needs to know
     * what the forget is about to promote, and once `registry.forget` has
     * returned that is no longer a question anything can ask.
     */
    suspend fun forgetAndDrop(device: KnownDevice) {
        val pairedImu = registry.knownDevices.first().filter { it.role == DeviceRole.IMU }.map { it.address }
        val drop =
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = device.address,
                preferredImu = registry.preferredNow(DeviceRole.IMU)?.address,
                preferredHrm = registry.preferredNow(DeviceRole.HRM)?.address,
                secondImu = secondaryImuAddress.value,
                remainingImu = pairedImu.filterNot { it == device.address },
            )
        registry.forget(device.address)
        // Before the two GATT drops: this one also nulls the address the third
        // link reads, so waking it cannot have it reconnect to the unit that
        // was just forgotten.
        if (DeviceLinkRole.SECOND in drop) setSecondaryImuAddress(null)
        if (DeviceLinkRole.ANALYSED in drop) imuClient.disconnect()
        if (DeviceLinkRole.HEART_RATE in drop) hrmClient.disconnect()
    }

    /**
     * The reconnect loop for one link. It runs on the process-wide `appScope`,
     * a `SupervisorJob` with no `CoroutineExceptionHandler`, and `start()`
     * launches it exactly once behind `if (imuJob == null)` — so anything that
     * escapes this function reaches the default uncaught handler and kills the
     * process, and nothing ever relaunches the link.
     *
     * Hence the whole body is guarded, not just the connect call. The other
     * throw in here is [DeviceRegistry.preferredNow], whose try/catch covers
     * only the JSON decode and not the DataStore read behind it; an unreadable
     * DataStore raises something that is not a [SecurityException] from this
     * same coroutine. A catch narrowed to permission errors would have missed
     * it.
     *
     * Takes the CLIENT and an address provider rather than a [DeviceRole], since
     * #156: there are three links and only two roles, because both
     * accelerometers are ordinary `DeviceRole.IMU` devices. Nothing else about
     * the loop moved -- the backoff, the Connected-then-wait branch and the
     * blanket catch are per-link already and their reasoning never depended on
     * there being two links rather than three. The `if (imuJob == null)`
     * sentence above is kept verbatim because it is still the guarantee that
     * matters and it is still true of each of the three.
     */
    private suspend fun maintain(client: GattClient, addressOf: suspend () -> String?) {
        var backoffS = 1L
        while (true) {
            try {
                val address = addressOf()
                if (address == null) {
                    // Nothing paired for this link yet; check again when the user pairs.
                    delay(3_000)
                    continue
                }
                when (client.connectionState.value) {
                    is ConnectionState.Connected -> {
                        backoffS = 1L
                        // Wait until the connection drops before doing anything else.
                        client.connectionState.first { it !is ConnectionState.Connected }
                    }
                    is ConnectionState.Connecting -> delay(2_000)
                    else -> {
                        client.connect(address, autoConnect = true)
                        delay(backoffS * 1_000)
                        backoffS = min(backoffS * 2, 30L)
                    }
                }
            } catch (e: Exception) {
                // ensureActive rethrows if the scope was cancelled, so stop()
                // still stops. Anything else falls through to the same backoff
                // the loop already uses: the role keeps retrying to the 30 s
                // cap instead of the process dying, which matters because the
                // crash tears down the very permission dialog that would fix
                // it.
                currentCoroutineContext().ensureActive()
                delay(backoffS * 1_000)
                backoffS = min(backoffS * 2, 30L)
            }
        }
    }

    private fun clientFor(role: DeviceRole): GattClient = if (role == DeviceRole.IMU) imuClient else hrmClient
}
