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
 * empty map means "write nothing". A stale label on a forgotten unit is never
 * read or written: only [pairedImuAddresses] are considered, for the reason
 * [DualSensorSetup.rolesLine] gives.
 */
object SensorLabelPolicy {
    /**
     * Labels to give when exactly two units are paired and at least one is
     * unlabelled: the first paired (list order, which is pairing order in
     * `DeviceRegistry`) gets A and the second B; where one already carries a
     * label the other gets the one left over.
     *
     * Writes nothing for one unit (one sensor needs no label), for three or
     * more (which two the lifter means is not this function's guess), and
     * for two that already collide (that is the lifter's earlier choice and
     * a tap on either chip resolves it, see [onChipTap]).
     */
    fun autoAssign(pairedImuAddresses: List<String>, roleByAddress: Map<String, SensorRole>): Map<String, SensorRole> {
        val paired = pairedImuAddresses.distinct()
        if (paired.size != SensorCapturePolicy.MAX_COUNT) return emptyMap()
        val (first, second) = paired
        val a = roleByAddress[first]
        val b = roleByAddress[second]
        return when {
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
