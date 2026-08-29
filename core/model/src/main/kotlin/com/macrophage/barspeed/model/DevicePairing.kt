package com.macrophage.barspeed.model

/**
 * What a paired device's row on the Devices screen is showing the state of.
 *
 * [NOT_LINKED] is the point of the type. A saved unit that no link is
 * maintaining is not a unit whose connection failed, and drawing the two the
 * same way is absence rendered as a value: it reports a link failure where
 * there is no link. `SensorCapturePolicy.roster` hands the second link a null
 * address under every `DualShortfall`, so this state is not an edge case --
 * it is what the second bar sensor is in from the moment it is paired until
 * both units carry different labels.
 */
enum class DeviceLinkRole { ANALYSED, SECOND, HEART_RATE, NOT_LINKED }

/**
 * How far a two-accelerometer setup has got, issue #184.
 *
 * Named steps rather than a boolean because the screen has to say which thing
 * to go and do next, and the ones that are not terminal each have a different
 * answer.
 */
enum class DualSetupStep {
    /** No bar sensor is paired at all. */
    NO_SENSOR,

    /** Exactly one bar sensor is paired: the ordinary setup, and nothing to fix. */
    ONE_SENSOR,

    /** Two or more are paired and at least one carries no A/B label. */
    LABEL_BOTH,

    /** Every paired unit is labelled and two of them share a label. */
    LABELS_COLLIDE,

    /** Two are paired and carry different labels: a set asking for two can arm both. */
    READY,
}

/**
 * What pairing, forgetting and preferring do, issue #184.
 *
 * Lifted out of `:core:ble` for the usual reason -- that module has no test
 * source set, so a rule left there is a rule nothing can run against. These
 * are introduced reproducing TODAY's behaviour so the differentials can be
 * shown red first.
 */
object DevicePairingPolicy {
    /**
     * Which address is a role's preferred one after [justPaired] is paired.
     *
     * TODAY's rule, from `DeviceRegistry.pair`: the newly paired device always
     * becomes preferred. [currentPreferred] and [pairedOfRole] are the state
     * before the pairing.
     */
    fun preferredAfterPairing(currentPreferred: String?, pairedOfRole: Set<String>, justPaired: String): String {
        currentPreferred?.let { pairedOfRole.contains(it) }
        return justPaired
    }

    /**
     * Which address is a role's preferred one after [forgotten] is forgotten.
     *
     * TODAY's rule, from `DeviceRegistry.forget`: the preference is dropped
     * when the device holding it goes, and nothing takes its place.
     * [remainingOfRole] is what is still paired for that role afterwards.
     */
    fun preferredAfterForget(currentPreferred: String?, forgotten: String, remainingOfRole: List<String>): String? {
        remainingOfRole.size
        return if (currentPreferred == forgotten) null else currentPreferred
    }

    /**
     * Which link, if any, is maintaining [address].
     *
     * Every argument is an address a link is ACTUALLY pointed at, never a
     * guess at which unit ought to be second. The order of the checks is the
     * order the Devices screen's own `when` used: analysed first, so a device
     * that is somehow both cannot report as the second one.
     */
    fun linkRoleFor(
        address: String,
        analysedImuAddress: String?,
        secondImuAddress: String?,
        hrmAddress: String?,
    ): DeviceLinkRole = when (address) {
        analysedImuAddress -> DeviceLinkRole.ANALYSED
        secondImuAddress -> DeviceLinkRole.SECOND
        hrmAddress -> DeviceLinkRole.HEART_RATE
        else -> DeviceLinkRole.NOT_LINKED
    }

    /**
     * The short, durable name for a unit: the last two octets of its address.
     *
     * Two WT901s advertise the same name, so the name cannot tell one row from
     * the other. The address can, it never changes, and the last two octets are
     * short enough to sit in a row heading. The whole address is still printed
     * underneath -- this is a handle, not a replacement.
     *
     * Anything that is not colon-separated comes back uppercased and whole,
     * rather than being cut at a fixed offset: an address shape this has never
     * seen must not be silently truncated into what looks like a different
     * unit's tag.
     */
    fun unitTag(address: String): String {
        val parts = address.split(":")
        return if (parts.size < 2) address.uppercase() else parts.takeLast(2).joinToString(":").uppercase()
    }

    /**
     * What to say about a unit's live signal, or null when there is nothing to
     * say.
     *
     * Null and not "weak" when [rssi] is null. A connected unit may stop
     * advertising and a switched-off one certainly does; either way the app has
     * no reading, and printing the weakest bucket for "no reading" is the same
     * absence-as-a-value mistake [DeviceLinkRole] exists to avoid.
     */
    fun signalLine(rssi: Int?): String? {
        if (rssi == null) return null
        val word = DeviceScanListPolicy.strengthOf(rssi).name.lowercase()
        return "Signal $word ($rssi dBm)"
    }
}

/**
 * What the screens say about a two-accelerometer setup, issue #184.
 *
 * One copy of these sentences, read by both screens. They were a single copy
 * on the Record screen and none at all on Devices, which is why the lifter
 * doing the pairing was never told that labelling is a required step: the one
 * sentence explaining it drew on a different screen, and only when a plan
 * declared two sensors.
 */
object DualSensorSetup {
    /**
     * The Record screen's sensor-count line for a shortfall.
     *
     * Byte-identical to the strings `RecordScreen.sensorCountDetail` carried
     * before this lift; nothing the lifter reads there changes.
     */
    fun recordLine(shortfall: DualShortfall): String = when (shortfall) {
        // `roster` returns this whenever it cannot name two distinct
        // addresses, which includes NONE paired as well as one. The enum's
        // name is narrower than the states it covers; the sentence is not.
        DualShortfall.ONE_SENSOR_PAIRED -> "Fewer than two sensors are paired - this set will record one."
        DualShortfall.ROLES_UNASSIGNED -> "Label both sensors A and B under Devices - this set will record one."
        DualShortfall.ROLES_COLLIDE -> "Both sensors are labelled the same - fix it under Devices."
    }

    /**
     * How far the setup has got, from what is paired and what is labelled.
     *
     * Deliberately does NOT take the preferred address, unlike
     * `SensorCapturePolicy.roster`. Which unit is analysed is a separate
     * question from whether the pair can be told apart, and folding the two
     * together is what makes the Record screen's `ONE_SENSOR_PAIRED` cover a
     * stale preference as well as a missing unit.
     *
     * Every paired unit is considered, not just two: three IMUs carrying two
     * labels is a collision, and saying so is more useful than picking two of
     * them and reporting on those.
     */
    fun step(pairedImuAddresses: List<String>, roleByAddress: Map<String, SensorRole>): DualSetupStep {
        val paired = pairedImuAddresses.distinct()
        if (paired.isEmpty()) return DualSetupStep.NO_SENSOR
        if (paired.size < SensorCapturePolicy.MAX_COUNT) return DualSetupStep.ONE_SENSOR
        val labels = paired.map { roleByAddress[it] }
        if (labels.any { it == null }) return DualSetupStep.LABEL_BOTH
        if (labels.distinct().size < labels.size) return DualSetupStep.LABELS_COLLIDE
        return DualSetupStep.READY
    }

    /**
     * What the Devices screen says about the setup, or null for nothing.
     *
     * TODAY's answer, for every step: nothing. The Devices screen has never
     * said a word about labelling, which is #184's second symptom.
     */
    fun devicesLine(step: DualSetupStep): String? {
        step.ordinal
        return null
    }

    /**
     * How to tell two identically-named units apart, or null when the lifter
     * does not need to.
     *
     * TODAY's answer, for every step: nothing on the screen. The ritual is in
     * `DevicesScreen`'s KDoc, where the person holding two identical sensors
     * cannot read it.
     */
    fun identifyHint(step: DualSetupStep): String? {
        step.ordinal
        return null
    }
}
