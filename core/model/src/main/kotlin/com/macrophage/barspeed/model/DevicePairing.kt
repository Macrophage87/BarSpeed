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
 * What the deliberate-preference control on a paired row draws.
 *
 * Two cases and not a nullable button label, because "this unit is the live
 * one" and "this unit could become the live one" are different sentences and a
 * row must never draw both or neither.
 */
sealed interface PreferenceControl {
    /** This row's unit already holds its role's link; the row says so and offers nothing. */
    data class InUse(val line: String) : PreferenceControl

    /** This row's unit does not hold its role's link; the row offers to move it. */
    data class Offer(val label: String) : PreferenceControl
}

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
 * Which device each of the two bar-sensor links should be maintaining.
 *
 * Both are addresses, never roles: there are two links and only one
 * [DeviceRole.IMU]-shaped role, because both WT901s are ordinary paired IMUs
 * (`AutoConnectManager.imuClientB`). A null field means that link should be
 * pointed at nothing -- which is a state, not a failure, and is what
 * [DeviceLinkRole.NOT_LINKED] draws.
 *
 * The pair is answered together rather than one field at a time so that
 * "these two links are pointed at one remote" is a question a test can ask.
 * The invariant is that no path may leave two clients on one WT901:
 * `WitmotionStreamDecoder` holds one buffer per client and the WT901's
 * 20-byte frames carry no checksum, so nothing in the app could notice, and a
 * dual set's two archives would be two recordings of one unit filed under two
 * labels.
 */
data class ImuLinkTargets(
    /** The unit the analysed link should hold, or null when no paired unit is preferred. */
    val analysed: String?,
    /** The unit the second link should hold, or null when no second link should be up. */
    val second: String?,
)

/**
 * What pairing, forgetting and preferring do, issue #184.
 *
 * Lifted out of `:core:ble` for the usual reason -- that module has no test
 * source set, so a rule left there is a rule nothing can run against.
 */
object DevicePairingPolicy {
    /**
     * Which address is a role's preferred one after [justPaired] is paired:
     * the one it already had, if that device is still paired.
     *
     * [currentPreferred] and [pairedOfRole] are the state BEFORE the pairing.
     *
     * The rule this replaced made every newly paired device its role's
     * preferred one, and preferred is not a display flag: `AutoConnectManager`
     * maintains the analysed link to it and `SensorCapturePolicy.roster` reads
     * it to decide which role's stream the DSP is pointed at. So pairing a
     * second bar sensor re-pointed a data path as a side effect of a UI act
     * performed for an unrelated reason, and because A/B labels are
     * per-address it silently changed which label the analysed figures came
     * from. A wrong pixel is recoverable and a wrongly-attributed stream is
     * not.
     *
     * It is also the clunk the owner reported: mid-sequence the first row went
     * dark and the second lit up, which reads as one sensor knocking the other
     * off. Which unit is analysed stays changeable -- by asking, through a
     * control that says what it does.
     *
     * A preference naming a device that is NOT in [pairedOfRole] gives way,
     * because it is not a preference: `DeviceRegistry` can hold a
     * `preferred_imu` for a forgotten address, and keeping it would leave the
     * analysed link idling on an address nothing is paired to.
     */
    fun preferredAfterPairing(currentPreferred: String?, pairedOfRole: Set<String>, justPaired: String): String =
        if (currentPreferred != null && currentPreferred in pairedOfRole) currentPreferred else justPaired

    /**
     * Which address is a role's preferred one after [forgotten] is forgotten:
     * whatever is left, when the forgotten device was holding it.
     *
     * [remainingOfRole] is what is still paired for that role afterwards.
     *
     * The near neighbour of [preferredAfterPairing] and it had to move with
     * it. While pairing re-pointed the preference, losing it was self-healing
     * -- the lifter paired something and it came back. Now that pairing leaves
     * it alone, forgetting the analysed unit while another is still paired
     * would leave the role with no preferred device at all and the analysed
     * link idling on a null address until the lifter noticed.
     *
     * Promoting the survivor is the convenience, not the only route back: the
     * Devices screen draws "Use this one for analysis" on every paired bar
     * sensor that is not the analysed one, and the strap equivalent on a
     * strap, which in that state is the survivor. An earlier draft of this
     * KDoc said there was no way back short of forgetting the survivor too;
     * that was false when it was written -- the same commit added the control
     * -- and it is deleted rather than reworded.
     */
    fun preferredAfterForget(currentPreferred: String?, forgotten: String, remainingOfRole: List<String>): String? =
        if (currentPreferred != forgotten) currentPreferred else remainingOfRole.firstOrNull()

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
     * Which links must be dropped when [forgotten] is forgotten: every link
     * that was pointed at it, and the SECOND link when it is holding whichever
     * bar sensor the analysed role points at after the forget.
     *
     * A link left holding a forgotten unit does not fall over on its own.
     * `AutoConnectManager.maintain`'s Connected branch is parked on
     * `connectionState.first { it !is Connected }`, so the client keeps
     * streaming that unit for the rest of the session while
     * [preferredAfterForget] has already re-pointed the role at a survivor --
     * and `SensorCapturePolicy.roster` would then file the forgotten unit's
     * stream under the survivor's label.
     *
     * Every address handed in is what a link is pointed at BEFORE the forget,
     * which is the only moment the question can be answered:
     * [preferredAfterForget] has already moved the preference by the time the
     * registry write has returned.
     *
     * A unit holding two links yields two, rather than the first match: one
     * WT901 can be both the analysed unit and the second one.
     *
     * The SECOND link also goes down when the survivor [preferredAfterForget]
     * promotes is the address that link is already holding, which is the same
     * two-clients-on-one-WT901 state [linksToDropOnPrefer] refuses, reached
     * from the Forget button instead of the preference button. Forgetting the
     * analysed unit hands `preferred_imu` to a survivor; if that survivor is
     * what the second link is on, dropping the analysed client alone wakes
     * `maintain`, which re-reads the promoted address and brings `imuClient`
     * up on the unit `imuClientB` is already streaming.
     *
     * [remainingImu] is what that costs to know: the bar-sensor addresses
     * still paired AFTER the forget, in the order `DeviceRegistry.forget`
     * keeps them. The promotion is derived here through
     * [preferredAfterForget] rather than handed in already computed, so that
     * function stays the single answer to "who gets promoted" and a change to
     * it reaches the drop set without a second site having to follow.
     *
     * Only the SAME address, for [linksToDropOnPrefer]'s reason: with a third
     * bar sensor ahead of it in the registry the second link's unit is not the
     * one promoted, and taking the link down anyway would cost the next dual
     * set a stream for nothing. Only the bar-sensor promotion is consulted,
     * because [DeviceLinkRole.SECOND] is a bar-sensor link and a strap's
     * survivor cannot be the address it holds.
     *
     * A null [secondImu] is checked for explicitly rather than left to the
     * comparison. Forgetting the last bar sensor promotes nothing, and
     * "no second link" must not come out equal to "nothing was promoted".
     */
    fun linksToDropOnForget(
        forgotten: String,
        preferredImu: String?,
        preferredHrm: String?,
        secondImu: String?,
        remainingImu: List<String>,
    ): Set<DeviceLinkRole> {
        val promotedImu = preferredAfterForget(preferredImu, forgotten, remainingImu)
        return setOfNotNull(
            DeviceLinkRole.ANALYSED.takeIf { preferredImu == forgotten },
            DeviceLinkRole.HEART_RATE.takeIf { preferredHrm == forgotten },
            DeviceLinkRole.SECOND.takeIf {
                secondImu != null && (secondImu == forgotten || secondImu == promotedImu)
            },
        )
    }

    /**
     * Which links must be dropped when the role that owns [ownedLink] is
     * deliberately re-pointed at [newlyPreferred].
     *
     * [ownedLink] is the link that role's preferred device holds:
     * [DeviceLinkRole.ANALYSED] for a bar sensor, [DeviceLinkRole.HEART_RATE]
     * for a strap. [secondImu] is the address the SECOND link is actually
     * pointed at, or null when it is pointed at nothing.
     *
     * The role's own link is always dropped rather than redirected, which is
     * the rule `AutoConnectManager.setPreferredAndConnect` already followed as
     * a bare `disconnect()` call: `maintain`'s Connected branch is parked on
     * `connectionState.first { it !is Connected }`, so a client already holding
     * the old device would sit there indefinitely and the new address would
     * never be read. Dropping it wakes that branch.
     *
     * The SECOND link goes down too when it is the one holding
     * [newlyPreferred], and this is the half that is not a lift. Promoting the
     * unit the second link is already on would otherwise point `imuClient` and
     * `imuClientB` at the same remote: `AutoConnectManager` drops the analysed
     * client alone and `secondaryImuAddress` still names that address, so
     * `maintain` brings both links up on one WT901. `WitmotionStreamDecoder`
     * holds one buffer per client and the WT901's 20-byte frames carry no
     * checksum, so nothing in the app can notice; if both links stream, a dual
     * set's two raw archives are two recordings of ONE unit filed under two
     * labels. [linksToDropOnForget] refuses the same state reached from the
     * Forget button. An earlier draft of this paragraph said that rule already
     * prevented it; it did not -- it dropped the second link only when the
     * forgotten unit was the one holding it -- and the sentence is deleted
     * rather than reworded.
     *
     * Only when it is the SAME address. Taking the second link down for every
     * promotion would cost a dual set its second stream for no reason, and
     * `preferring a unit the second link is not holding leaves the second link
     * alone` is what holds that.
     *
     * Only for [DeviceLinkRole.ANALYSED]. The second link is a bar-sensor
     * link, so a strap promotion cannot be the unit holding it; the guard is
     * on the argument rather than on a state the screen can produce.
     *
     * What this does NOT do is re-point the second link at the unit the
     * analysed link just left. [imuLinkTargets] decides that and its two
     * appliers -- `RecordViewModel.mirrorSensorSettings` and, since #192,
     * `DevicesViewModel`'s own arming collector -- bring it back, each from
     * the preference this call has already moved. The sentence here used to
     * say the Devices screen could not arm a second link at all; #192 fixed
     * that and it is deleted rather than reworded. Between the drop and the
     * re-arm the second unit reads "Not linked", which is what that state is
     * for.
     *
     * Here rather than in `:core:ble` for the reason [linksToDropOnForget] is
     * here: that module has no test source set, so which links a tap takes down
     * is a decision nothing can run against while it lives there.
     */
    fun linksToDropOnPrefer(
        ownedLink: DeviceLinkRole,
        newlyPreferred: String,
        secondImu: String?,
    ): Set<DeviceLinkRole> = setOfNotNull(
        ownedLink,
        DeviceLinkRole.SECOND.takeIf { ownedLink == DeviceLinkRole.ANALYSED && secondImu == newlyPreferred },
    )

    /**
     * What a paired row draws about being its role's live unit, or null when
     * [ownedLink] is a link no device role owns.
     *
     * [ownedLink] is the link a device of this row's role holds when it is the
     * preferred one: [DeviceLinkRole.ANALYSED] for a bar sensor,
     * [DeviceLinkRole.HEART_RATE] for a strap. [currentLink] is what
     * [linkRoleFor] answered for this row's address.
     *
     * Here rather than as an `if` on the screen for the usual reason: `:app`
     * has one test file over one pure function, so which rows offer to move a
     * preference is a rule that can be held in `:core:model` and cannot be
     * held there.
     *
     * Answered for the strap as well as the bar sensor, because the pairing
     * rule that made the control necessary is role-generic:
     * `DeviceRegistry.pair` keys off `keyFor(role)`, so pairing a second strap
     * no longer switches to it either, and with the control drawn for bar
     * sensors only the sole route to the new strap was to forget the old one.
     *
     * The strap's sentence claims nothing about the link being up. This rule
     * is answered from which link holds the address; the connection chip
     * beside it reads the link, and the two can disagree.
     */
    fun preferenceControl(ownedLink: DeviceLinkRole, currentLink: DeviceLinkRole): PreferenceControl? =
        when (ownedLink) {
            DeviceLinkRole.ANALYSED ->
                if (currentLink == DeviceLinkRole.ANALYSED) {
                    PreferenceControl.InUse("Analysed · the set's numbers come from this unit")
                } else {
                    PreferenceControl.Offer("Use this one for analysis")
                }
            DeviceLinkRole.HEART_RATE ->
                if (currentLink == DeviceLinkRole.HEART_RATE) {
                    PreferenceControl.InUse("Heart rate · readings come from this strap")
                } else {
                    PreferenceControl.Offer("Use this strap for heart rate")
                }
            DeviceLinkRole.SECOND, DeviceLinkRole.NOT_LINKED -> null
        }

    /**
     * Which device each bar-sensor link should be pointed at, given what is
     * paired, which unit is preferred and how the lifter has labelled them.
     *
     * Issue #192. The Devices screen could pair a second bar sensor, label it
     * and draw its row, and had no way to bring its link up: the only caller
     * of `AutoConnectManager.setSecondaryImuAddress` was
     * `RecordViewModel.mirrorSensorSettings`, so the second link was armed
     * only as a side effect of setting a session up on another screen. That
     * made #184's own field criterion -- reach both rows green on the Devices
     * screen -- impossible rather than merely awkward.
     *
     * Here rather than in the ViewModel for [linksToDropOnForget]'s reason:
     * `:app` has two test files over two pure functions, so which unit a link
     * names is a decision nothing could run against while it lives there.
     *
     * [analysed] is `AutoConnectManager`'s own rule restated in one place a
     * test can reach -- `registry.preferredNow(IMU)`, which
     * `DeviceRegistry.preferred` already resolves against the paired list, so
     * a preference naming a forgotten unit answers null here as it does
     * there. It is not a second source of truth for the analysed link; it is
     * what the second slot is checked against.
     *
     * [second] is [SensorCapturePolicy.roster]'s `secondaryAddress` asked for
     * two sensors, and NOT a new rule beside it. One function decides which
     * physical unit is the second one; a second copy of that decision would
     * be free to disagree with the one the set is actually captured under,
     * and the disagreement would be invisible until the export attributed one
     * unit's samples to the other's label.
     *
     * Deliberately ignores the per-exercise sensor COUNT, as
     * `mirrorSensorSettings` already did: the link is kept warm whenever the
     * pair is ready, so arming dual for one exercise does not have to wait
     * out a BLE connect at the moment the lifter taps START. What the count
     * decides is whether the set CAPTURES from it.
     */
    fun imuLinkTargets(
        pairedImuAddresses: List<String>,
        preferredImuAddress: String?,
        roleByAddress: Map<String, SensorRole>,
    ): ImuLinkTargets {
        val paired = pairedImuAddresses.distinct()
        val analysed = preferredImuAddress?.takeIf { it in paired }
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = paired,
                preferredAddress = analysed,
                roleByAddress = roleByAddress,
                requestedCount = SensorCapturePolicy.MAX_COUNT,
            )
        return ImuLinkTargets(analysed = analysed, second = roster.secondaryAddress)
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
     * The same facts as [recordLine] in this screen's own voice -- "under
     * Devices" is not a useful locator when you are already there -- rather
     * than a second wording of them. The Record screen's copy was the ONLY
     * place the app said labelling is required, and it draws on a different
     * screen and only when a plan declares two sensors, so the lifter doing
     * the pairing had never been told.
     *
     * Silent where there is nothing to fix. One sensor is the ordinary setup,
     * for every exercise, and nagging it about a unit it does not have would
     * turn this line into something the eye learns to skip.
     */
    fun devicesLine(step: DualSetupStep): String? = when (step) {
        DualSetupStep.NO_SENSOR, DualSetupStep.ONE_SENSOR -> null
        DualSetupStep.LABEL_BOTH ->
            "Label both sensors A and B - a set asking for two records only one until they carry " +
                "different labels."
        DualSetupStep.LABELS_COLLIDE -> "Both sensors are labelled the same - give one of them the other label."
        DualSetupStep.READY -> "Both sensors are labelled. A set asking for two will record both streams."
    }

    /**
     * How to tell two identically-named units apart, or null when the lifter
     * does not need to.
     *
     * This is `DevicesScreen`'s KDoc ritual moved onto the screen, where the
     * person holding two identical sensors can read it -- and restated,
     * because the old one no longer describes the app. It said to note which
     * single row is green and label the OTHER by elimination; with the
     * preference no longer moving on pairing there is no longer a moment when
     * exactly one row is green, so the reference point it turned on is gone.
     *
     * The live signal replaces it. It claims nothing about a unit that is not
     * advertising: a row with no reading shows no signal line at all, and the
     * lifter compares the rows that do.
     */
    fun identifyHint(step: DualSetupStep): String? = when (step) {
        DualSetupStep.LABEL_BOTH, DualSetupStep.LABELS_COLLIDE ->
            "Two units can advertise the same name. Scan below and hold one against the phone: " +
                "the row whose signal reads strong is that unit."
        DualSetupStep.NO_SENSOR, DualSetupStep.ONE_SENSOR, DualSetupStep.READY -> null
    }
}
