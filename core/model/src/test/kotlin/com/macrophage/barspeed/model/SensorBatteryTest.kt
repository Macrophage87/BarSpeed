package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX review 2026-11-06, ble-devices F2: the battery badge beside the sensor dots. */
class SensorBatteryTest {
    @Test
    fun `the voltage map is the linear one WitmotionClient always used`() {
        assertEquals(0, SensorBattery.percentFromCentivolts(330))
        assertEquals(0, SensorBattery.percentFromCentivolts(340))
        assertEquals(42, SensorBattery.percentFromCentivolts(370))
        assertEquals(100, SensorBattery.percentFromCentivolts(410))
        assertEquals(100, SensorBattery.percentFromCentivolts(420))
    }

    @Test
    fun `low is strictly under twenty, and unknown is never low`() {
        assertTrue(SensorBattery.isLow(19))
        assertTrue(SensorBattery.isLow(0))
        assertFalse(SensorBattery.isLow(20))
        assertFalse(SensorBattery.isLow(null))
    }

    @Test
    fun `the badge says low under twenty and nothing without a reading`() {
        assertEquals("64%", SensorBattery.badge(64))
        assertEquals("20%", SensorBattery.badge(20))
        assertEquals("15% low", SensorBattery.badge(15))
        assertNull(SensorBattery.badge(null))
    }

    @Test
    fun `only a connected link has a reading to show`() {
        assertEquals("15% low", SensorBattery.badgeOf(ConnectionState.Connected("WT901", batteryPct = 15)))
        assertTrue(SensorBattery.isLowOf(ConnectionState.Connected("WT901", batteryPct = 15)))
        assertNull(SensorBattery.badgeOf(ConnectionState.Connected("WT901")))
        assertNull(SensorBattery.badgeOf(ConnectionState.Connecting))
        assertNull(SensorBattery.badgeOf(ConnectionState.Failed("x")))
        assertFalse(SensorBattery.isLowOf(ConnectionState.Disconnected))
    }
}
