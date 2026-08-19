@file:Suppress("DEPRECATION", "MissingPermission")

package com.macrophage.barspeed.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.macrophage.barspeed.hrm.HeartRateMeasurementParser
import com.macrophage.barspeed.hrm.HeartRateProfile
import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.witmotion.WitmotionCommands
import com.macrophage.barspeed.witmotion.WitmotionFrame
import com.macrophage.barspeed.witmotion.WitmotionProtocol
import com.macrophage.barspeed.witmotion.WitmotionStreamDecoder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Base GATT client: connect (with OS-level auto-connect), service discovery,
 * MTU negotiation, notification subscription. Subclasses provide the UUIDs and
 * handle notification payloads. All callbacks are marshaled by the Android BLE
 * stack; state is exposed as flows.
 */
@SuppressLint("MissingPermission")
abstract class GattClient(protected val context: Context) {
    protected var gatt: BluetoothGatt? = null

    private val stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = stateFlow

    protected abstract val serviceUuid: UUID
    protected abstract val notifyCharacteristicUuid: UUID

    protected abstract fun onNotification(uuid: UUID, value: ByteArray)

    protected open fun onReady(gatt: BluetoothGatt) {}

    private var deviceName: String = "sensor"

    /**
     * Open a GATT link to [address], reporting refusal as state rather than as
     * a throw.
     *
     * The gate runs before `device.name` and `connectGatt`, both of which are
     * `@RequiresPermission(BLUETOOTH_CONNECT)` from API 31 — checked in
     * `platforms/android-35/data/annotations.zip`, where `getName` and four
     * `connectGatt` overloads are annotated and `getRemoteDevice` is not. It
     * sets [ConnectionState.Failed] first and never reaches
     * [ConnectionState.Connecting], so [AutoConnectManager]'s backoff branch
     * takes over instead of its two-second Connecting poll, which has no exit.
     *
     * Every path out of this function that is not a live link leaves a
     * terminal state behind. That includes `connectGatt` returning null, which
     * previously latched Connecting forever with the adapter simply switched
     * off: pre-existing, in these same ten lines, and the same lying-chip
     * failure the gate exists to fix. It also includes the two early returns
     * below, which review found still returning bare `false`: with the state
     * flow left untouched at whatever it held, the sentence above was false
     * for them, and a chip could still sit on a stale `Connecting`.
     */
    fun connect(address: String, autoConnect: Boolean = true): Boolean {
        if (!BlePermissionPolicy.mayConnect(Build.VERSION.SDK_INT, hasBluetoothConnect())) {
            stateFlow.value = ConnectionState.Failed(PERMISSION_DENIED_REASON)
            return false
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            stateFlow.value = ConnectionState.Failed("Bluetooth unavailable")
            return false
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            stateFlow.value = ConnectionState.Failed("Bad device address")
            return false
        }
        return try {
            val device = adapter.getRemoteDevice(address)
            deviceName = device.name ?: address
            stateFlow.value = ConnectionState.Connecting
            gatt?.close()
            val opened =
                device.connectGatt(context, autoConnect, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            gatt = opened
            if (opened == null) {
                stateFlow.value = ConnectionState.Failed("Bluetooth is off or the link was refused")
            }
            opened != null
        } catch (e: SecurityException) {
            // The permission can be revoked between the gate above and the
            // call below; the framework then throws rather than returning
            // null, and this coroutine has no handler above it.
            stateFlow.value = ConnectionState.Failed(PERMISSION_DENIED_REASON)
            false
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            // Both calls are BLUETOOTH_CONNECT-gated too, and tearing a link
            // down must not be able to kill the process. They share one try,
            // so a throwing disconnect() skips close() and the assignment
            // below then drops the only BluetoothGatt reference: the local
            // handle goes either way, but the native GATT client registration
            // is not released on this path. Splitting the try would trade that
            // leak for a second catch; neither is reachable today, since
            // disconnect()'s only caller is AutoConnectManager.stop(), which
            // has no callers.
        }
        gatt = null
        stateFlow.value = ConnectionState.Disconnected
    }

    private fun hasBluetoothConnect(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Issue #20's own wording; it is what the Devices screen renders. */
        const val PERMISSION_DENIED_REASON = "Bluetooth permission not granted"
    }

    protected fun updateBattery(pct: Int) {
        val current = stateFlow.value
        if (current is ConnectionState.Connected) {
            stateFlow.value = current.copy(batteryPct = pct)
        }
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            g.requestMtu(247)
                        } catch (e: SecurityException) {
                            // Same race as connect(): the permission can be revoked
                            // between the stack delivering this callback and this
                            // call. Nothing downstream has run yet -- no
                            // notification is subscribed -- so there is no live
                            // data path to protect by staying on Connecting.
                            stateFlow.value = ConnectionState.Failed(PERMISSION_DENIED_REASON)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        stateFlow.value = ConnectionState.Disconnected
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                try {
                    g.discoverServices()
                } catch (e: SecurityException) {
                    // Same reasoning as onConnectionStateChange above: no
                    // notification is subscribed yet, so Failed costs nothing
                    // beyond what a stuck Connecting would already have cost.
                    stateFlow.value = ConnectionState.Failed(PERMISSION_DENIED_REASON)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stateFlow.value = ConnectionState.Failed("Service discovery failed ($status)")
                    return
                }
                val characteristic = findNotifyCharacteristic(g)
                if (characteristic == null) {
                    stateFlow.value = ConnectionState.Failed("Expected service/characteristic not found")
                    return
                }
                try {
                    g.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(descriptor)
                        }
                    }
                } catch (e: SecurityException) {
                    // Same race as connect(), one callback later. These three
                    // calls are the last step before Connected; none of them
                    // has run, so no notification is subscribed and there is
                    // nothing live to protect by staying here instead of
                    // reporting Failed.
                    stateFlow.value = ConnectionState.Failed(PERMISSION_DENIED_REASON)
                    return
                }
                stateFlow.value = ConnectionState.Connected(deviceName)
                onReady(g)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) {
                    val value = characteristic.value ?: return
                    onNotification(characteristic.uuid, value)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onNotification(characteristic.uuid, value)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) onNotification(characteristic.uuid, value)
            }
        }

    /**
     * Find the notify characteristic by UUID; if the vendor changed UUIDs, fall
     * back to the first notifiable characteristic in any custom service (spec 2.1).
     */
    private fun findNotifyCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        g.getService(serviceUuid)?.getCharacteristic(notifyCharacteristicUuid)?.let { return it }
        return g.services
            .filterNot { it.uuid.toString().startsWith("0000180") }
            .flatMap { it.characteristics }
            .firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
    }
}

/** WitMotion WT901BLECL client: decodes measurement frames into [ImuSample]s. */
@SuppressLint("MissingPermission")
class WitmotionClient(context: Context, private val clock: () -> Long = System::currentTimeMillis) :
    GattClient(context) {
    override val serviceUuid: UUID = UUID.fromString(WitmotionProtocol.SERVICE_UUID)
    override val notifyCharacteristicUuid: UUID = UUID.fromString(WitmotionProtocol.NOTIFY_CHARACTERISTIC_UUID)

    private val decoder = WitmotionStreamDecoder()

    private val samplesFlow = MutableSharedFlow<ImuSample>(extraBufferCapacity = 512)
    val samples: SharedFlow<ImuSample> = samplesFlow

    private val commandHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onReady(gatt: BluetoothGatt) {
        // The GATT stack allows one operation at a time (the CCC descriptor write
        // is still in flight here), and WitMotion registers ignore writes until
        // unlocked. Sequence: unlock → 100 Hz rate → save → battery read, spaced
        // out so none of them gets dropped. The DSP still measures the actual
        // delivered rate as a safety net.
        val commands =
            listOf(
                WitmotionCommands.unlock(),
                WitmotionCommands.setOutputRate(WitmotionProtocol.OutputRate.RATE_100_HZ),
                WitmotionCommands.save(),
                WitmotionCommands.readRegister(WitmotionProtocol.REG_BATTERY),
            )
        commands.forEachIndexed { i, cmd ->
            commandHandler.postDelayed({ writeCommand(gatt, cmd) }, COMMAND_SPACING_MS * (i + 1))
        }
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        for (frame in decoder.feed(value, clock())) {
            when (frame) {
                is WitmotionFrame.Measurement -> samplesFlow.tryEmit(frame.sample)
                is WitmotionFrame.RegisterData ->
                    if (frame.startRegister == WitmotionProtocol.REG_BATTERY && frame.values.isNotEmpty()) {
                        updateBattery(estimateBatteryPct(frame.values[0].toInt()))
                    }
            }
        }
    }

    private fun writeCommand(gatt: BluetoothGatt, command: ByteArray) {
        val characteristic =
            gatt.getService(serviceUuid)
                ?.getCharacteristic(UUID.fromString(WitmotionProtocol.WRITE_CHARACTERISTIC_UUID)) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    characteristic,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = command
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            // Connected already; the notify characteristic is already
            // subscribed and delivering samples, so demoting the state here
            // would be the opposite lie -- Failed while data keeps arriving.
            // A lost configuration command leaves the sensor on whatever rate
            // it already had; the DSP measures the actual delivered rate
            // rather than trusting this write (see onReady above), so this
            // degrades the capture rather than corrupting it.
        }
    }

    /** WT901BLECL reports battery voltage in centivolts; map to a rough percent. */
    private fun estimateBatteryPct(centivolts: Int): Int {
        val volts = centivolts / 100.0
        return (((volts - 3.4) / (4.1 - 3.4)) * 100).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val COMMAND_SPACING_MS = 300L
    }
}

/** Standard BLE heart-rate client (Garmin HRM 600 and any compliant strap). */
@SuppressLint("MissingPermission")
class HrmClient(context: Context, private val clock: () -> Long = System::currentTimeMillis) :
    GattClient(context) {
    override val serviceUuid: UUID = UUID.fromString(HeartRateProfile.SERVICE_UUID)
    override val notifyCharacteristicUuid: UUID =
        UUID.fromString(HeartRateProfile.MEASUREMENT_CHARACTERISTIC_UUID)

    private val samplesFlow = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
    val samples: SharedFlow<HrSample> = samplesFlow

    override fun onReady(gatt: BluetoothGatt) {
        try {
            gatt.getService(UUID.fromString(HeartRateProfile.BATTERY_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(HeartRateProfile.BATTERY_LEVEL_CHARACTERISTIC_UUID))
                ?.let { gatt.readCharacteristic(it) }
        } catch (e: SecurityException) {
            // Connected is already set and the measurement characteristic is
            // already subscribed; only this battery read is lost. batteryPct
            // stays null, its already-honest default for "unknown" -- this
            // must not demote a client still receiving heart-rate samples to
            // Failed.
        }
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        when (uuid.toString()) {
            HeartRateProfile.MEASUREMENT_CHARACTERISTIC_UUID ->
                HeartRateMeasurementParser.parse(value, clock())?.let { samplesFlow.tryEmit(it) }
            HeartRateProfile.BATTERY_LEVEL_CHARACTERISTIC_UUID ->
                if (value.isNotEmpty()) updateBattery(value[0].toInt())
        }
    }
}
