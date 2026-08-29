package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pairing, preferring and what the Devices screen says, issue #184.
 *
 * The tests marked CHARACTERIZATION pin what the app does TODAY. They are
 * deleted in the commit that adds the differentials replacing them.
 */
class DevicePairingPolicyTest {
    private val first = "AA:AA:AA:AA:AA:01"
    private val second = "BB:BB:BB:BB:BB:02"
    private val strap = "CC:CC:CC:CC:CC:03"

    // ---- which unit is preferred ---------------------------------------------

    /** CHARACTERIZATION. Today pairing anything makes it its role's preferred device. */
    @Test
    fun `today pairing a second unit takes preferred off the first`() {
        assertEquals(
            second,
            DevicePairingPolicy.preferredAfterPairing(
                currentPreferred = first,
                pairedOfRole = setOf(first),
                justPaired = second,
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

    /** CHARACTERIZATION. Today forgetting the preferred unit leaves the role with none. */
    @Test
    fun `today forgetting the preferred unit promotes nothing`() {
        assertNull(
            DevicePairingPolicy.preferredAfterForget(
                currentPreferred = first,
                forgotten = first,
                remainingOfRole = listOf(second),
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

    /** CHARACTERIZATION. Today the Devices screen says nothing about any of this. */
    @Test
    fun `today the devices screen says nothing at any step`() {
        DualSetupStep.entries.forEach { step ->
            assertNull(DualSensorSetup.devicesLine(step), "$step")
            assertNull(DualSensorSetup.identifyHint(step), "$step")
        }
    }
}
