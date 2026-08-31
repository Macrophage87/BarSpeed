package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    private val third = "DD:DD:DD:DD:DD:04"

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
                remainingImu = listOf(first),
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
                remainingImu = listOf(second),
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
                remainingImu = listOf(first, second),
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
                remainingImu = listOf(first),
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
                remainingImu = listOf(second),
            ),
        )
    }

    /**
     * A characterization that must SURVIVE the change: the second link only
     * comes down for a promotion when it is holding the very unit being
     * promoted. `preferredAfterForget` takes the FIRST survivor, so with a
     * third bar sensor ahead of it in the registry the second link's unit is
     * not the one promoted, and taking that link down would cost the next
     * dual set its second stream for no reason.
     */
    @Test
    fun `forgetting the analysed unit and promoting a third leaves the second link alone`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = first,
                preferredImu = first,
                preferredHrm = null,
                secondImu = second,
                remainingImu = listOf(third, second),
            ),
        )
    }

    /**
     * A characterization that must SURVIVE the change, and it is the one that
     * fails if the rule compares a null second address against a null
     * promotion. Forgetting the only bar sensor promotes nothing, and "no
     * second link" must not come out equal to "nothing was promoted" -- that
     * is absence rendered as a value, and it would drop a link that is not
     * there and null an address that is already null.
     */
    @Test
    fun `forgetting the only bar sensor with no second link drops the analysed link alone`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = first,
                preferredImu = first,
                preferredHrm = null,
                secondImu = null,
                remainingImu = emptyList(),
            ),
        )
    }

    /**
     * DIFFERENTIAL, round 3 finding 20: forgetting the analysed unit promotes
     * the unit the SECOND link is already holding, and that link has to come
     * down with the promotion.
     *
     * The forget-button twin of `preferring the unit the second link is
     * holding takes the second link down`, and the same two-clients-on-one-
     * WT901 state by a different tap. `DeviceRegistry.forget` writes the
     * survivor into `preferred_imu`; today's rule returns `{ANALYSED}` alone,
     * so `AutoConnectManager` drops `imuClient`, `maintain` wakes and re-reads
     * the promoted address, and `secondaryImuAddress` still names it -- both
     * links come up on one bar sensor.
     *
     * What that costs is not cosmetic. `WitmotionStreamDecoder` holds one
     * `ArrayDeque` per client and the WT901's 20-byte frames carry no
     * checksum, so nothing in the app can notice; if both links stream, the
     * next dual set's two raw archives are two recordings of ONE unit filed
     * under two labels. A wrong pixel is recoverable and a wrongly attributed
     * capture is not.
     *
     * Reachable in one tap: the Forget button is drawn on every paired row
     * (`DevicesScreen`), and `RecordViewModel.mirrorSensorSettings` -- the only
     * thing that re-derives the second address -- lives on the Record
     * back-stack entry, not the screen the tap happens on.
     */
    @Test
    fun `forgetting the analysed unit promotes the unit the second link is holding and takes that link down`() {
        assertEquals(
            setOf(DeviceLinkRole.ANALYSED, DeviceLinkRole.SECOND),
            DevicePairingPolicy.linksToDropOnForget(
                forgotten = first,
                preferredImu = first,
                preferredHrm = strap,
                secondImu = second,
                remainingImu = listOf(second),
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

    // ---- which unit each link should hold ------------------------------------

    /**
     * A ready pair points the two links at the two units, analysed first.
     *
     * New symbol, so red-before-green is not available for it and no pretence
     * is made that it was: nothing here failed before the function existed.
     * What stands in for it is mutation, and the mutations run are listed in
     * the commit body with their measured totals.
     */
    @Test
    fun `a ready pair names one unit for each link`() {
        val targets =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first, second),
                preferredImuAddress = second,
                roleByAddress = mapOf(first to SensorRole.A, second to SensorRole.B),
            )

        assertEquals(second, targets.analysed, "the preferred unit holds the analysed link")
        assertEquals(first, targets.second)
    }

    /**
     * One paired unit arms no second link, and that is not a failure.
     *
     * The ordinary setup, for every exercise. A null [ImuLinkTargets.second]
     * is what `DeviceLinkRole.NOT_LINKED` draws, and drawing a disconnected
     * chip there would report a link failure where there is no link (#184).
     */
    @Test
    fun `one paired unit leaves the second link pointed at nothing`() {
        val targets =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A),
            )

        assertEquals(first, targets.analysed)
        assertNull(targets.second)
    }

    /**
     * A pair that cannot be told apart arms no second link.
     *
     * Both halves of the not-ready pair: an unlabelled unit and two units
     * carrying the same label. Arming either would put a stream in the
     * archive under a label that does not identify the unit it came from,
     * which is worse than capturing one stream.
     */
    @Test
    fun `an unlabelled or colliding pair arms no second link`() {
        val unlabelled =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first, second),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A),
            )
        val collide =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first, second),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A, second to SensorRole.A),
            )

        assertEquals(first, unlabelled.analysed, "the analysed link is unaffected by the other unit's label")
        assertNull(unlabelled.second)
        assertEquals(first, collide.analysed)
        assertNull(collide.second)
    }

    /**
     * A preference naming a unit that is not paired holds no link.
     *
     * `DeviceRegistry.forget` promotes a survivor, so this state is
     * short-lived in practice -- but naming a forgotten address is exactly
     * what the analysed link must not do, and answering the address anyway
     * would have the second slot chosen against a unit that is not there.
     */
    @Test
    fun `a preference naming a unit that is not paired names no link at all`() {
        val targets =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(second),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A, second to SensorRole.B),
            )

        assertNull(targets.analysed)
        assertNull(targets.second, "with no analysed unit there is no second unit either")
    }

    /**
     * No arrangement ever points both links at one remote.
     *
     * The invariant `SensorCapture.kt` states, asked of this function over
     * every arrangement of two addresses, three label assignments each and
     * three preferences -- rather than of the one arrangement that motivated
     * it. Two clients on one WT901 is the state #183/#184 found twice: the
     * frames carry no checksum and each client has its own decoder buffer, so
     * a dual set's two archives would be two recordings of one unit filed
     * under two labels, and nothing in the app could tell.
     */
    @Test
    fun `no arrangement points both links at the same unit`() {
        val options = listOf(SensorRole.A, SensorRole.B, null)
        listOf(first, second, null).forEach { preferred ->
            options.forEach { one ->
                options.forEach { two ->
                    val roles =
                        listOfNotNull(
                            one?.let { first to it },
                            two?.let { second to it },
                        ).toMap()
                    val targets =
                        DevicePairingPolicy.imuLinkTargets(listOf(first, second), preferred, roles)
                    if (targets.second != null) {
                        assertNotEquals(
                            targets.analysed,
                            targets.second,
                            "preferred=$preferred roles=$roles put both links on one unit",
                        )
                    }
                }
            }
        }
    }

    /**
     * DIFFERENTIAL, issue #192. Failed at
     * d3348808d831f2c16e288b8772d47fca111fc921 (CI run 33331307023,
     * conclusion failure).
     *
     * The link half of the same decision: with three units paired, no second
     * link comes up at all. At d334880 `roster` named one positionally and
     * this function returned it, so the Devices screen -- once it armed the
     * link at all -- would have brought a link up on whichever unit the
     * registry happened to list first, and a set asking for two would have
     * filed its stream under a label another paired unit also carried.
     *
     * Two links, two labels: a third paired unit is a setup to fix, not a
     * candidate to choose between.
     */
    @Test
    fun `a third paired unit arms no second link at all`() {
        val unlabelledThird =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first, second, third),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A, second to SensorRole.B),
            )
        val labelledThird =
            DevicePairingPolicy.imuLinkTargets(
                pairedImuAddresses = listOf(first, second, third),
                preferredImuAddress = first,
                roleByAddress = mapOf(first to SensorRole.A, second to SensorRole.B, third to SensorRole.B),
            )

        assertEquals(first, unlabelledThird.analysed, "the analysed link still holds the preferred unit")
        assertNull(unlabelledThird.second)
        assertEquals(first, labelledThird.analysed)
        assertNull(labelledThird.second)
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

    /**
     * A third paired unit with no label leaves the setup at [DualSetupStep.LABEL_BOTH].
     *
     * The other half of the three-unit case, pinned before #192 changes what
     * a third unit costs: the sibling above covers three units that all carry
     * a label, and this covers the one that does not. Both matter because
     * `SensorCapturePolicy.roster` is about to be gated on this function's
     * answer, so what it says about three units becomes what a set of three
     * captures.
     */
    @Test
    fun `a third unlabelled unit leaves the setup asking for labels`() {
        assertEquals(
            DualSetupStep.LABEL_BOTH,
            DualSensorSetup.step(
                listOf(first, second, third),
                mapOf(first to SensorRole.A, second to SensorRole.B),
            ),
        )
    }

    /**
     * No arrangement of three paired units is ever [DualSetupStep.READY].
     *
     * Exhaustive over every assignment of A, B and "no label" to three paired
     * addresses -- 27 arrangements -- rather than over the two that happen to
     * be interesting, because this is the premise the two-link rule rests on:
     * [SensorRole] has two entries, so three units cannot carry three
     * distinct labels, and READY therefore means EXACTLY two paired units
     * carrying different labels. A third [SensorRole] would break that
     * silently, and this is what would say so.
     */
    @Test
    fun `three paired units are never ready, whatever they are labelled`() {
        val options = listOf(SensorRole.A, SensorRole.B, null)
        val addresses = listOf(first, second, third)
        options.forEach { one ->
            options.forEach { two ->
                options.forEach { three ->
                    val roles =
                        listOf(one, two, three)
                            .mapIndexedNotNull { i, role -> role?.let { addresses[i] to it } }
                            .toMap()
                    val step = DualSensorSetup.step(addresses, roles)
                    assertNotEquals(DualSetupStep.READY, step, "three units cannot be ready: $roles")
                }
            }
        }
    }

    // ---- what the screens say ------------------------------------------------

    /**
     * DIFFERENTIAL, issue #198. The Record screen has TWO shortfall sentences,
     * because there are two things left that can be in the way.
     *
     * "Fewer than two sensors are paired - this set will record one." was the
     * third and is gone with ONE_SENSOR_PAIRED. It was a sentence about a
     * request, and there is no request: owning one bar sensor is the ordinary
     * setup, not a degraded two, and telling a one-sensor lifter about it
     * before every set was the app reporting a gap where there is none.
     *
     * The two that survive are not dissolved with it and their meaning shifts
     * rather than going: they no longer say "you asked for two and cannot have
     * it", they say two units are connected and the app cannot tell them
     * apart, so it recorded one. A coach has to be able to tell that from
     * having owned one sensor.
     *
     * Still asserted against [DualShortfall.entries] so a third reason cannot
     * be added without a sentence being written for it.
     */
    @Test
    fun `the record screen's shortfall sentences are unchanged by the lift`() {
        assertEquals(
            "Label both sensors A and B under Devices - this set will record one.",
            DualSensorSetup.recordLine(DualShortfall.ROLES_UNASSIGNED),
        )
        assertEquals(
            "Both sensors are labelled the same - fix it under Devices.",
            DualSensorSetup.recordLine(DualShortfall.ROLES_COLLIDE),
        )
        assertEquals(2, DualShortfall.entries.size, "a new shortfall needs a sentence here")
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
