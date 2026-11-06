package com.macrophage.barspeed.model

/**
 * Who carries which A/B label, without asking the lifter to do it by hand,
 * UX review 2026-11-06 (ble-devices F3).
 *
 * The labels are identity only (#156: no side, no meaning beyond telling two
 * identical WT901s apart), so asking for two chip taps before the second
 * unit records anything was ceremony. Until those taps the second unit's row
 * read "Not linked" and a tap on the label the first unit already held
 * produced a collision.
 *
 * Both functions return the label WRITES to make -- address to new label --
 * and never the whole map, so the caller writes exactly what changed and an
 * empty map means "write nothing". A label on a unit that is not paired is
 * never read or written: only [pairedImuAddresses] are considered, for the
 * reason [DualSensorSetup.rolesLine] gives. A unit forgotten and paired again
 * comes back with the label it had, which is why [autoAssign] resolves a
 * collision rather than leaving it.
 *
 * Forgetting a unit deliberately does NOT clear its label. The export's
 * `unitAddresses` reads the whole label store and withholds a role two
 * addresses claim; a forgotten unit's label is what keeps a replacement given
 * the same letter from being published as the unit that recorded the older
 * sessions (`SensorCapturePolicy.unitAddresses`).
 */
object SensorLabelPolicy {
    /**
     * Labels to give when exactly two units are paired and at least one is
     * unlabelled: the first paired (list order, which is pairing order in
     * `DeviceRegistry`) gets A and the second B; where one already carries a
     * label the other gets the one left over.
     *
     * Two that collide are resolved the same way: the first paired keeps
     * its label and the second takes the other (whole-release review). No
     * tap makes a collision ([onChipTap] never leaves one), so one is always
     * a stale label -- a unit forgotten and re-paired with the label it had,
     * beside a unit given that label in the meantime. Re-pairing puts the
     * unit last in `DeviceRegistry`, so it is the one relabelled. Left
     * alone, the two read as colliding and a set recorded one stream.
     *
     * Writes nothing for one unit (one sensor needs no label) or for three
     * or more (which two the lifter means is not this function's guess).
     */
    fun autoAssign(pairedImuAddresses: List<String>, roleByAddress: Map<String, SensorRole>): Map<String, SensorRole> {
        val paired = pairedImuAddresses.distinct()
        if (paired.size != SensorCapturePolicy.MAX_COUNT) return emptyMap()
        val (first, second) = paired
        val a = roleByAddress[first]
        val b = roleByAddress[second]
        return when {
            a != null && a == b -> mapOf(second to other(a))
            a != null && b != null -> emptyMap()
            a != null -> mapOf(second to other(a))
            b != null -> mapOf(first to other(b))
            else -> mapOf(first to SensorRole.A, second to SensorRole.B)
        }
    }

    /**
     * What a tap on [tapped] in [address]'s label row writes.
     *
     * A tap never leaves two paired units carrying one label: whichever other
     * paired unit already held [tapped] takes [address]'s old label (or the
     * other one, if [address] had none). A tap on the label [address] already
     * carries swaps the two units, which is the one-tap fix when the stickers
     * turn out to be the other way round.
     */
    fun onChipTap(
        address: String,
        tapped: SensorRole,
        pairedImuAddresses: List<String>,
        roleByAddress: Map<String, SensorRole>,
    ): Map<String, SensorRole> {
        val current = roleByAddress[address]
        val mine = if (current == tapped) other(tapped) else tapped
        val partnerGets = current ?: other(mine)
        val writes = linkedMapOf(address to mine)
        pairedImuAddresses.distinct()
            .filter { it != address && roleByAddress[it] == mine }
            .forEach { writes[it] = partnerGets }
        if (current == tapped) {
            // A swap: the partner takes the label this unit just gave up,
            // even if it had none.
            pairedImuAddresses.distinct()
                .filter { it != address && roleByAddress[it] != tapped }
                .singleOrNull()
                ?.let { writes[it] = tapped }
        }
        return writes
    }

    private fun other(role: SensorRole): SensorRole = if (role == SensorRole.A) SensorRole.B else SensorRole.A
}
