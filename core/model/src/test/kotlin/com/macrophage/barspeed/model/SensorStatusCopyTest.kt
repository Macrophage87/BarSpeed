package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX review 2026-11-06, ble-devices F5 and F7: Devices speaks in lifter words. */
class SensorStatusCopyTest {
    @Test
    fun `a service discovery failure reads as a power-cycle and a Retry, keeping the code`() {
        val copy = SensorStatusCopy.failed(ConnectionState.Failed("Service discovery failed (133)"), isStrap = false)
        assertEquals("Couldn't connect. Turn the sensor off and on, then tap Retry.", copy.line)
        assertEquals("Service discovery failed (133)", copy.detail)
        assertTrue(copy.offerRetry)
        assertFalse(copy.offerTurnOnBluetooth)
    }

    @Test
    fun `no link opened reads the same as any other connect failure`() {
        val copy = SensorStatusCopy.failed(ConnectionState.Failed(ConnectionFailureReason.NO_LINK_OPENED), false)
        assertEquals("Couldn't connect. Turn the sensor off and on, then tap Retry.", copy.line)
        assertEquals(ConnectionFailureReason.NO_LINK_OPENED, copy.detail)
    }

    @Test
    fun `a strap is not told to power-cycle`() {
        val copy = SensorStatusCopy.failed(ConnectionState.Failed("Service discovery failed (133)"), isStrap = true)
        assertEquals("Couldn't connect. Check the heart-rate strap is on and close by, then tap Retry.", copy.line)
        assertTrue(copy.offerRetry)
    }

    @Test
    fun `phone Bluetooth off offers to turn it on, not a Retry, whatever the reason string`() {
        // The boolean decides, not the string: SensorAdvicePolicy's rule.
        val copy = SensorStatusCopy.failed(ConnectionState.Failed("anything", phoneBluetoothOff = true), false)
        assertEquals("Your phone's Bluetooth is off.", copy.line)
        assertTrue(copy.offerTurnOnBluetooth)
        assertFalse(copy.offerRetry)
        assertNull(copy.detail)
    }

    @Test
    fun `a unit that answered with the wrong profile is not told to power-cycle`() {
        val copy =
            SensorStatusCopy.failed(
                ConnectionState.Failed("Expected service/characteristic not found", linkEstablished = true),
                isStrap = false,
            )
        assertEquals(
            "It answered, but it isn't a bar sensor BarSpeed can read. Check you paired the right device.",
            copy.line,
        )
        assertFalse(copy.offerRetry)
        assertEquals("Expected service/characteristic not found", copy.detail)
    }

    @Test
    fun `a missing permission points at the banner`() {
        val copy = SensorStatusCopy.failed(ConnectionState.Failed(ConnectionFailureReason.PERMISSION_DENIED), false)
        assertEquals("BarSpeed isn't allowed to use Bluetooth. Grant Nearby devices above.", copy.line)
        assertFalse(copy.offerRetry)
    }

    @Test
    fun `connecting says nothing for ten seconds, then that the unit is not found`() {
        assertNull(SensorStatusCopy.connecting("Sensor A", 9_999))
        assertEquals(
            "Sensor A: not found nearby. Is it on and close by?",
            SensorStatusCopy.connecting("Sensor A", SensorStatusCopy.NOT_FOUND_AFTER_MS),
        )
    }

    @Test
    fun `device names use the label, and copy F4's nouns, not the role enum`() {
        assertEquals("Sensor A", SensorStatusCopy.deviceName(isStrap = false, role = SensorRole.A))
        assertEquals("Sensor B", SensorStatusCopy.deviceName(isStrap = false, role = SensorRole.B))
        assertEquals("Bar sensor", SensorStatusCopy.deviceName(isStrap = false, role = null))
        assertEquals("Heart-rate strap", SensorStatusCopy.deviceName(isStrap = true, role = SensorRole.A))
    }

    @Test
    fun `a scan refused for Bluetooth off offers the button, anything else keeps its raw text`() {
        val off = SensorStatusCopy.scanFailed(ConnectionFailureReason.PHONE_BLUETOOTH_OFF)
        assertEquals("Your phone's Bluetooth is off.", off.line)
        assertTrue(off.offerTurnOnBluetooth)
        val other = SensorStatusCopy.scanFailed("Scan failed (code 2)")
        assertEquals("Couldn't scan. Turn Bluetooth off and on, then scan again.", other.line)
        assertEquals("Scan failed (code 2)", other.detail)
        assertFalse(other.offerTurnOnBluetooth)
    }
}
