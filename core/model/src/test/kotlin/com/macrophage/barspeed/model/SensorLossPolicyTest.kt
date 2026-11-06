package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A bar sensor going quiet mid-set, said once and drawn (BLE F1). */
class SensorLossPolicyTest {
    private val delivering = listOf(ArmedDelivery.DELIVERING)
    private val quiet = listOf(ArmedDelivery.LINKED_SILENT)

    private fun walk(vararg ticks: List<ArmedDelivery>, sensorCounted: Boolean = true): List<String?> {
        var watch = SensorLossWatch()
        return ticks.map { deliveries ->
            val step = SensorLossPolicy.step(watch, sensorCounted, deliveries)
            watch = step.watch
            step.cue
        }
    }

    @Test
    fun `the only sensor going quiet is said once and its return once`() {
        assertEquals(
            listOf(null, "Sensor lost", null, null, "Sensor back", null),
            walk(delivering, quiet, quiet, quiet, delivering, delivering),
        )
    }

    /** A link that flaps says each word once in the set, not once per flap. */
    @Test
    fun `a flapping link says each word once per set`() {
        assertEquals(
            listOf("Sensor lost", "Sensor back", null, null, null),
            walk(quiet, delivering, quiet, delivering, quiet),
        )
    }

    @Test
    fun `the banner stands exactly while the sensor is quiet`() {
        var watch = SensorLossWatch()
        val shown = listOf(delivering, quiet, delivering, quiet).map {
            watch = SensorLossPolicy.step(watch, true, it).watch
            watch.lost
        }
        assertEquals(listOf(false, true, false, true), shown)
    }

    /** With two sensors the feed moves to the one still delivering, so the count goes on. */
    @Test
    fun `one of two sensors going quiet is not a lost count`() {
        assertFalse(SensorLossPolicy.allQuiet(listOf(ArmedDelivery.NOT_LINKED, ArmedDelivery.DELIVERING)))
        assertTrue(SensorLossPolicy.allQuiet(listOf(ArmedDelivery.NOT_LINKED, ArmedDelivery.LINKED_SILENT)))
        assertTrue(SensorLossPolicy.allQuiet(listOf(ArmedDelivery.LINK_WITHOUT_SENSOR)))
    }

    /** The app does not know yet two seconds into a connect, and must not accuse the unit. */
    @Test
    fun `too soon to say is not lost`() {
        assertFalse(SensorLossPolicy.allQuiet(listOf(ArmedDelivery.TOO_SOON)))
        assertFalse(SensorLossPolicy.allQuiet(emptyList()))
    }

    /** A tap-counted or guided set counts without the sensor, so nothing is said. */
    @Test
    fun `a set the sensor does not count is not watched`() {
        assertEquals(listOf(null, null), walk(quiet, delivering, sensorCounted = false))
    }

    @Test
    fun `quiet is the armed-silence window on the last frame, and only armed links are read`() {
        val links = ArmedLinks(
            analysedState = ConnectionState.Connected("WT901"),
            analysedFrameAtMs = 10_000L,
            analysedArmedAtMs = 0L,
            secondaryState = ConnectionState.Disconnected,
            secondaryFrameAtMs = null,
            secondaryArmedAtMs = 0L,
        )
        val within = 10_000L + ArmedSilencePolicy.SILENT_AFTER_MS
        assertEquals(delivering, SensorLossPolicy.deliveries(links, secondaryArmed = false, nowMs = within))
        assertEquals(quiet, SensorLossPolicy.deliveries(links, secondaryArmed = false, nowMs = within + 1))
        assertEquals(
            listOf(ArmedDelivery.DELIVERING, ArmedDelivery.NOT_LINKED),
            SensorLossPolicy.deliveries(links, secondaryArmed = true, nowMs = within),
        )
    }

    @Test
    fun `the words are not rep calls`() {
        assertNull(SpokenRepCall.spansOf(SensorLossPolicy.LOST_CUE).firstOrNull())
        assertNull(SpokenRepCall.spansOf(SensorLossPolicy.BACK_CUE).firstOrNull())
    }
}
