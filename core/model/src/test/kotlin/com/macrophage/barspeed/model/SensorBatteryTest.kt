package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX review 2026-11-06, ble-devices F2: the battery badge beside the sensor dots. */
class SensorBatteryTest {
    @Test
    fun `the voltage map follows a single-cell Li-ion curve, interpolated between its points`() {
        assertEquals(0, SensorBattery.percentFromCentivolts(330))
        assertEquals(0, SensorBattery.percentFromCentivolts(340))
        assertEquals(10, SensorBattery.percentFromCentivolts(360))
        assertEquals(18, SensorBattery.percentFromCentivolts(364))
        assertEquals(30, SensorBattery.percentFromCentivolts(370))
        assertEquals(50, SensorBattery.percentFromCentivolts(380))
        assertEquals(90, SensorBattery.percentFromCentivolts(410))
        assertEquals(100, SensorBattery.percentFromCentivolts(420))
        assertEquals(100, SensorBattery.percentFromCentivolts(430))
    }

    @Test
    fun `low warns from 3_64 V down, where the linear map waited for about 3_54 V`() {
        assertTrue(SensorBattery.isLow(SensorBattery.percentFromCentivolts(364)))
        assertFalse(SensorBattery.isLow(SensorBattery.percentFromCentivolts(365)))
        // The old linear 3.4-4.1 V map read 3.60 V as 28%, not low.
        assertTrue(SensorBattery.isLow(SensorBattery.percentFromCentivolts(360)))
    }

    @Test
    fun `the curve never falls as the voltage rises`() {
        val pcts = (300..440).map { SensorBattery.percentFromCentivolts(it) }
        assertEquals(pcts.sorted(), pcts)
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
