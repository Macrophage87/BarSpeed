package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission decisions the BLE and recording paths make, pinned here
 * because the two modules that consume them have no test source set of their
 * own. Every case below is a decision that used to be a bare `SDK_INT`
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
}
