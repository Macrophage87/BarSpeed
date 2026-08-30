package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The found-devices list, issue #183.
 *
 * Two of these are differentials: they fail against the rule the app ships
 * today and pass only once it changes. The characterizations they replaced --
 * "today a device moves the moment a packet arrives" and "today a row does not
 * know the device is already paired" -- are gone, having done their job of
 * showing that the shipped rule is what these now contradict.
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
     * The property #183 exists for: a device must not move because a packet
     * arrived.
     *
     * `SCAN_MODE_LOW_LATENCY` delivers a result for every advertisement
     * packet, several a second per device, each carrying that packet's own
     * RSSI -- which jitters a few dBm on a unit lying still on the bench. Any
     * rule that reads the signal to decide the ORDER therefore reorders the
     * list many times a second. First-seen order is the only one that cannot,
     * and the signal is shown as a value on the row instead.
     *
     * Three assertions, because fewer cannot tell this rule from the two
     * plausible near-misses: a strong newcomer must not overtake (that is the
     * re-sort), and a re-sighted device must not fall to the end (that is a
     * plain remove-and-append).
     */
    @Test
    fun `a device does not move because a packet arrived`() {
        var list = DeviceScanListPolicy.sighted(emptyList(), Sighting(far, -70))
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -55))
        assertEquals(listOf(far, near), list.map { it.address }, "a strong newcomer joins at the end")

        list = DeviceScanListPolicy.sighted(list, Sighting(third, -60))
        assertEquals(listOf(far, near, third), list.map { it.address })

        list = DeviceScanListPolicy.sighted(list, Sighting(near, -95))

        assertEquals(listOf(far, near, third), list.map { it.address }, "a re-sighted device holds its place")
        assertEquals(-95, list.first { it.address == near }.rssi, "and still reports its latest reading")
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

    /**
     * A saved device is shown, marked, and never offered for pairing again.
     *
     * Shown rather than hidden, and that is the decision #183 left open.
     * Hiding it risks the lifter reading a missing row as "the scan did not
     * find my sensor", which is the one thing this list is for; and while a
     * second unit is being paired, a row proving the FIRST unit is powered and
     * in range is exactly the diagnostic that is wanted. What must not survive
     * is the offer to pair it again: the second offer can only change the
     * device's ROLE, and re-filing a saved bar sensor as an HRM leaves
     * `preferred_imu` naming an address `DeviceRegistry.preferred(IMU)` no
     * longer matches, so the analysed link idles on nothing (#184).
     *
     * An earlier draft said the reason was that pairing moves the analysed
     * link. That stopped being true in the same branch, when
     * `DeviceRegistry.pair` began keeping the existing preference; the
     * sentence is deleted rather than reworded.
     */
    @Test
    fun `a device already paired is shown, marked, and not offered again`() {
        val list = listOf(Sighting(near, -55), Sighting(far, -70))

        val rows = DeviceScanListPolicy.displayRows(list, setOf(near))

        assertEquals(setOf(near, far), rows.map { it.address }.toSet(), "the paired unit is still on screen")
        assertTrue(rows.single { it.address == near }.alreadyPaired)
        assertTrue(!rows.single { it.address == far }.alreadyPaired)
    }

    /**
     * The marked rows sink below the pairable ones, and that is the ONE way a
     * row is allowed to move: in answer to the lifter's own tap, never to a
     * packet. `knownAddresses` changes only when something is paired or
     * forgotten, so pairing a device drops it out of the offers immediately
     * rather than at the next scan.
     */
    @Test
    fun `pairing a device moves it below the ones still on offer, and nothing else moves`() {
        val list = listOf(Sighting(near, -55), Sighting(far, -70), Sighting(third, -90))

        val before = DeviceScanListPolicy.displayRows(list, emptySet())
        val after = DeviceScanListPolicy.displayRows(list, setOf(near))

        assertEquals(listOf(near, far, third), before.map { it.address })
        assertEquals(listOf(far, third, near), after.map { it.address })
    }

    /** Paired rows keep first-seen order among themselves too. */
    @Test
    fun `the already-paired rows are ordered as they were first seen`() {
        val list = listOf(Sighting(near, -55), Sighting(far, -70), Sighting(third, -90))

        val rows = DeviceScanListPolicy.displayRows(list, setOf(near, third))

        assertEquals(listOf(far, near, third), rows.map { it.address })
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

    // ---- what a paired row may show about signal -----------------------------

    /**
     * A characterization of today's map: a running scan's last reading per
     * address, and nothing for an address it has no packet for.
     *
     * Absence is the load-bearing half. `DevicePairingPolicy.signalLine`
     * answers null for a missing reading rather than the weakest bucket, and
     * it can only do that if the map leaves the key out.
     */
    @Test
    fun `a running scan reports the last reading for each sighted address and nothing else`() {
        val readings = DeviceScanListPolicy.liveRssi(true, listOf(Sighting(near, -42), Sighting(far, -88)))

        assertEquals(mapOf(near to -42, far to -88), readings)
        assertTrue(third !in readings, "an address no packet arrived for is absent, not weak")
    }

    /**
     * DIFFERENTIAL, finding 2: a stopped scan has no readings, not the last
     * scan's.
     *
     * `DevicesViewModel.toggleScan` clears the found list when a scan STARTS
     * and never when one stops or errors, and nothing on the Devices screen is
     * conditioned on the scan running -- so a paired row went on printing
     * "Signal strong (-42 dBm)" indefinitely, indistinguishable from a live
     * reading. That is load-bearing rather than cosmetic: the live signal is
     * what replaced the by-elimination ritual, `DualSensorSetup.identifyHint`
     * tells the lifter to label the unit whose row reads strong, and the label
     * decides which physical stream the export files under which name.
     */
    @Test
    fun `a stopped scan reports no readings at all`() {
        assertEquals(
            emptyMap(),
            DeviceScanListPolicy.liveRssi(false, listOf(Sighting(near, -42), Sighting(far, -88))),
        )
    }
}
