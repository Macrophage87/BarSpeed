package com.macrophage.barspeed.ble

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrophage.barspeed.model.DevicePairingPolicy
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

    /**
     * Saves the device, and makes it the preferred device for its role only if
     * that role has no live preferred device already.
     *
     * It used to prefer whatever was paired last, unconditionally. Preferred
     * decides which unit [AutoConnectManager] maintains the analysed link to
     * and which stream `SensorCapturePolicy.roster` reports as ARMED for
     * analysis, so
     * pairing a second bar sensor silently re-pointed the DSP -- issue #184.
     * The rule is [DevicePairingPolicy.preferredAfterPairing]'s, in
     * `:core:model` where a test can run against it, and it still prefers the
     * first device paired and still replaces a preference naming a device that
     * is no longer paired. Use [setPreferred] to move it deliberately.
     */
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
            prefs[keyFor(device.role)] =
                DevicePairingPolicy.preferredAfterPairing(
                    currentPreferred = prefs[keyFor(device.role)],
                    pairedOfRole = current.filter { it.role == device.role }.map { it.address }.toSet(),
                    justPaired = device.address,
                )
        }
    }

    /**
     * Makes an already-paired device the preferred one for its role.
     *
     * The deliberate half of the pair. Now that pairing does not move the
     * preference, this is how the lifter says which of two bar sensors the app
     * analyses -- an act with its own control and its own words, rather than a
     * side effect of pairing.
     */
    suspend fun setPreferred(address: String, role: DeviceRole) {
        context.deviceDataStore.edit { prefs -> prefs[keyFor(role)] = address }
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
            val remaining = current.filterNot { it.address == address }
            prefs[knownKey] = json.encodeToString(ListSerializer(KnownDevice.serializer()), remaining)
            // Promotes a survivor rather than clearing the preference, since
            // #184: losing it used to be self-healing because re-pairing
            // anything re-pointed it, and pairing no longer does. Left alone,
            // forgetting the analysed unit with a second one still paired
            // would idle the analysed link on a null address until the lifter
            // noticed and tapped "Use this one for analysis" on the survivor.
            // An earlier version of this comment said there was no way back
            // short of forgetting the survivor too; that was false and it is
            // deleted rather than reworded.
            //
            // The promotion moves the ADDRESS and nothing else, which is why
            // this is not the whole of the answer: a caller that does not also
            // drop the link holding the forgotten unit leaves the client
            // streaming it under the survivor's name.
            // [AutoConnectManager.forgetAndDrop] is that caller and
            // `DevicePairingPolicy.linksToDropOnForget` is the rule it uses.
            DeviceRole.entries.forEach { role ->
                val next =
                    DevicePairingPolicy.preferredAfterForget(
                        currentPreferred = prefs[keyFor(role)],
                        forgotten = address,
                        remainingOfRole = remaining.filter { it.role == role }.map { it.address },
                    )
                if (next == null) prefs.remove(keyFor(role)) else prefs[keyFor(role)] = next
            }
        }
    }

    suspend fun preferredNow(role: DeviceRole): KnownDevice? = preferred(role).first()

    private fun keyFor(role: DeviceRole) = if (role == DeviceRole.IMU) preferredImuKey else preferredHrmKey
}
