package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rule about how many accelerometers a set captures with, issue #156.
 *
 * These are NEW symbols, so red-before-green is not available for them and no
 * pretence is made that it was: nothing here failed before the function
 * existed, because there was nothing to call. What stands in for it is
 * mutation: each of the rules below was broken by hand and the test named in
 * the commit body reddened. The mutations run are listed there with their
 * measured totals.
 *
 * The seam itself is why this file can exist at all. `:core:ble` has no test
 * source set and `:app` has one file over one pure function, so every decision
 * about which stream is which would otherwise live where nothing can run
 * against it. This is the "extract a pure seam and pin it" move made before
 * the first defect rather than after the third.
 */
class SensorCapturePolicyTest {
    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"
    private val c = "CC:CC:CC:CC:CC:CC"

    // ---- the counts ----------------------------------------------------------

    @Test
    fun `the default is one sensor and the ceiling is two`() {
        assertEquals(1, SensorCapturePolicy.DEFAULT_COUNT)
        assertEquals(1, SensorCapturePolicy.clamp(0))
        assertEquals(1, SensorCapturePolicy.clamp(1))
        assertEquals(2, SensorCapturePolicy.clamp(2))
        assertEquals(2, SensorCapturePolicy.clamp(3))
        assertEquals(1, SensorCapturePolicy.clamp(-4))
    }

    /**
     * The lifter's adjustment beats the plan's declaration, and both beat the
     * default. [LeadInPolicy.resolve]'s precedence, because it is the same kind
     * of decision and a second ordering would be a second rule.
     */
    @Test
    fun `the adjustment wins over the declaration and the declaration over the default`() {
        assertEquals(1, SensorCapturePolicy.resolve(declared = null, override = null))
        assertEquals(2, SensorCapturePolicy.resolve(declared = 2, override = null))
        assertEquals(1, SensorCapturePolicy.resolve(declared = 2, override = 1))
        assertEquals(2, SensorCapturePolicy.resolve(declared = null, override = 2))
        assertEquals(2, SensorCapturePolicy.resolve(declared = 1, override = 9))
    }

    /**
     * The planned half of the pair reads the plan alone.
     *
     * If it read the override too, the two halves would be equal by
     * construction and the export could never show that the lifter changed
     * anything -- the retrofit #151 had to undo, avoided here by writing the
     * pair on the first commit.
     */
    @Test
    fun `the planned count is the plan's declaration and never the adjustment`() {
        assertEquals(1, SensorCapturePolicy.planned(null))
        assertEquals(2, SensorCapturePolicy.planned(2))
        assertEquals(2, SensorCapturePolicy.planned(7))
    }

    // ---- the wire vocabulary -------------------------------------------------

    @Test
    fun `roles go onto the wire lowercased and come back off it by name`() {
        assertEquals("a", SensorCapturePolicy.wireOf(SensorRole.A))
        assertEquals("b", SensorCapturePolicy.wireOf(SensorRole.B))
        assertEquals(SensorRole.A, SensorCapturePolicy.roleFromWire("a"))
        assertEquals(SensorRole.B, SensorCapturePolicy.roleFromWire("B"))
    }

    /**
     * An unknown role reads as absent, never as a default and never as a throw.
     *
     * A defaulted `A` would relabel the OTHER unit's stream, which is the
     * absence-rendered-as-a-value class aimed straight at provenance; a throw
     * would come out of a decode with no catch above it.
     */
    @Test
    fun `a role this build does not know reads as absent`() {
        assertNull(SensorCapturePolicy.roleFromWire(null))
        assertNull(SensorCapturePolicy.roleFromWire(""))
        assertNull(SensorCapturePolicy.roleFromWire("c"))
        assertNull(SensorCapturePolicy.roleFromWire("left"))
    }

    // ---- the roster ----------------------------------------------------------

    @Test
    fun `a single-sensor request arms no role at all`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 1,
            )

        assertEquals(emptyList(), roster.expected, "a one-sensor set must put no role on its stream")
        assertNull(roster.analysed)
        assertNull(roster.secondary)
        assertNull(roster.shortfall, "one sensor asked for and one armed is not a shortfall")
        assertTrue(!roster.isDual)
    }

    @Test
    fun `two paired and labelled units arm both roles, the preferred one analysed`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = b,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 2,
            )

        assertTrue(roster.isDual)
        assertEquals(listOf(SensorRole.B, SensorRole.A), roster.expected, "the analysed role leads the list")
        assertEquals(SensorRole.B, roster.analysed, "the preferred address decides which stream is analysed")
        assertEquals(SensorRole.A, roster.secondary)
        assertEquals(a, roster.secondaryAddress)
        assertNull(roster.shortfall)
    }

    /**
     * A second unit with no label is refused rather than given one.
     *
     * A positional default -- "the preferred one is A" -- was the obvious
     * alternative and is what this pins against. The preferred address is
     * movable at any time -- by "Use this one for analysis"
     * (`DeviceRegistry.setPreferred`) and by forgetting the analysed unit --
     * so the meaning of A would change under the lifter, and every capture
     * either side of that moment would be labelled consistently and wrongly.
     * The label has to be a property of the MAC or it is not a label.
     *
     * An earlier draft rested this on `DeviceRegistry.pair` making every newly
     * paired device its role's preferred address. That stopped being true in
     * the same branch; the premise is deleted rather than reworded, and the
     * conclusion is unaffected.
     */
    @Test
    fun `an unlabelled second unit downgrades to one sensor rather than being labelled`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A),
                requestedCount = 2,
            )

        assertTrue(!roster.isDual)
        assertEquals(emptyList(), roster.expected)
        assertEquals(DualShortfall.ROLES_UNASSIGNED, roster.shortfall)
        assertEquals(listOf(b), roster.unassigned, "the screen has to be able to say which unit to label")
    }

    @Test
    fun `two units labelled the same are refused, because neither stream could be told from the other`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.A),
                requestedCount = 2,
            )

        assertEquals(DualShortfall.ROLES_COLLIDE, roster.shortfall)
        assertEquals(emptyList(), roster.expected)
    }

    @Test
    fun `one paired unit cannot arm two, and says which gap it is`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A),
                requestedCount = 2,
            )

        assertEquals(DualShortfall.ONE_SENSOR_PAIRED, roster.shortfall)
        assertEquals(emptyList(), roster.expected)
    }

    /**
     * A preferred address that is not among the paired ones is not a second
     * sensor.
     *
     * The registry can hold a `preferred_imu` for a device the lifter has since
     * forgotten; treating that stale address as one of the pair would arm a
     * link to nothing and label the surviving unit as the secondary.
     */
    @Test
    fun `a stale preferred address does not make one unit into two`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(b),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 2,
            )

        assertEquals(DualShortfall.ONE_SENSOR_PAIRED, roster.shortfall)
    }

    /**
     * Every shortfall names a null [SensorRoster.secondaryAddress], and the
     * cases are checked against [DualShortfall.entries] so a fourth reason
     * cannot be added without this being re-decided.
     *
     * Pinned as a characterization before #183/#184 touch the Devices screen,
     * because it is the fact that screen's honesty rests on: the second link
     * is handed `secondaryAddress` and nothing else, so under any shortfall it
     * is pointed at no device at all. A row that renders a `Disconnected` chip
     * in that state is reporting a link failure where there is no link --
     * absence rendered as a value -- and the fix depends on this staying true.
     */
    @Test
    fun `no shortfall ever names a second address for the second link to hold`() {
        val cases =
            mapOf(
                DualShortfall.ONE_SENSOR_PAIRED to
                    SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A), 2),
                DualShortfall.ROLES_UNASSIGNED to
                    SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A), 2),
                DualShortfall.ROLES_COLLIDE to
                    SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.A), 2),
            )

        assertEquals(DualShortfall.entries.toSet(), cases.keys, "every shortfall has to be exercised here")
        cases.forEach { (shortfall, roster) ->
            assertEquals(shortfall, roster.shortfall)
            assertNull(roster.secondaryAddress, "$shortfall must leave the second link pointed at nothing")
            assertTrue(!roster.isDual)
        }
    }

    /**
     * DIFFERENTIAL, issue #192. Fails against the rule shipped today.
     *
     * A third paired bar sensor has nowhere to go: there are two links and
     * two labels. Today the second one is chosen positionally -- the first
     * paired address that is not the analysed one -- so which of three units
     * gets armed depends on the order `DeviceRegistry` happens to keep, and
     * an unlabelled third unit sitting later in that list is silently skipped
     * while the set arms dual.
     *
     * That is the positional default this file's own
     * `an unlabelled second unit downgrades to one sensor rather than being
     * labelled` refuses for the pair; it survived for the trio only because
     * nobody had three paired. The rule stated instead: dual arms only from
     * a READY setup, which `DualSensorSetup.step` defines and which three
     * units can never be -- pinned exhaustively in DevicePairingPolicyTest.
     * A third unit means the lifter is asked to sort the labels out and the
     * set records one sensor, which is what a shortfall is for.
     */
    @Test
    fun `a third paired unit is not silently skipped in favour of an armed pair`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b, c),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 2,
            )

        assertEquals(DualShortfall.ROLES_UNASSIGNED, roster.shortfall, "the third unit carries no label")
        assertNull(roster.secondaryAddress, "no second link is armed while a paired unit is unlabelled")
        assertTrue(!roster.isDual)
        assertEquals(listOf(c), roster.unassigned, "the screen is told which unit to label")
    }

    /**
     * DIFFERENTIAL, issue #192. Fails against the rule shipped today.
     *
     * Three paired units that all carry a label must collide, because there
     * are two labels. Today the collision check only compares the two units
     * the positional rule picked, so a third unit duplicating the second's
     * label passes unnoticed and the set arms dual with two units the lifter
     * cannot tell apart -- while the Devices screen, which reads
     * `DualSensorSetup.step`, is telling them the labels collide. The screen
     * and the capture disagreed about the same three units.
     */
    @Test
    fun `three labelled units collide rather than arming the two that happen to differ`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b, c),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B, c to SensorRole.B),
                requestedCount = 2,
            )

        assertEquals(DualShortfall.ROLES_COLLIDE, roster.shortfall)
        assertNull(roster.secondaryAddress)
    }

    // ---- what gets recorded --------------------------------------------------

    @Test
    fun `an ordinary one-sensor set records no declaration at all`() {
        val roster = SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A), requestedCount = 1)

        assertNull(
            SensorCapturePolicy.recorded(plannedCount = 1, roster = roster),
            "a plain one-sensor set must stay byte-identical to what the app has always written",
        )
    }

    /**
     * A set that asked for two and armed one records the ask.
     *
     * This is the whole reason a declaration is stored beside the role column:
     * what arrived is observable from the streams, what was EXPECTED is
     * observable from nothing, and without this a dual-armed set that captured
     * one stream is indistinguishable from a single-sensor set forever.
     */
    @Test
    fun `a shortfall is recorded, with an empty role list and the count that ran`() {
        val roster = SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A), requestedCount = 2)

        val recorded = SensorCapturePolicy.recorded(plannedCount = 2, roster = roster)

        assertEquals(2, recorded?.plannedCount)
        assertEquals(1, recorded?.count, "one sensor ran, and the count says so rather than the role list")
        assertEquals(emptyList(), recorded?.expected)
        assertNull(recorded?.analysed)
        assertNull(recorded?.secondaryRole)
    }

    @Test
    fun `a dual set records both roles, the analysed one and its partner`() {
        val roster =
            SensorCapturePolicy.roster(
                listOf(a, b),
                a,
                mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 2,
            )

        val recorded = SensorCapturePolicy.recorded(plannedCount = 2, roster = roster)

        assertEquals(2, recorded?.plannedCount)
        assertEquals(2, recorded?.count)
        assertEquals(listOf(SensorRole.A, SensorRole.B), recorded?.expected)
        assertEquals(SensorRole.A, recorded?.analysed)
        assertEquals(SensorRole.B, recorded?.secondaryRole)
    }

    /**
     * A plan that asked for one and a lifter who armed two is recorded too.
     *
     * The pair runs in both directions, which is what makes it a pair rather
     * than a note about the plan.
     */
    @Test
    fun `an upward adjustment is recorded with the plan's own figure beside it`() {
        val roster =
            SensorCapturePolicy.roster(
                listOf(a, b),
                a,
                mapOf(a to SensorRole.A, b to SensorRole.B),
                requestedCount = 2,
            )

        val recorded = SensorCapturePolicy.recorded(plannedCount = 1, roster = roster)

        assertEquals(1, recorded?.plannedCount)
        assertEquals(2, recorded?.count)
    }

    // ---- what arrived --------------------------------------------------------

    @Test
    fun `present is the armed roles that produced a stream, in the armed order`() {
        val expected = listOf(SensorRole.B, SensorRole.A)

        assertEquals(expected, SensorCapturePolicy.present(expected, setOf(SensorRole.A, SensorRole.B)))
        assertEquals(listOf(SensorRole.A), SensorCapturePolicy.present(expected, setOf(SensorRole.A)))
        assertEquals(emptyList(), SensorCapturePolicy.present(expected, emptySet()))
    }

    /**
     * A stream carrying a role the set was never armed for is not reported as
     * present.
     *
     * Nothing produces that today; what it would mean is a row whose role
     * column disagrees with the set's own declaration, and the declaration is
     * the one of the two that was made deliberately.
     */
    @Test
    fun `a role that was never armed is not present however many streams carry it`() {
        assertEquals(
            listOf(SensorRole.A),
            SensorCapturePolicy.present(listOf(SensorRole.A), setOf(SensorRole.A, SensorRole.B)),
        )
    }
}
