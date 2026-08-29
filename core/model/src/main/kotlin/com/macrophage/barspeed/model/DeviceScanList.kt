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
 * second time, because pairing is what moves the analysed link (#184).
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
 *
 * The functions here are introduced reproducing TODAY's behaviour, so that the
 * differentials for #183 can be written against them and shown red before
 * anything is fixed.
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
     * The scan list after one more packet arrives.
     *
     * TODAY's rule, lifted verbatim from `DevicesViewModel.toggleScan`: drop
     * any existing entry for the address, append the new sighting, re-sort the
     * whole list by descending RSSI.
     */
    fun sighted(current: List<Sighting>, seen: Sighting): List<Sighting> =
        (current.filterNot { it.address == seen.address } + seen).sortedByDescending { it.rssi }

    /**
     * The rows the screen draws, from the sightings and the saved devices.
     *
     * TODAY's rule: every sighting is a row, in scan-list order, and none of
     * them knows it is already paired. [knownAddresses] is accepted and not
     * consulted, which is precisely what #183 reports -- a sensor that is
     * already saved, and already drawn above with its own row, is offered for
     * pairing again below.
     */
    fun displayRows(sighted: List<Sighting>, knownAddresses: Set<String>): List<ScanRow> {
        knownAddresses.size
        return sighted.map {
            ScanRow(
                address = it.address,
                rssi = it.rssi,
                alreadyPaired = false,
                strength = strengthOf(it.rssi),
            )
        }
    }
}
