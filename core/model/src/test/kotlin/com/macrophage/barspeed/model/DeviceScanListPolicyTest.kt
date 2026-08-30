package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The found-devices list, issues #183 and #197.
 *
 * Several of these are differentials: they fail against the rule the app
 * ships today and pass only once it changes. The characterizations they
 * replaced -- "today a device moves the moment a packet arrives", "today a
 * row does not know the device is already paired", and "today the list does
 * not order on `likelyRole` at all" -- are gone, having done their job of
 * showing that the shipped rule is what these now contradict.
 */
class DeviceScanListPolicyTest {
    private val near = "AA:AA:AA:AA:AA:01"
    private val far = "BB:BB:BB:BB:BB:02"
    private val third = "CC:CC:CC:CC:CC:03"
    private val fourth = "DD:DD:DD:DD:DD:04"

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
        var list = DeviceScanListPolicy.sighted(emptyList(), Sighting(far, -70, classified = false))
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -55, classified = false))
        assertEquals(listOf(far, near), list.map { it.address }, "a strong newcomer joins at the end")

        list = DeviceScanListPolicy.sighted(list, Sighting(third, -60, classified = false))
        assertEquals(listOf(far, near, third), list.map { it.address })

        list = DeviceScanListPolicy.sighted(list, Sighting(near, -95, classified = false))

        assertEquals(listOf(far, near, third), list.map { it.address }, "a re-sighted device holds its place")
        assertEquals(-95, list.first { it.address == near }.rssi, "and still reports its latest reading")
    }

    /** An address seen twice is one row, whatever the order rule is. */
    @Test
    fun `a second packet from a device already listed does not add a row`() {
        var list = DeviceScanListPolicy.sighted(emptyList(), Sighting(near, -55, classified = false))
        list = DeviceScanListPolicy.sighted(list, Sighting(far, -70, classified = false))
        list = DeviceScanListPolicy.sighted(list, Sighting(near, -52, classified = false))

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
        val list = listOf(Sighting(near, -55, classified = false), Sighting(far, -70, classified = false))

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
        val list =
            listOf(
                Sighting(near, -55, classified = false),
                Sighting(far, -70, classified = false),
                Sighting(third, -90, classified = false),
            )

        val before = DeviceScanListPolicy.displayRows(list, emptySet())
        val after = DeviceScanListPolicy.displayRows(list, setOf(near))

        assertEquals(listOf(near, far, third), before.map { it.address })
        assertEquals(listOf(far, third, near), after.map { it.address })
    }

    /** Paired rows keep first-seen order among themselves too. */
    @Test
    fun `the already-paired rows are ordered as they were first seen`() {
        val list =
            listOf(
                Sighting(near, -55, classified = false),
                Sighting(far, -70, classified = false),
                Sighting(third, -90, classified = false),
            )

        val rows = DeviceScanListPolicy.displayRows(list, setOf(near, third))

        assertEquals(listOf(far, near, third), rows.map { it.address })
    }

    @Test
    fun `every row carries the bucket for its own last reading`() {
        val rows =
            DeviceScanListPolicy.displayRows(
                listOf(
                    Sighting(near, -40, classified = false),
                    Sighting(far, -70, classified = false),
                    Sighting(third, -95, classified = false),
                ),
                emptySet(),
            )

        assertEquals(
            listOf(SignalStrength.STRONG, SignalStrength.MEDIUM, SignalStrength.WEAK),
            rows.map { it.strength },
        )
        assertEquals(listOf(-40, -70, -95), rows.map { it.rssi })
    }

    // ---- likely-role ordering, issue #197 ------------------------------------

    /**
     * DIFFERENTIAL: the owner's own ask -- "make the sensors that look like
     * HRMs and IMUs appear first" -- did not hold before this change.
     * `guessRole` already names a device's role from its advertised service
     * UUIDs or its name; this is the first test that puts that guess to work
     * for ORDER rather than only for the "looks like $role" caption
     * `DevicesScreen` already draws.
     *
     * First-seen order holds inside each half, same as the paired split
     * below: [near] and [third] are both classified but `near` was sighted
     * first, so it leads.
     */
    @Test
    fun `sensor-shaped rows come before ones the scan cannot classify`() {
        val list =
            listOf(
                Sighting(near, -55, classified = true),
                Sighting(far, -70, classified = false),
                Sighting(third, -60, classified = true),
            )

        val rows = DeviceScanListPolicy.displayRows(list, emptySet())

        assertEquals(listOf(near, third, far), rows.map { it.address })
    }

    /**
     * DIFFERENTIAL, and the composition decision the issue asked to be stated
     * rather than left to whichever sort runs last: paired-vs-on-offer is the
     * OUTER split, classified-vs-not is the INNER one.
     *
     * `far` is paired AND classified; `third` is on-offer and classified too.
     * If classification were the outer split, both classified rows would lead
     * together (`third, far, near, fourth`) with the paired one still ahead of
     * an unclassified ON-OFFER row -- exactly the "pairable candidate buried
     * under an already-saved device" defect #183 introduced the outer split
     * to prevent. With paired-vs-on-offer outer, `far` cannot leave the
     * bottom half no matter how it classifies, which is the reading this test
     * pins: `third, near, far, fourth`.
     */
    @Test
    fun `the classified split is inner to the paired split, not the other way round`() {
        val list =
            listOf(
                // on offer, unclassified
                Sighting(near, -55, classified = false),
                // paired, classified
                Sighting(far, -70, classified = true),
                // on offer, classified
                Sighting(third, -60, classified = true),
                // paired, unclassified
                Sighting(fourth, -80, classified = false),
            )

        val rows = DeviceScanListPolicy.displayRows(list, setOf(far, fourth))

        assertEquals(listOf(third, near, far, fourth), rows.map { it.address })
    }

    /**
     * The #183 property, held ACROSS the new partition and not just inside
     * the one #183 already guarded.
     *
     * `likelyRole` is safe to order on for the reason RSSI was not: it is a
     * property of the DEVICE (its advertised service UUIDs and name), not of
     * the packet, so it does not jitter. This test is what would catch it if
     * that stopped being true, or if a future edit let RSSI leak back into
     * the ordering: `far` picks up the strongest reading of the three packets
     * sent here, strong enough to flip every [SignalStrength] bucket, and
     * that must not pull it out of the unclassified group or past `third`,
     * the other unclassified row.
     */
    @Test
    fun `a device does not move across the classified split when a packet updates its signal`() {
        var raw = DeviceScanListPolicy.sighted(emptyList(), Sighting(near, -70, classified = true))
        raw = DeviceScanListPolicy.sighted(raw, Sighting(far, -60, classified = false))
        raw = DeviceScanListPolicy.sighted(raw, Sighting(third, -55, classified = true))

        val before = DeviceScanListPolicy.displayRows(raw, emptySet())
        assertEquals(listOf(near, third, far), before.map { it.address })

        raw = DeviceScanListPolicy.sighted(raw, Sighting(far, -30, classified = false))
        val after = DeviceScanListPolicy.displayRows(raw, emptySet())

        assertEquals(
            before.map { it.address },
            after.map { it.address },
            "a much stronger reading does not move a row across, or within, the classified split",
        )
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
        val readings =
            DeviceScanListPolicy.liveRssi(
                true,
                listOf(Sighting(near, -42, classified = false), Sighting(far, -88, classified = false)),
            )

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
            DeviceScanListPolicy.liveRssi(
                false,
                listOf(Sighting(near, -42, classified = false), Sighting(far, -88, classified = false)),
            ),
        )
    }
}
