package com.macrophage.barspeed.model

/**
 * What one advertisement packet told the app about a device.
 *
 * Deliberately just the address and that packet's RSSI: `:core:model` cannot
 * see `:core:ble`'s `DiscoveredDevice` (the dependency runs the other way), and
 * the ordering rule does not need the name or the guessed role anyway. The
 * caller keeps the full record keyed by address and reads this list for the
 * order.
 */
data class Sighting(val address: String, val rssi: Int)

/**
 * How strong a sighting is, coarsely.
 *
 * Three buckets and not a number, because this is what the screen SAYS about a
 * unit rather than what it measured. The dBm figure is still shown beside it;
 * what the bucket adds is a word that does not change every packet.
 *
 * The bucket makes no distance claim. RSSI depends on orientation, on the
 * lifter's body between the two radios and on the phone's own antenna, so
 * "STRONG" means "this radio is loud here, now" and nothing more.
 */
enum class SignalStrength { STRONG, MEDIUM, WEAK }

/**
 * One row of the found-devices list, as the screen should draw it.
 *
 * [alreadyPaired] is the whole of #183's second half: a device the app has
 * already saved is still a legitimate row -- seeing it is how a lifter knows
 * the unit is powered and in range -- but it must not be offered for pairing a
 * second time: the second offer can only change the device's ROLE, and
 * re-filing a saved bar sensor as an HRM leaves `preferred_imu` naming an
 * address `DeviceRegistry.preferred(IMU)` no longer matches, so the analysed
 * link idles on nothing (#184).
 */
data class ScanRow(
    val address: String,
    val rssi: Int,
    val alreadyPaired: Boolean,
    val strength: SignalStrength,
)

/**
 * How the found-devices list is ordered and what each row claims, issue #183.
 *
 * A `:core:model` object because the alternative is a decision nothing can run
 * against: `:core:ble` has no test source set and `:app` has one test file over
 * one pure function. "The order does not change when a packet arrives" is
 * exactly the kind of property a JVM test can hold and a screenshot cannot.
 */
object DeviceScanListPolicy {
    /** At or above this many dBm a sighting reads [SignalStrength.STRONG]. */
    const val STRONG_DBM = -60

    /** At or above this many dBm, and below [STRONG_DBM], a sighting reads [SignalStrength.MEDIUM]. */
    const val MEDIUM_DBM = -80

    /**
     * Which bucket a raw RSSI falls in.
     *
     * The boundaries are display thresholds chosen so that a unit on the bench
     * beside the phone and a unit across the gym do not read the same word.
     * They have not been measured against either sensor -- see the commit
     * body's [Field] note -- and nothing but the wording depends on them.
     */
    fun strengthOf(rssi: Int): SignalStrength = when {
        rssi >= STRONG_DBM -> SignalStrength.STRONG
        rssi >= MEDIUM_DBM -> SignalStrength.MEDIUM
        else -> SignalStrength.WEAK
    }

    /**
     * The scan list after one more packet arrives: FIRST-SEEN order, with the
     * sighted device's reading updated in place.
     *
     * A device must not move because a packet arrived. The rule this replaced
     * re-sorted the whole list by descending RSSI on every result, and
     * `SCAN_MODE_LOW_LATENCY` delivers one per advertisement packet -- several
     * a second per device, each carrying that packet's own RSSI. A few dBm of
     * jitter on a unit lying still on the bench was enough to swap any two
     * rows within a few dBm of each other, continuously.
     *
     * Two alternatives were rejected. Coarse buckets as the sort key still
     * reorder whenever a device sits on a boundary, which is the same defect
     * at lower frequency. Smoothing needs a time base this function does not
     * have and still moves rows the lifter did not touch. First-seen order
     * cannot reorder at all, and the signal is not lost -- it is shown as a
     * value on the row, where a changing number costs a redraw rather than a
     * mis-tap.
     *
     * Removal-and-append is NOT the same rule and is the near-miss worth
     * naming: it drops the re-sighted device at the end, so a device moves
     * because a packet arrived, which is the property this exists to hold.
     */
    fun sighted(current: List<Sighting>, seen: Sighting): List<Sighting> {
        val at = current.indexOfFirst { it.address == seen.address }
        if (at < 0) return current + seen
        return current.toMutableList().apply { this[at] = seen }
    }

    /**
     * The rows the screen draws, from the sightings and the saved devices.
     *
     * A device the app has already saved is SHOWN, marked, and sunk below the
     * ones still on offer -- not hidden. Hiding it risks the lifter reading a
     * missing row as "the scan did not find my sensor", which is the one thing
     * this list is for; and while a second unit is being paired, a row proving
     * the first is powered and in range is exactly the diagnostic wanted. What
     * the caller must not draw for a marked row is the offer to pair it again:
     * the second offer can only change the device's ROLE, and re-filing a
     * saved bar sensor as an HRM strands `preferred_imu` on an address
     * `DeviceRegistry.preferred(IMU)` no longer matches (#184). An earlier
     * draft gave the reason as "pairing is what moves the analysed link";
     * `DeviceRegistry.pair` stopped doing that in the same branch, and the
     * sentence is deleted rather than reworded.
     *
     * That stranded state is also invisible to
     * `AutoConnectManager.forgetAndDrop`, which is the other reason to keep it
     * unreachable: it reads the preference through `DeviceRegistry.preferred`,
     * which filters on `d.role == role`, so a bar sensor re-filed as an HRM
     * answers null and the drop set cannot name [DeviceLinkRole.ANALYSED] --
     * the analysed client would go on holding a unit the registry no longer
     * names. Withholding the second offer is what keeps that state out of
     * reach, so the offer and the blind spot have to move together.
     *
     * The partition is the ONE movement allowed here, and it answers the
     * lifter's own tap: [knownAddresses] changes when something is paired or
     * forgotten and at no other time, so a device leaves the offers the
     * instant it is paired rather than at the next scan.
     */
    fun displayRows(sighted: List<Sighting>, knownAddresses: Set<String>): List<ScanRow> {
        val rows =
            sighted.map {
                ScanRow(
                    address = it.address,
                    rssi = it.rssi,
                    alreadyPaired = it.address in knownAddresses,
                    strength = strengthOf(it.rssi),
                )
            }
        val (paired, onOffer) = rows.partition { it.alreadyPaired }
        return onOffer + paired
    }

    /**
     * The signal readings a paired row may show, keyed by address, and empty
     * when no scan is running.
     *
     * The caller's found list is cleared when a scan STARTS and not when one
     * stops, so without the [scanning] gate a stopped scan's readings stand
     * indefinitely and a frozen number is drawn as a live one. That is not
     * cosmetic: `DualSensorSetup.identifyHint` tells the lifter to label the
     * unit whose row reads strong, and the label decides which physical stream
     * the export files under which name.
     *
     * An address the running scan has no packet for is ABSENT rather than
     * present with a low number, so `DevicePairingPolicy.signalLine` can answer
     * null and the row can show nothing at all.
     *
     * What this does NOT do is date a reading. A unit that stops advertising
     * mid-scan keeps its last one until the scan is restarted; giving that an
     * expiry needs a time base this policy deliberately does not have.
     */
    fun liveRssi(scanning: Boolean, sighted: List<Sighting>): Map<String, Int> =
        if (!scanning) emptyMap() else sighted.associate { it.address to it.rssi }
}
