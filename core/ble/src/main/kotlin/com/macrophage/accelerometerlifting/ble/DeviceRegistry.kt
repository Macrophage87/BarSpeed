package com.macrophage.accelerometerlifting.ble

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.deviceDataStore by preferencesDataStore(name = "devices")

/**
 * Persistent registry of paired sensors. Pairing is a one-time act (spec 4.1):
 * multiple saved devices are allowed, with one preferred device per role.
 */
class DeviceRegistry(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val knownKey = stringPreferencesKey("known_devices")
    private val preferredImuKey = stringPreferencesKey("preferred_imu")
    private val preferredHrmKey = stringPreferencesKey("preferred_hrm")

    val knownDevices: Flow<List<KnownDevice>> =
        context.deviceDataStore.data.map { prefs ->
            prefs[knownKey]?.let {
                try {
                    json.decodeFromString(ListSerializer(KnownDevice.serializer()), it)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }

    fun preferred(role: DeviceRole): Flow<KnownDevice?> = context.deviceDataStore.data.map { prefs ->
        val address = prefs[keyFor(role)] ?: return@map null
        prefs[knownKey]?.let {
            try {
                json.decodeFromString(ListSerializer(KnownDevice.serializer()), it)
                    .firstOrNull { d -> d.address == address && d.role == role }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Saves the device and makes it the preferred device for its role. */
    suspend fun pair(device: KnownDevice) {
        context.deviceDataStore.edit { prefs ->
            val current =
                prefs[knownKey]?.let {
                    try {
                        json.decodeFromString(ListSerializer(KnownDevice.serializer()), it)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
            val updated = current.filterNot { it.address == device.address } + device
            prefs[knownKey] = json.encodeToString(ListSerializer(KnownDevice.serializer()), updated)
            prefs[keyFor(device.role)] = device.address
        }
    }

    suspend fun forget(address: String) {
        context.deviceDataStore.edit { prefs ->
            val current =
                prefs[knownKey]?.let {
                    try {
                        json.decodeFromString(ListSerializer(KnownDevice.serializer()), it)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
            prefs[knownKey] =
                json.encodeToString(
                    ListSerializer(KnownDevice.serializer()),
                    current.filterNot { it.address == address },
                )
            DeviceRole.entries.forEach { role ->
                if (prefs[keyFor(role)] == address) prefs.remove(keyFor(role))
            }
        }
    }

    suspend fun preferredNow(role: DeviceRole): KnownDevice? = preferred(role).first()

    private fun keyFor(role: DeviceRole) = if (role == DeviceRole.IMU) preferredImuKey else preferredHrmKey
}
