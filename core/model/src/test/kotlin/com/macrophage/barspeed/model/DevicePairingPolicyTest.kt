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
     * another still paired would leave the role with NO preferred device, the
     * analysed link idling on a null address, and no way back that does not
     * involve forgetting the survivor too.
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
