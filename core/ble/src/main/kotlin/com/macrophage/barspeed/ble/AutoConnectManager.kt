package com.macrophage.barspeed.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    val imuState: StateFlow<ConnectionState> = imuClient.connectionState
    val hrmState: StateFlow<ConnectionState> = hrmClient.connectionState
    val imuSamples: SharedFlow<com.macrophage.barspeed.model.ImuSample> = imuClient.samples
    val hrSamples: SharedFlow<com.macrophage.barspeed.model.HrSample> = hrmClient.samples

    private var imuJob: Job? = null
    private var hrmJob: Job? = null

    /** Begin maintaining connections to both preferred devices in parallel. */
    fun start() {
        if (imuJob == null) imuJob = scope.launch { maintain(DeviceRole.IMU) }
        if (hrmJob == null) hrmJob = scope.launch { maintain(DeviceRole.HRM) }
    }

    fun stop() {
        imuJob?.cancel()
        hrmJob?.cancel()
        imuJob = null
        hrmJob = null
        imuClient.disconnect()
        hrmClient.disconnect()
    }

    /** Pair (remember + prefer) a device and connect to it immediately. */
    suspend fun pairAndConnect(device: KnownDevice) {
        registry.pair(device)
        clientFor(device.role).connect(device.address)
    }

    /**
     * The reconnect loop for one role. It runs on the process-wide `appScope`,
     * a `SupervisorJob` with no `CoroutineExceptionHandler`, and `start()`
     * launches it exactly once behind `if (imuJob == null)` — so anything that
     * escapes this function reaches the default uncaught handler and kills the
     * process, and nothing ever relaunches the role.
     *
     * Hence the whole body is guarded, not just the connect call. The other
     * throw in here is [DeviceRegistry.preferredNow], whose try/catch covers
     * only the JSON decode and not the DataStore read behind it; an unreadable
     * DataStore raises something that is not a [SecurityException] from this
     * same coroutine. A catch narrowed to permission errors would have missed
     * it.
     */
    private suspend fun maintain(role: DeviceRole) {
        var backoffS = 1L
        while (true) {
            try {
                val preferred = registry.preferredNow(role)
                if (preferred == null) {
                    // Nothing paired for this role yet; check again when the user pairs.
                    delay(3_000)
                    continue
                }
                val client = clientFor(role)
                when (client.connectionState.value) {
                    is ConnectionState.Connected -> {
                        backoffS = 1L
                        // Wait until the connection drops before doing anything else.
                        client.connectionState.first { it !is ConnectionState.Connected }
                    }
                    is ConnectionState.Connecting -> delay(2_000)
                    else -> {
                        client.connect(preferred.address, autoConnect = true)
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
