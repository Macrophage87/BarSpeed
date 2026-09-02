package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
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
 * source set at all, and no test on the CI path reaches `:app`'s Android
 * classes, so every decision about which stream is which would otherwise live
 * where nothing can run against it. This is the "extract a pure seam and pin it" move made before
 * the first defect rather than after the third.
 */
class SensorCapturePolicyTest {
    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"
    private val c = "CC:CC:CC:CC:CC:CC"

    // ---- the counts ----------------------------------------------------------

    /**
     * One stream is what a set records unless the hardware says otherwise, and
     * two is the ceiling.
     *
     * The clamp, the plan-versus-adjustment precedence and the planned half of
     * the pair used to be asserted here and are gone with the pins that named
     * them: #198 takes every count out of the capture decision, so there is no
     * requested figure left to clamp or to resolve. What survives is the pair
     * of constants the roster and the record flow still read.
     */
    @Test
    fun `the default is one sensor and the ceiling is two`() {
        assertEquals(1, SensorCapturePolicy.DEFAULT_COUNT)
        assertEquals(2, SensorCapturePolicy.MAX_COUNT)
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

    /**
     * DIFFERENTIAL, issue #198. One sensor is the ordinary case, not a
     * degraded two.
     *
     * `a single-sensor request arms no role at all` stood here and is gone
     * with the request it was about. What replaces it is the other half of the
     * same fact: owning one bar sensor, or none, is not a shortfall against
     * anything, because nothing was asked for. Today both states answer
     * ONE_SENSOR_PAIRED -- a name that was already wrong for a lifter who owns
     * one unit and is drawn on the Record screen as "Fewer than two sensors
     * are paired", a sentence about a gap that is not one.
     */
    @Test
    fun `one paired unit is the ordinary case rather than a shortfall`() {
        val one = SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A))

        assertNull(one.shortfall, "owning one sensor is not a gap in a setup")
        assertEquals(emptyList(), one.expected, "a single stream carries no role")
        assertNull(one.analysed)
        assertNull(one.secondary)
        assertTrue(!one.isDual)

        val none = SensorCapturePolicy.roster(emptyList(), null, emptyMap())

        assertNull(none.shortfall, "no sensor at all is not a gap either")
        assertTrue(!none.isDual)
    }

    @Test
    fun `two paired and labelled units arm both roles, the preferred one analysed`() {
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = b,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
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
            )

        assertEquals(DualShortfall.ROLES_COLLIDE, roster.shortfall)
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
            )

        assertNull(roster.secondary, "a forgotten unit is not the second half of a pair")
        assertTrue(!roster.isDual)
        assertNull(
            roster.shortfall,
            "one unit paired is one unit paired, whatever a stale preference says about another",
        )
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
                DualShortfall.ROLES_UNASSIGNED to
                    SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A)),
                DualShortfall.ROLES_COLLIDE to
                    SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.A)),
            )

        assertEquals(DualShortfall.entries.toSet(), cases.keys, "every shortfall has to be exercised here")
        cases.forEach { (shortfall, roster) ->
            assertEquals(shortfall, roster.shortfall)
            assertNull(roster.secondaryAddress, "$shortfall must leave the second link pointed at nothing")
            assertTrue(!roster.isDual)
        }
    }

    /**
     * DIFFERENTIAL, issue #192. Failed at
     * d3348808d831f2c16e288b8772d47fca111fc921 (CI run 33331307023,
     * conclusion failure).
     *
     * A third paired bar sensor has nowhere to go: there are two links and
     * two labels. At d334880 the second one was chosen positionally -- the
     * first paired address that is not the analysed one -- so which of three
     * units
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
            )

        assertEquals(DualShortfall.ROLES_UNASSIGNED, roster.shortfall, "the third unit carries no label")
        assertNull(roster.secondaryAddress, "no second link is armed while a paired unit is unlabelled")
        assertTrue(!roster.isDual)
        assertEquals(listOf(c), roster.unassigned, "the screen is told which unit to label")
    }

    /**
     * DIFFERENTIAL, issue #192. Failed at
     * d3348808d831f2c16e288b8772d47fca111fc921 (CI run 33331307023,
     * conclusion failure).
     *
     * Three paired units that all carry a label must collide, because there
     * are two labels. At d334880 the collision check only compared the two
     * units the positional rule picked, so a third unit duplicating the
     * second's label passed unnoticed and the set armed dual with two units
     * the lifter could not tell apart -- while the Devices screen, which reads
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
            )

        assertEquals(DualShortfall.ROLES_COLLIDE, roster.shortfall)
        assertNull(roster.secondaryAddress)
    }

    // ---- what gets recorded --------------------------------------------------

    @Test
    fun `an ordinary one-sensor set records no declaration at all`() {
        val roster = SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A))

        assertNull(
            SensorCapturePolicy.recorded(roster),
            "a plain one-sensor set must stay byte-identical to what the app has always written",
        )
    }

    /**
     * DIFFERENTIAL, issue #198. Two units the app cannot tell apart record one
     * stream, and the set says so.
     *
     * This replaces `a shortfall is recorded, with an empty role list and the
     * count that ran`, which recorded the same state and read it off the plan:
     * the declaration existed because plannedCount was 2 and count was 1. With
     * nothing declared there is no such pair, and without a stored reason the
     * two facts a coach has to be able to tell apart -- "there was one sensor"
     * and "there were two and one was unusable" -- collapse into one row that
     * says neither.
     *
     * What arrived is observable from the streams; what was in the way is
     * observable from nothing, which is why it is stored rather than derived.
     */
    @Test
    fun `a pair the app cannot tell apart is recorded as one stream and a named gap`() {
        val unlabelled = SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A))

        val recorded = SensorCapturePolicy.recorded(unlabelled)

        assertEquals(
            DualShortfall.ROLES_UNASSIGNED,
            recorded?.shortfall,
            "two paired units the app cannot label are not a one-sensor set",
        )
        assertEquals(1, recorded?.count, "one sensor ran, and the count says so rather than the role list")
        assertEquals(emptyList(), recorded?.expected)
        assertNull(recorded?.analysed)
        assertNull(recorded?.secondaryRole)
    }

    /**
     * DIFFERENTIAL, issue #198. The other surviving gap is recorded the same
     * way and is not folded into the first.
     *
     * Two units carrying one label and two units carrying no label are
     * different things to go and fix, which is why the enum has kept them
     * apart on screen since #184. A stored reason collapsing them would be the
     * screen and the archive disagreeing about one set.
     */
    @Test
    fun `two units labelled the same are recorded as a collision, not as one sensor`() {
        val collide =
            SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.A))

        val recorded = SensorCapturePolicy.recorded(collide)

        assertEquals(DualShortfall.ROLES_COLLIDE, recorded?.shortfall)
        assertEquals(1, recorded?.count)
        assertEquals(emptyList(), recorded?.expected)
    }

    @Test
    fun `a dual set records both roles, the analysed one and its partner`() {
        val roster =
            SensorCapturePolicy.roster(
                listOf(a, b),
                a,
                mapOf(a to SensorRole.A, b to SensorRole.B),
            )

        val recorded = SensorCapturePolicy.recorded(roster)

        assertEquals(2, recorded?.count)
        assertEquals(listOf(SensorRole.A, SensorRole.B), recorded?.expected)
        assertEquals(SensorRole.A, recorded?.analysed)
        assertEquals(SensorRole.B, recorded?.secondaryRole)
        assertNull(recorded?.shortfall, "nothing was in the way of a set that armed both")
    }

    /**
     * DIFFERENTIAL, issue #198. Nothing in the stored declaration says what a
     * plan asked for, because no plan asks any more.
     *
     * Asserted against the serialized column rather than against the data
     * class, because absence is the whole claim: a Kotlin field cannot be
     * checked for not existing, and what a reader of an old phone's database
     * meets is the JSON. `an upward adjustment is recorded with the plan's own
     * figure beside it` stood here and is gone with the figure.
     */
    @Test
    fun `the stored declaration carries no planned count`() {
        val roster =
            SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.B))
        val recorded = SensorCapturePolicy.recorded(roster)!!

        val stored = Json.encodeToString(RecordedSensors.serializer(), recorded)

        assertTrue(
            "plannedCount" !in stored,
            "the set row still publishes a planned count nobody planned: $stored",
        )
        assertTrue("count" in stored, "the armed count is still written: $stored")
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

    // ---- which stream the DSP is pointed at ----------------------------------

    /**
     * DIFFERENTIAL, issue #207. The analysed role is a role that STREAMED, and
     * the document says when that is not the role the set armed.
     *
     * Field-36 is the second case: two units paired and labelled, the
     * preference on b, b in a bag, and 13 of 14 sets published `summary: {}`
     * over complete role-a streams. The preference decides which unit the
     * existing link is maintained for and it is a fact about wiring; it cannot
     * decide which stream the figures come from, because by the time there are
     * figures it is known which unit produced any.
     *
     * `fellBack` is asserted in BOTH directions on every case rather than only
     * where it is true. It is the only thing separating "analysed the
     * preferred unit" from "analysed the only unit that turned up" once both
     * name a role that is present, and a flag that is only ever checked when
     * set is a flag nothing stops being written.
     *
     * NOTHING STREAMED is deliberately not a fallback. There is no other
     * stream to move onto, so the honest answer is the role the set armed and
     * a set of figures that are empty because there was nothing to compute
     * them from -- moving the name in that case would say a unit was analysed
     * when nothing was.
     *
     * This replaces `today the analysed role is the one that was armed,
     * whatever streamed`, which stated the pre-fix rule at c1 and was true
     * there.
     */
    @Test
    fun `the analysed role is one that streamed, and says so when it moved`() {
        assertEquals(
            AnalysedStream(SensorRole.B, fellBack = false),
            SensorCapturePolicy.analysedStream(SensorRole.B, listOf(SensorRole.B, SensorRole.A)),
            "both units streamed: the armed role stands and nothing moved",
        )
        assertEquals(
            AnalysedStream(SensorRole.B, fellBack = false),
            SensorCapturePolicy.analysedStream(SensorRole.B, listOf(SensorRole.B)),
            "the armed unit streamed and the other did not",
        )
        assertEquals(
            AnalysedStream(SensorRole.A, fellBack = true),
            SensorCapturePolicy.analysedStream(SensorRole.B, listOf(SensorRole.A)),
            "field-36 set 02: armed for b, only a streamed",
        )
        assertEquals(
            AnalysedStream(SensorRole.B, fellBack = false),
            SensorCapturePolicy.analysedStream(SensorRole.B, emptyList()),
            "nothing streamed at all, so there is nothing to fall back to",
        )
    }

    /**
     * A set with no role in play has no role to fall back to, and that is not
     * a state the fix touches.
     *
     * The ordinary one-sensor set and the set that met two paired units it
     * could not tell apart both record one UNROLED stream. There is no second
     * buffer to move onto and no role to name if there were, so the answer is
     * null in both directions and no fallback is ever reported.
     */
    @Test
    fun `a set with no role in play stays with no role in play`() {
        assertEquals(
            AnalysedStream(null, fellBack = false),
            SensorCapturePolicy.analysedStream(null, emptyList()),
        )
    }

    /**
     * A row that did not fall back carries no such key, which is every row
     * every earlier build wrote.
     *
     * The repository encodes `sensorsJson` with kotlinx's default
     * `encodeDefaults = false`, so the flag reaches the column only on a set
     * that moved. Its absence therefore has to mean the same thing as a stored
     * false, and it does: no build before #207 could move the analysed role,
     * so the role such a row names is the role it armed.
     */
    @Test
    fun `the fallback flag is absent from a stored declaration until a set falls back`() {
        val wire = Json { ignoreUnknownKeys = true }
        val declaration =
            RecordedSensors(count = 2, expected = listOf(SensorRole.B, SensorRole.A), analysed = SensorRole.B)

        val stored = wire.encodeToString(RecordedSensors.serializer(), declaration)
        assertTrue("analysedFellBack" !in stored, "an unremarkable set stored a fallback flag: $stored")
        assertEquals(
            declaration,
            wire.decodeFromString(RecordedSensors.serializer(), stored),
            "a row without the key decodes as one that did not fall back",
        )
        assertTrue(
            "analysedFellBack" in
                wire.encodeToString(RecordedSensors.serializer(), declaration.copy(analysedFellBack = true)),
            "a set that DID fall back must carry the key",
        )
    }

    // ---- the shortfall vocabulary --------------------------------------------

    /**
     * Every shortfall this app can name is one [SensorCapturePolicy.roster]
     * actually produces.
     *
     * A characterization pin taken before #198 dissolves one of the three, and
     * it is the invariant rather than the membership: a value nothing produces
     * is a sentence the Devices and Record screens can draw for a state that
     * cannot occur, and a value produced but not in the enum cannot exist. The
     * assertion is a SET equality against [DualShortfall.entries], so it holds
     * whatever the membership becomes and reddens the moment the two drift.
     *
     * Each input is named for the hardware state it stands for, not for the
     * answer it expects, so the case survives a change to what that state
     * means.
     */
    @Test
    fun `every shortfall the app can name is one the roster produces`() {
        val produced =
            listOf(
                // Nothing paired at all.
                SensorCapturePolicy.roster(emptyList(), null, emptyMap()),
                // One unit, labelled.
                SensorCapturePolicy.roster(listOf(a), a, mapOf(a to SensorRole.A)),
                // Two units, one carrying no label.
                SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A)),
                // Two units carrying the same label.
                SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.A)),
                // Two units, labelled apart: the state that arms.
                SensorCapturePolicy.roster(listOf(a, b), a, mapOf(a to SensorRole.A, b to SensorRole.B)),
                // Three units, so a label is missing or duplicated whatever the lifter did.
                SensorCapturePolicy.roster(listOf(a, b, c), a, mapOf(a to SensorRole.A, b to SensorRole.B)),
            ).mapNotNull { it.shortfall }.toSet()

        assertEquals(
            DualShortfall.entries.toSet(),
            produced,
            "a shortfall the enum declares that no hardware state produces, or the reverse",
        )
    }
}
