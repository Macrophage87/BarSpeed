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
import android.os.Build
import com.macrophage.barspeed.hrm.HeartRateMeasurementParser
import com.macrophage.barspeed.hrm.HeartRateProfile
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

    fun connect(address: String, autoConnect: Boolean = true): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        val device = adapter.getRemoteDevice(address)
        deviceName = device.name ?: address
        stateFlow.value = ConnectionState.Connecting
        gatt?.close()
        gatt = device.connectGatt(context, autoConnect, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        stateFlow.value = ConnectionState.Disconnected
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
                        g.requestMtu(247)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        stateFlow.value = ConnectionState.Disconnected
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
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

    override fun onReady(gatt: BluetoothGatt) {
        // Request 100 Hz streaming; the DSP measures the actual delivered rate.
        writeCommand(gatt, WitmotionCommands.setOutputRate(WitmotionProtocol.OutputRate.RATE_100_HZ))
        writeCommand(gatt, WitmotionCommands.readRegister(WitmotionProtocol.REG_BATTERY))
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
    }

    /** WT901BLECL reports battery voltage in centivolts; map to a rough percent. */
    private fun estimateBatteryPct(centivolts: Int): Int {
        val volts = centivolts / 100.0
        return (((volts - 3.4) / (4.1 - 3.4)) * 100).toInt().coerceIn(0, 100)
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
        gatt.getService(UUID.fromString(HeartRateProfile.BATTERY_SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(HeartRateProfile.BATTERY_LEVEL_CHARACTERISTIC_UUID))
            ?.let { gatt.readCharacteristic(it) }
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
