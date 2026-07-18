package com.macrophage.barspeed.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private suspend fun maintain(role: DeviceRole) {
        var backoffS = 1L
        while (true) {
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
        }
    }

    private fun clientFor(role: DeviceRole): GattClient = if (role == DeviceRole.IMU) imuClient else hrmClient
}
