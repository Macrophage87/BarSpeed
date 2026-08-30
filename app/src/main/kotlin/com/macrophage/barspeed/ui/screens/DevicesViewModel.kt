package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.ble.DiscoveredDevice
import com.macrophage.barspeed.ble.KnownDevice
import com.macrophage.barspeed.model.DevicePairingPolicy
import com.macrophage.barspeed.model.DeviceScanListPolicy
import com.macrophage.barspeed.model.DualSensorSetup
import com.macrophage.barspeed.model.DualSetupStep
import com.macrophage.barspeed.model.ScanRow
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.Sighting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which paired device each row shows the state of.
 *
 * All three ARE addresses a link is maintaining, since #184. [imuB] used to be
 * a guess -- the first paired IMU that is not the preferred one -- and the
 * screen drew that guess's connection state, so a healthy unlabelled unit
 * showed a Disconnected chip for a link that was pointed at nothing. It is now
 * `AutoConnectManager`'s own secondary address, and null means exactly what it
 * says: no link is maintaining that unit. `DevicePairingPolicy.linkRoleFor`
 * turns these three into the row's state; a row matching none of them is
 * NOT_LINKED, which is not the same as disconnected.
 */
data class LinkAddresses(
    val imu: String? = null,
    val imuB: String? = null,
    val hrm: String? = null,
)

/**
 * One row of the found-devices list: the advertisement the scanner saw, and
 * what [DeviceScanListPolicy] says the screen should make of it.
 *
 * The two are kept apart rather than flattened because they answer to
 * different things: [device] is whatever the last packet carried, and [row] is
 * a decision about the list as a whole.
 */
data class FoundDevice(val device: DiscoveredDevice, val row: ScanRow)

class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container

    val knownDevices =
        container.deviceRegistry.knownDevices.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    val imuState = container.autoConnect.imuState
    val imuStateB = container.autoConnect.imuStateB
    val hrmState = container.autoConnect.hrmState

    /**
     * Which paired device each of the three links is maintaining.
     *
     * The screen used to pick a link by ROLE, and its own comment said what
     * that costs: with two saved devices of one role every row showed the same
     * link. That was latent while nobody paired two IMUs and issue #156 makes
     * it real, so the rows are keyed by ADDRESS here instead.
     *
     * [LinkAddresses.imuB] now comes from `AutoConnectManager` rather than
     * being guessed at from what is paired (#184). Guessing produced a claim
     * the app could not support: the second link is pointed at
     * `SensorCapturePolicy.roster`'s secondary address, which is null under
     * every `DualShortfall`, so the guessed row rendered a Disconnected chip
     * for a unit no link had ever tried to reach. Reading the real value makes
     * "no link is maintaining this unit" sayable, and it is a different thing
     * to say.
     */
    val linkAddresses =
        combine(
            container.deviceRegistry.preferred(DeviceRole.IMU),
            container.autoConnect.secondaryImuAddressNow,
            container.deviceRegistry.preferred(DeviceRole.HRM),
        ) { imu, imuB, hrm ->
            LinkAddresses(imu = imu?.address, imuB = imuB, hrm = hrm?.address)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkAddresses())

    /**
     * Keep the second bar sensor's link pointed at whatever the pairing rules
     * name, for as long as this screen's ViewModel lives (#192).
     *
     * The defect this closes: `AutoConnectManager.setSecondaryImuAddress` had
     * exactly one caller, `RecordViewModel.mirrorSensorSettings`, so the
     * second link came up only as a side effect of setting a session up on
     * the Record screen. A second unit could be paired here, labelled here
     * and drawn here, and no sequence of taps on this screen brought it to
     * connected -- which made #184's own field criterion, both rows green on
     * the Devices screen, impossible rather than merely awkward. It also
     * reconciles the owner's two reports: both rows DO go green, once Record
     * has armed the link.
     *
     * WHICH address is `DevicePairingPolicy.imuLinkTargets`' answer and
     * nothing here re-decides it; that function is
     * `SensorCapturePolicy.roster`'s secondary address, so this screen and
     * the set the lifter records next cannot disagree about which physical
     * unit the second one is.
     *
     * The link's CURRENT address is combined in rather than only its inputs,
     * which is what makes this self-healing and is the half a mirror of
     * `mirrorSensorSettings` would not have. `setPreferredAndConnect` and
     * `forgetAndDrop` both call `setSecondaryImuAddress(null)` deliberately,
     * AFTER writing the preference this collector reads; without the address
     * itself as an input, a null landing after this collector's write would
     * leave the link down until some unrelated preference changed. With it,
     * the next emission re-derives the target from the state the drop left
     * behind and arms it.
     *
     * Re-arming after a deliberate drop is safe because the target is
     * recomputed from the NEW preference: `imuLinkTargets` never names the
     * analysed unit as the second one, so this cannot point both clients at
     * one WT901 -- the invariant `SensorCapture.kt:216-218` states, pinned
     * exhaustively by `no arrangement points both links at the same unit`.
     * It is also not a new race: `mirrorSensorSettings` already re-derives
     * the same address off the same preference flow whenever the Record
     * screen is open.
     *
     * An `init` block rather than a `stateIn` property, because this has no
     * reader: `WhileSubscribed` would tie arming the link to whether
     * something happened to be collecting a state, and a `val` holding the
     * Job is a property nothing reads.
     *
     * What no test here can show: that the link comes UP. `:core:ble` has no
     * test source set, nothing in this repository executes a ViewModel, and
     * what the GATT stack does with the address is a [Field] question.
     */
    init {
        viewModelScope.launch {
            combine(
                container.deviceRegistry.knownDevices,
                container.settings.sensorRoles,
                container.deviceRegistry.preferred(DeviceRole.IMU),
                container.autoConnect.secondaryImuAddressNow,
            ) { known, roles, preferred, held ->
                val target =
                    DevicePairingPolicy.imuLinkTargets(
                        pairedImuAddresses = known.filter { it.role == DeviceRole.IMU }.map { it.address },
                        preferredImuAddress = preferred?.address,
                        roleByAddress = roles,
                    ).second
                target to held
            }.collect { (target, held) ->
                if (target != held) container.autoConnect.setSecondaryImuAddress(target)
            }
        }
    }

    /** Which accelerometer the lifter has labelled which, by address. */
    val sensorRoles =
        container.settings.sensorRoles.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyMap(),
        )

    fun setSensorRole(address: String, role: SensorRole?) {
        viewModelScope.launch { container.settings.setSensorRole(address, role) }
    }

    /**
     * How far the two-accelerometer setup has got, so the screen can say what
     * to do next (#184).
     *
     * Read from what is paired and what is labelled, and NOT from the
     * preferred address: which unit is analysed is a separate question from
     * whether the pair can be told apart.
     */
    val dualSetupStep =
        combine(knownDevices, sensorRoles) { known, roles ->
            DualSensorSetup.step(known.filter { it.role == DeviceRole.IMU }.map { it.address }, roles)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DualSetupStep.NO_SENSOR)

    /**
     * Makes a paired device the one its role is read from, deliberately --
     * the analysed bar sensor, or the strap the heart rate comes from.
     *
     * The control that replaces the side effect pairing used to have. It is
     * the only thing on this screen that moves the analysed link
     * DELIBERATELY, and it says so on the button -- not the only thing that
     * moves it: forgetting the analysed unit still promotes a survivor, and
     * pairing the first bar sensor of all still sets the preference. Both are
     * reachable from this screen.
     */
    fun setPreferred(device: KnownDevice) {
        viewModelScope.launch { container.autoConnect.setPreferredAndConnect(device) }
    }

    val discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val scanError = MutableStateFlow<String?>(null)

    /**
     * The found list as the screen draws it: what was scanned, in the order
     * and with the marks [DeviceScanListPolicy.displayRows] decides.
     *
     * Combined with the saved devices rather than filtered once at scan time,
     * so pairing a device marks its row on the next emission instead of at the
     * next scan -- `knownDevices` is a flow off the registry and pairing
     * writes to it.
     */
    val foundDevices =
        combine(discovered, knownDevices) { found, known ->
            val byAddress = found.associateBy { it.address }
            val rows =
                DeviceScanListPolicy.displayRows(
                    found.map { Sighting(it.address, it.rssi) },
                    known.map { it.address }.toSet(),
                )
            rows.mapNotNull { row -> byAddress[row.address]?.let { FoundDevice(it, row) } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The last reading the RUNNING scan has for each address, for the paired
     * rows to show (#184).
     *
     * Empty when no scan is running: [toggleScan] clears [discovered] on start
     * and not on stop, so the raw flow keeps the previous scan's readings
     * indefinitely and a stopped scan would show a frozen number as a live one.
     *
     * A paired unit the running scan has no packet for is simply absent from
     * this map, so its row shows no signal line at all --
     * `DevicePairingPolicy.signalLine` answers null rather than the weakest
     * bucket, because "no reading" and "far away" are different facts.
     */
    val sightedRssi =
        combine(discovered, scanning) { found, on ->
            DeviceScanListPolicy.liveRssi(on, found.map { Sighting(it.address, it.rssi) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var scanJob: Job? = null

    fun toggleScan() {
        if (scanning.value) {
            scanJob?.cancel()
            scanning.value = false
            return
        }
        discovered.value = emptyList()
        scanError.value = null
        scanning.value = true
        scanJob =
            viewModelScope.launch {
                container.bleScanner.scan()
                    .catch { e ->
                        // A null message renders nothing through DevicesScreen's
                        // scanError?.let{}, so a scan that failed looks identical
                        // to one that simply found no devices.
                        scanError.value = e.message ?: "Scan failed (no reason reported)"
                        scanning.value = false
                    }
                    .collect { device ->
                        // The ORDER is DeviceScanListPolicy's, not this
                        // collector's: it used to re-sort by RSSI here, on
                        // every advertisement packet, and #183 is what that
                        // felt like. The full record stays keyed by address
                        // because the policy deliberately holds only what the
                        // ordering rule needs.
                        val byAddress = discovered.value.associateBy { it.address } + (device.address to device)
                        val order =
                            DeviceScanListPolicy.sighted(
                                discovered.value.map { Sighting(it.address, it.rssi) },
                                Sighting(device.address, device.rssi),
                            )
                        discovered.value = order.mapNotNull { byAddress[it.address] }
                    }
            }
    }

    fun pair(device: DiscoveredDevice, role: DeviceRole) {
        viewModelScope.launch {
            container.autoConnect.pairAndConnect(KnownDevice(device.address, device.name, role))
        }
    }

    fun forget(device: KnownDevice) {
        viewModelScope.launch { container.autoConnect.forgetAndDrop(device) }
    }

    override fun onCleared() {
        scanJob?.cancel()
        // The arming collector is deliberately not cancelled by hand --
        // viewModelScope does that -- and the link is deliberately NOT taken
        // down with it: leaving the Devices screen is not a reason to drop a
        // sensor the lifter just brought up, and the address stands until
        // something that owns it moves it.
    }
}
