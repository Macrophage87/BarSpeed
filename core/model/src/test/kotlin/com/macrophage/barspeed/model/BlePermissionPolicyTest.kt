package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission decisions the BLE and recording paths make, pinned here
 * because `:core:ble` has no test source set at all and no test on the CI path
 * can construct `:app`'s Android classes. Every case below is a decision that used to be a bare `SDK_INT`
 * literal buried in an Android class no test could construct.
 */
class BlePermissionPolicyTest {
    @Test
    fun `connecting stays allowed on API 26-30 where BLUETOOTH_CONNECT does not exist`() {
        // checkSelfPermission reports the permission denied on every one of
        // these levels because it was only added in 31. Gating on it alone
        // would disable BLE outright for the whole band.
        for (sdk in 26..30) {
            assertTrue(
                BlePermissionPolicy.mayConnect(sdk, hasBluetoothConnect = false),
                "API $sdk must still be allowed to connect without BLUETOOTH_CONNECT",
            )
        }
    }

    @Test
    fun `connecting is refused from API 31 when BLUETOOTH_CONNECT is denied`() {
        assertFalse(BlePermissionPolicy.mayConnect(31, hasBluetoothConnect = false))
        assertFalse(BlePermissionPolicy.mayConnect(35, hasBluetoothConnect = false))
    }

    @Test
    fun `connecting is allowed from API 31 when BLUETOOTH_CONNECT is granted`() {
        assertTrue(BlePermissionPolicy.mayConnect(31, hasBluetoothConnect = true))
        assertTrue(BlePermissionPolicy.mayConnect(35, hasBluetoothConnect = true))
    }

    @Test
    fun `service type stays connectedDevice below 34 whatever the permission says`() {
        for (sdk in 26..33) {
            assertEquals(
                FgsTypeChoice.CONNECTED_DEVICE,
                BlePermissionPolicy.foregroundServiceType(sdk, hasBluetoothConnect = false),
                "API $sdk does not enforce the type, so the truthful type is the right one",
            )
            assertEquals(
                FgsTypeChoice.CONNECTED_DEVICE,
                BlePermissionPolicy.foregroundServiceType(sdk, hasBluetoothConnect = true),
            )
        }
    }

    @Test
    fun `service type degrades to specialUse from 34 when BLUETOOTH_CONNECT is denied`() {
        assertEquals(
            FgsTypeChoice.SPECIAL_USE,
            BlePermissionPolicy.foregroundServiceType(34, hasBluetoothConnect = false),
        )
        assertEquals(
            FgsTypeChoice.SPECIAL_USE,
            BlePermissionPolicy.foregroundServiceType(35, hasBluetoothConnect = false),
        )
    }

    @Test
    fun `service type stays connectedDevice from 34 when BLUETOOTH_CONNECT is granted`() {
        assertEquals(
            FgsTypeChoice.CONNECTED_DEVICE,
            BlePermissionPolicy.foregroundServiceType(34, hasBluetoothConnect = true),
        )
        assertEquals(
            FgsTypeChoice.CONNECTED_DEVICE,
            BlePermissionPolicy.foregroundServiceType(35, hasBluetoothConnect = true),
        )
    }

    // Permission sets. Spelled as literals rather than through the policy's own
    // constants: a pin that reads the value it is checking cannot catch a
    // renamed permission, and a renamed permission is denied forever with no
    // dialog.

    @Test
    fun `the ble permissions from API 31 are scan and connect`() {
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            BlePermissionPolicy.blePermissions(31),
        )
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            BlePermissionPolicy.blePermissions(35),
        )
    }

    @Test
    fun `the ble permission on API 26-30 is fine location`() {
        for (sdk in 26..30) {
            assertEquals(
                listOf("android.permission.ACCESS_FINE_LOCATION"),
                BlePermissionPolicy.blePermissions(sdk),
                "API $sdk has no runtime Bluetooth permission; location is what gates scanning there",
            )
        }
    }

    @Test
    fun `no SDK level asks for nothing`() {
        // An empty list makes "every one of these is held" vacuously true, so
        // the app would read GRANTED, draw no banner and never launch a
        // request -- on every device in the band. minSdk is 26, so a narrowing
        // that only hits 26-30 still ships. Emptiness is checked separately
        // from contents because it is the failure the relational pins below
        // cannot see: the empty list is a subset of anything and every one of
        // its zero names is declared in the manifest.
        for (sdk in 26..35) {
            assertTrue(
                BlePermissionPolicy.blePermissions(sdk).isNotEmpty(),
                "API $sdk would hold no BLE permission at all, making the grant check vacuously true",
            )
            assertTrue(
                BlePermissionPolicy.runtimePermissions(sdk).isNotEmpty(),
                "API $sdk would request nothing, so the launcher would return with no verdict forever",
            )
        }
    }

    @Test
    fun `the requested set adds notifications only from 33`() {
        for (sdk in 26..32) {
            assertEquals(
                BlePermissionPolicy.blePermissions(sdk),
                BlePermissionPolicy.runtimePermissions(sdk),
                "API $sdk has no POST_NOTIFICATIONS runtime permission to ask for",
            )
        }
        for (sdk in 33..35) {
            assertEquals(
                BlePermissionPolicy.blePermissions(sdk) + "android.permission.POST_NOTIFICATIONS",
                BlePermissionPolicy.runtimePermissions(sdk),
            )
        }
    }

    @Test
    fun `the decided set is a subset of the requested set`() {
        // The app asks for one set and decides on another. If a BLE permission
        // were ever left out of the request, checkSelfPermission would read it
        // denied forever and nothing would ever prompt for it.
        for (sdk in 26..35) {
            assertTrue(
                BlePermissionPolicy.runtimePermissions(sdk).containsAll(BlePermissionPolicy.blePermissions(sdk)),
                "API $sdk decides on a permission it never asks for",
            )
        }
    }

    // The step table, walked in full. Sixteen cells: granted x rationale x the
    // four PermissionAsk states. Enumerated rather than sampled because the
    // cost of a wrong cell is the dead end itself, and two of the sixteen were
    // wrong in the first design of this function.

    @Test
    fun `holding the permission wins over every other input`() {
        for (ask in PermissionAsk.entries) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    BlePermissionStep.GRANTED,
                    BlePermissionPolicy.permissionStep(granted = true, shouldShowRationale = rationale, ask = ask),
                    "granted must clear the banner from $ask with rationale=$rationale, " +
                        "or a grant made in Settings never takes effect",
                )
            }
        }
    }

    @Test
    fun `nothing is offered while a request is unasked or in flight`() {
        for (ask in listOf(PermissionAsk.NEVER_ASKED, PermissionAsk.IN_FLIGHT)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    BlePermissionStep.AWAITING_ANSWER,
                    BlePermissionPolicy.permissionStep(granted = false, shouldShowRationale = rationale, ask = ask),
                    "$ask with rationale=$rationale must not offer a button under a dialog that is already rising",
                )
            }
        }
    }

    @Test
    fun `a recorded denial offers the ask again the platform will honour`() {
        for (ask in listOf(PermissionAsk.ANSWERED, PermissionAsk.ABANDONED)) {
            assertEquals(
                BlePermissionStep.ASK_AGAIN,
                BlePermissionPolicy.permissionStep(granted = false, shouldShowRationale = true, ask = ask),
                "rationale=true is the platform saying it will show the dialog again",
            )
        }
    }

    @Test
    fun `an abandoned request offers both routes because neither can be ruled out`() {
        // shouldShowRationale is false both for a permission never denied and
        // for one denied permanently. Collapsing this into SETTINGS_ONLY sends
        // a lifter who backgrounded the phone during the first dialog to
        // Settings; collapsing it into ASK_AGAIN offers a button that does
        // nothing when the platform has stopped asking.
        assertEquals(
            BlePermissionStep.ASK_AGAIN_OR_SETTINGS,
            BlePermissionPolicy.permissionStep(
                granted = false,
                shouldShowRationale = false,
                ask = PermissionAsk.ABANDONED,
            ),
        )
    }

    @Test
    fun `only an answered denial with no rationale is a dead end`() {
        assertEquals(
            BlePermissionStep.SETTINGS_ONLY,
            BlePermissionPolicy.permissionStep(
                granted = false,
                shouldShowRationale = false,
                ask = PermissionAsk.ANSWERED,
            ),
        )
    }

    @Test
    fun `a denial blocks recording from 31 and only discovery below it`() {
        for (sdk in 26..30) {
            assertFalse(
                BlePermissionPolicy.denialBlocksRecording(sdk),
                "API $sdk still connects to a remembered sensor without the permission; " +
                    "saying otherwise would be false to the lifter",
            )
        }
        for (sdk in 31..35) {
            assertTrue(BlePermissionPolicy.denialBlocksRecording(sdk), "API $sdk cannot open the link at all")
        }
    }
}
