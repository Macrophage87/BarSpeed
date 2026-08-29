package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The found-devices list, issue #183.
 *
 * The tests marked CHARACTERIZATION below pin what the app does TODAY, not
 * what it should do. They exist so the differentials that replace them can be
 * shown failing against the shipped rule rather than against nothing, and they
 * are deleted in the same commit that adds those differentials.
 */
class DeviceScanListPolicyTest {
    private val near = "AA:AA:AA:AA:AA:01"
    private val far = "BB:BB:BB:BB:BB:02"
    private val third = "CC:CC:CC:CC:CC:03"

    // ---- signal buckets ------------------------------------------------------

    /**
     * A new symbol, so red-before-green was not available for it and no
     * pretence is made that it was. Mutation stands in: the boundaries below
     * were moved by hand and the numbers are in the commit body.
     */
    @Test
    fun `signal falls into three buckets at their stated boundaries`() {
        assertEquals(SignalStrength.STRONG, DeviceScanListPolicy.strengthOf(-30))
        assertEquals(SignalStrength.STRONG, DeviceScanListPolicy.strengthOf(DeviceScanListPolicy.STRONG_DBM))
        assertEquals(SignalStrength.MEDIUM, DeviceScanListPolicy.strengthOf(DeviceScanListPolicy.STRONG_DBM - 1))
        assertEquals(SignalStrength.MEDIUM, DeviceScanListPolicy.strengthOf(DeviceScanListPolicy.MEDIUM_DBM))
        assertEquals(SignalStrength.WEAK, DeviceScanListPolicy.strengthOf(DeviceScanListPolicy.MEDIUM_DBM - 1))
        assertEquals(SignalStrength.WEAK, DeviceScanListPolicy.strengthOf(-120))
    }

    // ---- the scan list -------------------------------------------------------

    /**
     * CHARACTERIZATION. Today every packet re-sorts the whole list by RSSI.
     *
     * Two assertions, because one is not enough to tell the shipped rule from
     * a plain append: the first sighting of a strong device has to overtake
     * devices already listed, which only a re-sort does. Written that way
     * after a mutation removing `sortedByDescending` reddened nothing.
     */
    @Test
    fun `today a device moves the moment a packet arrives`() {
        var list = DeviceScanListPolicy.sighted(emptyList(), Sighting(far, -70))
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -55))
        assertEquals(listOf(near, far), list.map { it.address }, "the newcomer overtakes on its first packet")

        list = DeviceScanListPolicy.sighted(list, Sighting(third, -60))
        assertEquals(listOf(near, third, far), list.map { it.address })

        // Five dBm of jitter on a unit lying still on the bench, and it falls
        // two places without moving an inch.
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -75))

        assertEquals(listOf(third, far, near), list.map { it.address })
    }

    /** An address seen twice is one row, whatever the order rule is. */
    @Test
    fun `a second packet from a device already listed does not add a row`() {
        var list = DeviceScanListPolicy.sighted(emptyList(), Sighting(near, -55))
        list = DeviceScanListPolicy.sighted(list, Sighting(far, -70))
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -52))

        assertEquals(2, list.size)
        assertEquals(-52, list.first { it.address == near }.rssi, "the latest packet's own RSSI is what is held")
    }

    // ---- what the rows claim -------------------------------------------------

    /** CHARACTERIZATION. Today a saved device is offered for pairing again. */
    @Test
    fun `today a row does not know the device is already paired`() {
        val list = listOf(Sighting(near, -55), Sighting(far, -70))

        val rows = DeviceScanListPolicy.displayRows(list, setOf(near))

        assertEquals(listOf(near, far), rows.map { it.address })
        assertTrue(rows.none { it.alreadyPaired }, "today nothing removes a known address from the scan list")
    }

    @Test
    fun `every row carries the bucket for its own last reading`() {
        val rows =
            DeviceScanListPolicy.displayRows(
                listOf(Sighting(near, -40), Sighting(far, -70), Sighting(third, -95)),
                emptySet(),
            )

        assertEquals(
            listOf(SignalStrength.STRONG, SignalStrength.MEDIUM, SignalStrength.WEAK),
            rows.map { it.strength },
        )
        assertEquals(listOf(-40, -70, -95), rows.map { it.rssi })
    }
}
