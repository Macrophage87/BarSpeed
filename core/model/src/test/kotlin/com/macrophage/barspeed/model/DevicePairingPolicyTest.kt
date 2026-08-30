package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pairing, preferring and what the Devices screen says, issue #184.
 *
 * Several of these are differentials: they fail against the rules the app
 * ships today and pass only once those rules change. The characterizations
 * they replaced -- "today pairing a second unit takes preferred off the
 * first", "today forgetting the preferred unit promotes nothing" and "today
 * the devices screen says nothing at any step" -- are gone, having shown that
 * the shipped rules are what these contradict.
 */
class DevicePairingPolicyTest {
    private val first = "AA:AA:AA:AA:AA:01"
    private val second = "BB:BB:BB:BB:BB:02"
    private val strap = "CC:CC:CC:CC:CC:03"

    // ---- which unit is preferred ---------------------------------------------

    /**
     * The decision #184 left to the implementer: pairing a second unit of a
     * role does NOT take the analysed link off the first.
     *
     * Preferred is not a display flag. `AutoConnectManager` maintains the
     * analysed link to it and `SensorCapturePolicy.roster` reads it to decide
     * which role's stream the DSP is pointed at, so moving it re-points a data
     * path -- and it moved as a side effect of a UI act the lifter performed
     * for an unrelated reason. Because labels are per-address, it also
     * silently changed the analysed role from A to B. A wrong pixel is
     * recoverable and a wrongly-attributed stream is not, so the tie goes to
     * leaving it alone.
     *
     * Mid-sequence the lifter no longer watches the first row go dark and the
     * second light up, which is the clunk the owner reported. Which unit is
     * analysed stays changeable, but only by asking for it.
     */
    @Test
    fun `pairing a second unit of a role leaves the analysed one alone`() {
        assertEquals(
            first,
            DevicePairingPolicy.preferredAfterPairing(
                currentPreferred = first,
                pairedOfRole = setOf(first),
                justPaired = second,
            ),
        )
    }

    /**
     * A preference naming a device that is no longer paired is not a
     * preference. `DeviceRegistry` can hold `preferred_imu` for a forgotten
     * address, and `roster` already refuses to treat that as one of the pair;
     * keeping it here would leave the role with no live preferred device and
     * the analysed link pointed at nothing.
     */
    @Test
    fun `a preference naming a unit that is no longer paired gives way`() {
        assertEquals(
            second,
            DevicePairingPolicy.preferredAfterPairing(
                currentPreferred = strap,
                pairedOfRole = setOf(first),
                justPaired = second,
            ),
        )
    }

    @Test
    fun `re-pairing the analysed unit keeps it analysed`() {
        assertEquals(
            first,
            DevicePairingPolicy.preferredAfterPairing(
                currentPreferred = first,
                pairedOfRole = setOf(first, second),
                justPaired = first,
            ),
        )
    }

    @Test
    fun `the first unit paired is the preferred one`() {
        assertEquals(
            first,
            DevicePairingPolicy.preferredAfterPairing(
                currentPreferred = null,
                pairedOfRole = emptySet(),
                justPaired = first,
            ),
        )
    }

    /**
     * The near neighbour of the change above, and it has to move with it.
     *
     * While pairing re-pointed the preference, forgetting the analysed unit
     * was self-healing: the lifter re-paired something and the preference came
     * back. Once pairing stops moving it, forgetting the analysed unit with
     * another still paired would leave the role with NO preferred device and
     * the analysed link idling on a null address until the lifter noticed.
     *
     * Promoting the survivor is the convenience and not the only route back:
     * the Devices screen draws "Use this one for analysis" on every paired bar
     * sensor that is not the analysed one, and the strap equivalent on a strap,
     * which in that state is the survivor. An earlier draft of this KDoc said
     * there was no way back short of forgetting the survivor too; that was
     * false when it was written -- the same commit added the control -- and
     * the sentence is deleted rather than reworded.
     */
    @Test
    fun `forgetting the analysed unit promotes the one that is left`() {
        assertEquals(
            second,
            DevicePairingPolicy.preferredAfterForget(
                currentPreferred = first,
                forgotten = first,
                remainingOfRole = listOf(second),
            ),
        )
    }

    @Test
    fun `forgetting the only unit of a role leaves no preference to promote`() {
        assertNull(
            DevicePairingPolicy.preferredAfterForget(
                currentPreferred = first,
                forgotten = first,
                remainingOfRole = emptyList(),
            ),
        )
    }

    @Test
    fun `forgetting some other unit leaves the preference alone`() {
        assertEquals(
            first,
            DevicePairingPolicy.preferredAfterForget(
                currentPreferred = first,
                forgotten = second,
                remainingOfRole = listOf(first),
            ),
        )
    }

    // ---- which link a row is showing -----------------------------------------

    @Test
    fun `a row shows the link that is actually pointed at its address`() {
        assertEquals(DeviceLinkRole.ANALYSED, DevicePairingPolicy.linkRoleFor(first, first, second, strap))
        assertEquals(DeviceLinkRole.SECOND, DevicePairingPolicy.linkRoleFor(second, first, second, strap))
        assertEquals(DeviceLinkRole.HEART_RATE, DevicePairingPolicy.linkRoleFor(strap, first, second, strap))
    }

    /**
     * The state the whole of #184's honesty rests on: a saved unit no link is
     * pointed at is NOT a unit whose connection failed.
     */
    @Test
    fun `a paired unit no link is maintaining is not linked, which is not disconnected`() {
        assertEquals(
            DeviceLinkRole.NOT_LINKED,
            DevicePairingPolicy.linkRoleFor(
                address = second,
                analysedImuAddress = first,
                secondImuAddress = null,
                hrmAddress = null,
            ),
        )
        assertEquals(
            DeviceLinkRole.NOT_LINKED,
            DevicePairingPolicy.linkRoleFor(
                address = first,
                analysedImuAddress = null,
                secondImuAddress = null,
                hrmAddress = null,
            ),
        )
    }

    @Test
    fun `analysed wins when one address is somehow both`() {
        assertEquals(DeviceLinkRole.ANALYSED, DevicePairingPolicy.linkRoleFor(first, first, first, null))
    }

    // ---- what a forget takes down --------------------------------------------

    /**
     * A characterization of today: a forget drops nothing.
     *
     * This case survives the change, because a unit no link is pointed at is
     * the one forget can safely leave the radios alone for.
     */
    @Test
    fun `forgetting a unit no link is pointed at drops nothing`() {
        assertEquals(
            emptySet(),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = second,
                preferredImu = first,
                preferredHrm = strap,
                secondImu = null,
            ),
        )
    }

    /**
     * DIFFERENTIAL, finding 1: the link holding the forgotten unit has to go
     * down with it.
     *
     * `DeviceRegistry.forget` promotes a survivor into the role's preferred
     * address, and `AutoConnectManager.maintain`'s Connected branch is parked
     * on `connectionState.first { it !is Connected }` -- so a client left
     * alone keeps streaming the FORGOTTEN unit while the screen, and
     * `SensorCapturePolicy.roster` behind it, name the survivor. With three
     * IMUs paired that misattribution reaches the archive: the stream recorded
     * under the survivor's label is the forgotten unit's. Wrong pixels are
     * recoverable; a wrongly attributed capture is not.
     */
    @Test
    fun `forgetting the analysed unit drops the link that was holding it`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = first,
                preferredImu = first,
                preferredHrm = strap,
                secondImu = null,
            ),
        )
    }

    /**
     * DIFFERENTIAL: the same reasoning for the role the commit body never
     * mentioned. `keyFor` is role-generic and so is the defect.
     */
    @Test
    fun `forgetting the strap drops the heart-rate link`() {
        assertEquals(
            setOf(DeviceLinkRole.HEART_RATE),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = strap,
                preferredImu = first,
                preferredHrm = strap,
                secondImu = second,
            ),
        )
    }

    /**
     * DIFFERENTIAL: the second link is pointed at a plain address rather than
     * at a preference, so nothing else would ever take it off a unit that is
     * no longer paired.
     */
    @Test
    fun `forgetting the second unit takes the second link down`() {
        assertEquals(
            setOf(DeviceLinkRole.SECOND),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = second,
                preferredImu = first,
                preferredHrm = null,
                secondImu = second,
            ),
        )
    }

    /**
     * DIFFERENTIAL: one unit can be holding two links at once, and forgetting
     * it has to take both down rather than the first one found.
     */
    @Test
    fun `forgetting a unit holding two links drops both`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED, DeviceLinkRole.SECOND),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = first,
                preferredImu = first,
                preferredHrm = strap,
                secondImu = first,
            ),
        )
    }

    // ---- what preferring a unit takes down -----------------------------------

    /**
     * A characterization of what `AutoConnectManager.setPreferredAndConnect`
     * already did as a bare `disconnect()`: the role's own link goes down so
     * `maintain` re-reads the new address, and nothing else is touched.
     */
    @Test
    fun `preferring a bar sensor drops the analysed link`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED),
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = DeviceLinkRole.ANALYSED,
                newlyPreferred = second,
                secondImu = null,
            ),
        )
    }

    /** The same characterization for the role the control was widened to. */
    @Test
    fun `preferring a strap drops the heart rate link`() {
        assertEquals(
            setOf(DeviceLinkRole.HEART_RATE),
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = DeviceLinkRole.HEART_RATE,
                newlyPreferred = strap,
                secondImu = null,
            ),
        )
    }

    /**
     * A characterization that must SURVIVE the change: the second link is only
     * in the way when it is holding the very unit being promoted. Taking it
     * down for any other promotion would cost the dual set its second stream
     * for no reason.
     */
    @Test
    fun `preferring a unit the second link is not holding leaves the second link alone`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED),
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = DeviceLinkRole.ANALYSED,
                newlyPreferred = second,
                secondImu = first,
            ),
        )
    }

    /**
     * A guard on the ARGUMENTS rather than a state the screen can produce, and
     * said as such: [DeviceLinkRole.SECOND] is a bar-sensor link, so a strap
     * address and the second IMU address cannot in practice be equal. It is
     * here because it is the only case that fails if the rule stops asking
     * which link the caller owns and drops the second one for every promotion.
     */
    @Test
    fun `preferring a strap never takes the second bar sensor link down`() {
        assertEquals(
            setOf(DeviceLinkRole.HEART_RATE),
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = DeviceLinkRole.HEART_RATE,
                newlyPreferred = strap,
                secondImu = strap,
            ),
        )
    }

    /**
     * DIFFERENTIAL: promoting the unit the SECOND link is already holding has
     * to take that link down, or two GATT clients end up on one WT901.
     *
     * `preferenceControl(ANALYSED, SECOND)` returns `Offer`, so the second
     * unit's row draws "Use this one for analysis" and this is one tap from
     * the Devices screen. Today's rule drops the analysed client alone and
     * leaves `secondaryImuAddress` naming the address the analysed link is
     * being pointed at, so `maintain` brings both links up on the same remote.
     *
     * What that costs is not cosmetic. `WitmotionStreamDecoder` holds one
     * `ArrayDeque` per client and the WT901's 20-byte frames carry no
     * checksum, so the app has no way to notice; and if both links stream, the
     * dual set's two raw archives are two recordings of ONE unit filed under
     * two labels. A wrong pixel is recoverable and a wrongly attributed
     * capture is not.
     *
     * `RecordViewModel.mirrorSensorSettings` re-derives the second address
     * from `SensorCapturePolicy.roster` and would heal this -- but only while
     * the Record back-stack entry is alive, and the tap happens on the Devices
     * screen, where it is not. There is no heal on the screen that owns the
     * control.
     */
    @Test
    fun `preferring the unit the second link is holding takes the second link down`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED, DeviceLinkRole.SECOND),
            DevicePairingPolicy.linksToDropOnPrefer(
                ownedLink = DeviceLinkRole.ANALYSED,
                newlyPreferred = second,
                secondImu = second,
            ),
        )
    }

    // ---- which row offers to move a preference -------------------------------

    /**
     * The unit a role's link is on states it; any other unit of that role
     * offers to take it.
     *
     * Never both and never neither: a row that offered to move a preference it
     * already holds would read as the app not knowing which unit it is on.
     */
    @Test
    fun `the analysed bar sensor says so and every other one offers to take over`() {
        assertEquals(
            PreferenceControl.InUse("Analysed · the set's numbers come from this unit"),
            DevicePairingPolicy.preferenceControl(DeviceLinkRole.ANALYSED, DeviceLinkRole.ANALYSED),
        )
        assertEquals(
            PreferenceControl.Offer("Use this one for analysis"),
            DevicePairingPolicy.preferenceControl(DeviceLinkRole.ANALYSED, DeviceLinkRole.NOT_LINKED),
        )
        assertEquals(
            PreferenceControl.Offer("Use this one for analysis"),
            DevicePairingPolicy.preferenceControl(DeviceLinkRole.ANALYSED, DeviceLinkRole.SECOND),
            "holding the second link is not being analysed",
        )
    }

    /**
     * DIFFERENTIAL, finding 6: a second heart-rate strap must be reachable
     * without forgetting the first.
     *
     * `DeviceRegistry.pair` keeps the existing preference for EVERY role, so
     * pairing a second strap no longer switches to it and
     * `AutoConnectManager.pairAndConnect` then declines to connect it. With
     * the control drawn for bar sensors only, the sole route to the new strap
     * was to forget the old one -- discoverable by accident, which is the
     * class of implicit behaviour #184 was filed to remove.
     *
     * The words are not a straight copy of the bar sensor's. "Connected strap"
     * was considered and refused: this rule is answered from which link the
     * address holds and knows nothing about whether that link is up, so it
     * would be asserting a connection while the chip beside it read
     * Disconnected.
     */
    @Test
    fun `a strap that is not the live one offers to become it`() {
        assertEquals(
            PreferenceControl.Offer("Use this strap for heart rate"),
            DevicePairingPolicy.preferenceControl(DeviceLinkRole.HEART_RATE, DeviceLinkRole.NOT_LINKED),
        )
    }

    /** DIFFERENTIAL, finding 6: and the strap the app is reading says so instead. */
    @Test
    fun `the strap the app reads says so rather than offering to move`() {
        assertEquals(
            PreferenceControl.InUse("Heart rate · readings come from this strap"),
            DevicePairingPolicy.preferenceControl(DeviceLinkRole.HEART_RATE, DeviceLinkRole.HEART_RATE),
        )
    }

    /**
     * A guard on the argument, not a UI decision: no device role owns the
     * second link or the absence of one, so nothing can be offered for them.
     */
    @Test
    fun `a link no device role owns has no control to draw`() {
        assertNull(DevicePairingPolicy.preferenceControl(DeviceLinkRole.SECOND, DeviceLinkRole.SECOND))
        assertNull(DevicePairingPolicy.preferenceControl(DeviceLinkRole.NOT_LINKED, DeviceLinkRole.NOT_LINKED))
    }

    // ---- telling two identical units apart -----------------------------------

    @Test
    fun `a unit's tag is the last two octets of its address`() {
        assertEquals("AA:01", DevicePairingPolicy.unitTag(first))
        assertEquals("BB:02", DevicePairingPolicy.unitTag(second))
        assertEquals("EE:FF", DevicePairingPolicy.unitTag("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `an address that is not colon-separated is not cut into someone else's tag`() {
        assertEquals("ABCDEF", DevicePairingPolicy.unitTag("abcdef"))
        assertEquals("", DevicePairingPolicy.unitTag(""))
    }

    @Test
    fun `no reading is no line, never the weakest bucket`() {
        assertNull(DevicePairingPolicy.signalLine(null))
        assertEquals("Signal strong (-40 dBm)", DevicePairingPolicy.signalLine(-40))
        assertEquals("Signal medium (-70 dBm)", DevicePairingPolicy.signalLine(-70))
        assertEquals("Signal weak (-95 dBm)", DevicePairingPolicy.signalLine(-95))
    }

    // ---- how far the setup has got -------------------------------------------

    @Test
    fun `the setup step is read off what is paired and what is labelled`() {
        assertEquals(DualSetupStep.NO_SENSOR, DualSensorSetup.step(emptyList(), emptyMap()))
        assertEquals(DualSetupStep.ONE_SENSOR, DualSensorSetup.step(listOf(first), emptyMap()))
        assertEquals(
            DualSetupStep.ONE_SENSOR,
            DualSensorSetup.step(listOf(first, first), mapOf(first to SensorRole.A)),
            "one unit listed twice is still one unit",
        )
        assertEquals(
            DualSetupStep.LABEL_BOTH,
            DualSensorSetup.step(listOf(first, second), mapOf(first to SensorRole.A)),
        )
        assertEquals(
            DualSetupStep.LABELS_COLLIDE,
            DualSensorSetup.step(listOf(first, second), mapOf(first to SensorRole.A, second to SensorRole.A)),
        )
        assertEquals(
            DualSetupStep.READY,
            DualSensorSetup.step(listOf(first, second), mapOf(first to SensorRole.A, second to SensorRole.B)),
        )
    }

    /**
     * Three paired units cannot all carry distinct labels, because there are
     * two labels. Reporting the collision beats picking two of the three and
     * reporting on those.
     */
    @Test
    fun `a third labelled unit is a collision rather than a silent choice of two`() {
        assertEquals(
            DualSetupStep.LABELS_COLLIDE,
            DualSensorSetup.step(
                listOf(first, second, strap),
                mapOf(first to SensorRole.A, second to SensorRole.B, strap to SensorRole.A),
            ),
        )
    }

    // ---- what the screens say ------------------------------------------------

    /**
     * The Record screen's three sentences, moved here unchanged. Asserted
     * against [DualShortfall.entries] so a fourth reason cannot be added
     * without a sentence being written for it.
     */
    @Test
    fun `the record screen's shortfall sentences are unchanged by the lift`() {
        assertEquals(
            "Fewer than two sensors are paired - this set will record one.",
            DualSensorSetup.recordLine(DualShortfall.ONE_SENSOR_PAIRED),
        )
        assertEquals(
            "Label both sensors A and B under Devices - this set will record one.",
            DualSensorSetup.recordLine(DualShortfall.ROLES_UNASSIGNED),
        )
        assertEquals(
            "Both sensors are labelled the same - fix it under Devices.",
            DualSensorSetup.recordLine(DualShortfall.ROLES_COLLIDE),
        )
        assertEquals(3, DualShortfall.entries.size, "a new shortfall needs a sentence here")
    }

    /**
     * The Devices screen names the state and the next action, at the steps
     * where there is one.
     *
     * The one sentence explaining that labelling is required lives on the
     * RECORD screen and draws only when a plan declares two sensors, so the
     * lifter doing the pairing has never been told. These are the same facts
     * in this screen's own voice -- "under Devices" is not a useful locator
     * when you are already there.
     *
     * Silent at the two steps with nothing to fix. A single-sensor setup is
     * the ordinary one, for every exercise, and nagging it about a second unit
     * it does not have would make the line noise.
     */
    @Test
    fun `the devices screen names the step and the next action`() {
        assertNull(DualSensorSetup.devicesLine(DualSetupStep.NO_SENSOR))
        assertNull(DualSensorSetup.devicesLine(DualSetupStep.ONE_SENSOR), "one sensor is the ordinary setup")
        assertEquals(
            "Label both sensors A and B - a set asking for two records only one until they carry " +
                "different labels.",
            DualSensorSetup.devicesLine(DualSetupStep.LABEL_BOTH),
        )
        assertEquals(
            "Both sensors are labelled the same - give one of them the other label.",
            DualSensorSetup.devicesLine(DualSetupStep.LABELS_COLLIDE),
        )
        assertEquals(
            "Both sensors are labelled. A set asking for two will record both streams.",
            DualSensorSetup.devicesLine(DualSetupStep.READY),
        )
    }

    /**
     * The by-elimination ritual comes off the KDoc and onto the screen, and
     * only where it is needed.
     *
     * It says to compare the two rows' live signal rather than to reason about
     * which single row is green, because with the preference no longer moving
     * there is no longer a moment when exactly one row is green. It claims
     * nothing about a unit that is not advertising: a row with no reading
     * shows no signal line at all.
     */
    @Test
    fun `the identify hint is offered exactly where two units must be told apart`() {
        val hint =
            "Two units can advertise the same name. Scan below and hold one against the phone: " +
                "the row whose signal reads strong is that unit."
        assertEquals(hint, DualSensorSetup.identifyHint(DualSetupStep.LABEL_BOTH))
        assertEquals(hint, DualSensorSetup.identifyHint(DualSetupStep.LABELS_COLLIDE))
        assertNull(DualSensorSetup.identifyHint(DualSetupStep.NO_SENSOR))
        assertNull(DualSensorSetup.identifyHint(DualSetupStep.ONE_SENSOR))
        assertNull(DualSensorSetup.identifyHint(DualSetupStep.READY), "labelled units are told apart by their labels")
    }
}
