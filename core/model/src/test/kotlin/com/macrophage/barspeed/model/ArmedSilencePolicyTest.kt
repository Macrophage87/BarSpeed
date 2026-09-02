package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Armed, and no frame by T" -- what the app may say about a unit it armed and
 * heard nothing from, issue #213.
 *
 * These are NEW symbols, so red-before-green is not available for them and no
 * pretence is made that it was: nothing here failed before
 * [ArmedSilencePolicy] existed, because there was nothing to call. What stands
 * in for it is mutation -- every rule below was broken by hand and the test it
 * guards reddened. The mutations and their measured totals are in the commit
 * body, not asserted here.
 *
 * The seam is why this file can exist. The observation these rules run on is
 * made in `:core:ble` (when a frame last arrived) and in `:app` (what the
 * screen is showing), and neither has a test that runs on the CI path. Lifting
 * the JUDGEMENT here is the "extract a pure seam and pin it" move.
 *
 * What no test in this repository can show, and what the commit body and the
 * report both say plainly: that a real WT901 switched off produces
 * `ConnectionState.Disconnected` rather than a stale `Connected`, that a GATT
 * profile mismatch is the only way `linkEstablished` becomes true on real
 * hardware, or that a subscribed link which stops delivering is ever seen at
 * all. `:core:ble` has no test source set, nothing here executes a GATT client,
 * and an emulator cannot simulate BLE. Those are [Field].
 */
class ArmedSilencePolicyTest {
    private val armedAt = 1_000_000L

    private fun after(ms: Long) = armedAt + ms

    // ---- T -------------------------------------------------------------------

    /**
     * The window is three seconds, and it is pinned as a value because it is a
     * number the lifter feels: it is how long they stand in front of a bar
     * before the app is willing to tell them a unit is dead.
     *
     * The two `:core:ble` figures it clears -- `AutoConnectManager.maintain`'s
     * three-second idle pass and `WitmotionClient.onReady`'s last command at
     * 1,200 ms -- are named in the constant's own KDoc and cannot be asserted
     * from here: this module cannot see `:core:ble`, the dependency runs the
     * other way. What is asserted is that the number did not move without
     * somebody meaning it to.
     */
    @Test
    fun `the silence window is three seconds`() {
        assertEquals(3_000L, ArmedSilencePolicy.SILENT_AFTER_MS, "the silence window moved")
    }

    /**
     * The boundary is inclusive on the quiet side: at exactly T the app has
     * waited long enough and speaks.
     *
     * Pinned because the alternative reading -- strictly greater -- is one
     * character away and would be invisible in any test that only used a
     * comfortable five seconds.
     */
    @Test
    fun `nothing is said before T and something is said at T`() {
        val silent = ConnectionState.Disconnected

        assertEquals(
            ArmedDelivery.TOO_SOON,
            ArmedSilencePolicy.deliveryOf(silent, lastFrameAtMs = null, armedAt, after(2_999L)),
            "the app accused a link one millisecond before its window closed",
        )
        assertEquals(
            ArmedDelivery.NOT_LINKED,
            ArmedSilencePolicy.deliveryOf(silent, lastFrameAtMs = null, armedAt, after(3_000L)),
            "the window closed and the app still would not say anything",
        )
    }

    // ---- what the stack can distinguish ---------------------------------------

    /**
     * The three states this app can actually tell apart for a silent armed
     * unit, each from the `ConnectionState` `:core:ble` produces for it.
     *
     * `Connecting` and `Disconnected` are one answer with "powered off", "out
     * of range" and "the bond was removed in Settings", because nothing in
     * this repository reads `BluetoothDevice.getBondState()` and the GATT
     * stack reports the same thing for all of them.
     */
    @Test
    fun `a silent unit is classified by the most specific state the stack reports`() {
        val late = after(10_000L)

        assertEquals(
            ArmedDelivery.NOT_LINKED,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Disconnected, null, armedAt, late),
        )
        assertEquals(
            ArmedDelivery.NOT_LINKED,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Connecting, null, armedAt, late),
        )
        assertEquals(
            ArmedDelivery.NOT_LINKED,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Failed("Bluetooth unavailable"), null, armedAt, late),
            "a connect that never reached a device is not a device that answered",
        )
        assertEquals(
            ArmedDelivery.LINK_WITHOUT_SENSOR,
            ArmedSilencePolicy.deliveryOf(
                ConnectionState.Failed("Expected service/characteristic not found", linkEstablished = true),
                null,
                armedAt,
                late,
            ),
            "the one state that proves a device answered was folded in with the ones that do not",
        )
        assertEquals(
            ArmedDelivery.LINKED_SILENT,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Connected("WT901BLE"), null, armedAt, late),
            "field-37's state: the link is up, the indicator is green, and nothing has arrived",
        )
    }

    /**
     * The reason string does not decide anything, `linkEstablished` does.
     *
     * The same case [SensorAdvicePolicyTest] holds fixed for
     * [SensorAdvicePolicy], for the same reason: a rule keyed on the sentence
     * would pass every test that happened to use two different sentences and
     * still be wrong the moment one is reworded.
     */
    @Test
    fun `only the link flag separates the two failure readings, never the words`() {
        val words = "Expected service/characteristic not found"
        val late = after(10_000L)

        assertEquals(
            ArmedDelivery.LINK_WITHOUT_SENSOR,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Failed(words, linkEstablished = true), null, armedAt, late),
        )
        assertEquals(
            ArmedDelivery.NOT_LINKED,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Failed(words, linkEstablished = false), null, armedAt, late),
        )
    }

    // ---- frames beat flags ----------------------------------------------------

    /**
     * A unit that is producing frames is delivering, whatever its link state
     * says.
     *
     * The frames are the fact; the state is a report about it. This is the
     * direction that matters for not crying wolf -- a stale or lagging
     * `ConnectionState` must never put a warning in front of a lifter whose
     * sensor is visibly feeding the app.
     */
    @Test
    fun `frames arriving beat whatever the link state claims`() {
        val now = after(10_000L)

        assertEquals(
            ArmedDelivery.DELIVERING,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Disconnected, lastFrameAtMs = now - 10L, armedAt, now),
            "a stream that is arriving was called dead because a flag disagreed",
        )
    }

    /**
     * Delivery is a ROLLING window, not a memory of the first frame.
     *
     * The defect this shape exists to prevent: a unit that streamed at the
     * start of a session and then went flat would read as delivering for the
     * rest of it, which is exactly the false green #213 was filed about,
     * rebuilt one layer down.
     */
    @Test
    fun `a unit that delivered and stopped is silent again once the window passes`() {
        val frame = after(1_000L)
        val connected = ConnectionState.Connected("WT901BLE")

        assertEquals(
            ArmedDelivery.DELIVERING,
            ArmedSilencePolicy.deliveryOf(connected, frame, armedAt, frame + 3_000L),
            "a frame exactly T old still counts as delivering",
        )
        assertEquals(
            ArmedDelivery.LINKED_SILENT,
            ArmedSilencePolicy.deliveryOf(connected, frame, armedAt, frame + 3_001L),
            "a stream that stopped kept reading as live",
        )
    }

    /**
     * A clock that went backwards declines to make a claim.
     *
     * `System.currentTimeMillis` is not monotonic and a correction mid-session
     * is enough. The conservative direction here is silence about the sensor,
     * not a warning derived from a negative age.
     */
    @Test
    fun `a clock correction makes the app say nothing rather than something wrong`() {
        assertEquals(
            ArmedDelivery.TOO_SOON,
            ArmedSilencePolicy.deliveryOf(ConnectionState.Disconnected, null, armedAt, armedAt - 60_000L),
        )
    }

    // ---- which roles are silent ----------------------------------------------

    /**
     * field-37's set 1, rebuilt: two roles armed, `a` delivering, `b` linked
     * and silent.
     *
     * The block that session published on all thirteen sets is
     * `{"count": 2, "expected": ["a", "b"], "present": ["a"]}`. What the app
     * could not say then, and says here, is which of the three states `b` was
     * in -- and the owner's account was that it "appeared to be paired in the
     * app", which is [ArmedDelivery.LINKED_SILENT]'s exact shape.
     *
     * That the unit really was in that state rather than in [ArmedDelivery.NOT_LINKED]
     * is NOT established: no build that could record it was running. It is the
     * [Field] question this fixture is built to make answerable next session.
     */
    @Test
    fun `field-37 shape, the armed partner is named silent and the delivering one is not`() {
        val armed = listOf(SensorRole.A, SensorRole.B)
        val silent =
            ArmedSilencePolicy.silent(
                armed,
                mapOf(SensorRole.A to ArmedDelivery.DELIVERING, SensorRole.B to ArmedDelivery.LINKED_SILENT),
            )

        assertEquals(mapOf(SensorRole.B to ArmedDelivery.LINKED_SILENT), silent)
        assertEquals(
            "Sensor B is connected but has sent no data. It will record nothing this set unless you " +
                "power-cycle it.",
            ArmedSilencePolicy.message(silent, sole = null, demoMode = false),
            "the sentence the SETUP window would have shown on every set of field-37",
        )
    }

    /**
     * Both units silent reads as one situation in the armed order, not as two
     * unordered complaints.
     */
    @Test
    fun `two silent roles are reported in the order they were armed`() {
        val silent =
            ArmedSilencePolicy.silent(
                listOf(SensorRole.B, SensorRole.A),
                mapOf(SensorRole.A to ArmedDelivery.NOT_LINKED, SensorRole.B to ArmedDelivery.LINKED_SILENT),
            )

        assertEquals(listOf(SensorRole.B, SensorRole.A), silent.keys.toList(), "the armed order was not kept")
        val message = assertNotNull(ArmedSilencePolicy.message(silent, sole = null, demoMode = false))
        assertTrue(
            message.indexOf("Sensor B") < message.indexOf("Sensor A"),
            "the message reordered the units the lifter has to go and find: $message",
        )
    }

    /**
     * Everything delivering says nothing at all.
     *
     * The ordinary case, and the one that must stay quiet: a permanent line in
     * front of a lifter whose sensors are both working is the complaint the
     * dissolved `ONE_SENSOR_PAIRED` shortfall used to make.
     */
    @Test
    fun `a set whose armed units are all delivering has nothing to say`() {
        val silent =
            ArmedSilencePolicy.silent(
                listOf(SensorRole.A, SensorRole.B),
                mapOf(SensorRole.A to ArmedDelivery.DELIVERING, SensorRole.B to ArmedDelivery.DELIVERING),
            )

        assertEquals(emptyMap(), silent)
        assertNull(ArmedSilencePolicy.message(silent, sole = null, demoMode = false))
    }

    /**
     * A window still inside T says nothing, even though nothing has arrived.
     *
     * [ArmedDelivery.TOO_SOON] survives [ArmedSilencePolicy.silent] -- it is
     * stored on a set shorter than T -- and is silent at the SCREEN, which is
     * the split this pair pins.
     */
    @Test
    fun `too soon is kept as a fact and still says nothing to the lifter`() {
        val silent =
            ArmedSilencePolicy.silent(listOf(SensorRole.B), mapOf(SensorRole.B to ArmedDelivery.TOO_SOON))

        assertEquals(mapOf(SensorRole.B to ArmedDelivery.TOO_SOON), silent, "a fact the export needs was dropped")
        assertNull(
            ArmedSilencePolicy.message(silent, sole = null, demoMode = false),
            "the app told the lifter something it did not know",
        )
    }

    /**
     * A role nobody looked at is not a role that was silent.
     *
     * The absence-rendered-as-a-value case, in the direction that matters: an
     * armed role with no reading is left out rather than defaulted to a state
     * the app never observed.
     */
    @Test
    fun `an armed role with no reading is left out rather than assumed dead`() {
        assertEquals(
            emptyMap(),
            ArmedSilencePolicy.silent(listOf(SensorRole.A, SensorRole.B), emptyMap()),
        )
    }

    /**
     * A role that is NOT armed cannot be reported silent however bad its link
     * looks.
     *
     * The one-sensor lifter's protection: their second WT901 is not armed, and
     * a rule that read the delivery map instead of the armed list would put a
     * warning about it on every set.
     */
    @Test
    fun `a role that was not armed is never reported`() {
        assertEquals(
            emptyMap(),
            ArmedSilencePolicy.silent(listOf(SensorRole.A), mapOf(SensorRole.B to ArmedDelivery.NOT_LINKED)),
        )
    }

    // ---- what is said and what is published ----------------------------------

    /**
     * Every state that can be silent has a remedy, and the two that are not
     * silent have none.
     *
     * Exhaustive over the enum rather than case by case, so a state added
     * later cannot ship without somebody deciding what the lifter does about
     * it.
     */
    @Test
    fun `every silent state names something the lifter can do, and no other state does`() {
        val advised = ArmedDelivery.entries.filter { ArmedSilencePolicy.advice(it, SensorRole.A) != null }.toSet()

        assertEquals(
            setOf(ArmedDelivery.NOT_LINKED, ArmedDelivery.LINK_WITHOUT_SENSOR, ArmedDelivery.LINKED_SILENT),
            advised,
            "a state the app can observe has no advice, or a state it cannot has one",
        )
        ArmedDelivery.entries.forEach { state ->
            val text = ArmedSilencePolicy.advice(state, SensorRole.B) ?: return@forEach
            assertTrue("Sensor B" in text, "$state's advice does not name the unit to go and find: $text")
            assertTrue("record nothing" in text, "$state's advice does not say what it costs: $text")
        }
    }

    /**
     * The wire vocabulary is total over the enum, spelled lowerCamel, and the
     * published set is everything except the state that means nothing is
     * wrong.
     *
     * The spellings are written out because they are the WIRE form and a
     * reader of the export has nothing else to check them against; the
     * MEMBERSHIP is derived, so a state added to the enum reddens here until
     * it is spelled.
     */
    @Test
    fun `the wire vocabulary is lowerCamel and publishes everything but delivering`() {
        assertEquals(
            setOf("delivering", "tooSoon", "notLinked", "linkWithoutSensor", "linkedSilent"),
            ArmedDelivery.entries.map(ArmedSilencePolicy::wireOf).toSet(),
            "a delivery state has no spelling, or a spelling drifted",
        )
        assertEquals(
            setOf("tooSoon", "notLinked", "linkWithoutSensor", "linkedSilent"),
            ArmedSilencePolicy.PUBLISHED_WIRE,
            "the published vocabulary is not the enum minus delivering",
        )
        assertEquals(
            ArmedDelivery.entries.size - 1,
            ArmedSilencePolicy.PUBLISHED_WIRE.size,
            "two states share a spelling, or delivering reached the published set",
        )
    }

    // ---- one unit, no role: issue #224 ---------------------------------------

    private val soloAddress = "AA:BB:CC:DD:EE:01"

    private fun soloRoster() = SensorCapturePolicy.roster(
        pairedImuAddresses = listOf(soloAddress),
        preferredAddress = soloAddress,
        roleByAddress = emptyMap(),
    )

    /**
     * DIFFERENTIAL, issue #224. The shape the owner trains most: ONE armed unit,
     * one stream, and the unit silent.
     *
     * The roster gives it no role -- #198's rule, unchanged here -- so
     * [ArmedSilencePolicy.silent] has nothing to key on and the whole of #213
     * misses it. This is the answer that does not need a role: what the app
     * could see of the ONE link it is holding.
     *
     * The unit is `Connected` and has never delivered, which is the state
     * field-37 drew a connected indicator for on thirteen sets. Whether a real
     * WT901 left switched off produces `Disconnected` rather than a stale
     * `Connected` is [Field] and is not asserted here.
     */
    @Test
    fun `one armed unit with no role is still named when it goes silent`() {
        val roster = soloRoster()
        assertNull(roster.analysed, "a role was invented for a single paired unit")
        assertEquals(emptyList(), roster.expected, "a single paired unit was armed for a role")

        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = roster,
                pairedImuAddresses = listOf(soloAddress),
                state = ConnectionState.Connected("WT901"),
                lastFrameAtMs = null,
                armedAtMs = armedAt,
                nowMs = after(4_000),
            )

        assertEquals(ArmedDelivery.LINKED_SILENT, sole, "the one armed unit went silent and nothing said so")
    }

    /**
     * DIFFERENTIAL, issue #224. The ordinary one-sensor set says nothing.
     *
     * The case that must stay quiet, and the reason the answer is null rather
     * than [ArmedDelivery.DELIVERING]: a word for a working unit would put a
     * declaration on every one-sensor row in the corpus and a line in front of
     * every lifter whose sensor is fine.
     */
    @Test
    fun `a sole unit that is delivering says nothing at all`() {
        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = soloRoster(),
                pairedImuAddresses = listOf(soloAddress),
                state = ConnectionState.Connected("WT901"),
                lastFrameAtMs = after(3_900),
                armedAtMs = armedAt,
                nowMs = after(4_000),
            )

        assertNull(sole, "a working single sensor was reported as a problem")
    }

    /**
     * DIFFERENTIAL, issue #224. A dual set never produces the roleless word.
     *
     * The two answers are exclusive by construction rather than by a rule
     * somebody has to remember: where roles are armed, `silent` is keyed by them
     * and says the same thing per unit. A set carrying both would state one fact
     * twice in two vocabularies.
     */
    @Test
    fun `a dual roster produces no roleless word`() {
        val a = soloAddress
        val b = "AA:BB:CC:DD:EE:02"
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = a,
                roleByAddress = mapOf(a to SensorRole.A, b to SensorRole.B),
            )

        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = roster,
                pairedImuAddresses = listOf(a, b),
                state = ConnectionState.Disconnected,
                lastFrameAtMs = null,
                armedAtMs = armedAt,
                nowMs = after(9_000),
            )

        assertNull(sole, "a dual set was given a word meant for a stream that carries no role")
    }

    /**
     * DIFFERENTIAL, issue #224. A manual set with nothing paired says nothing.
     *
     * No unit is armed, so there is no link to report on, and a word here would
     * be absence rendered as a value: "the app looked and saw nothing" is a
     * different statement from "nothing was ever armed".
     */
    @Test
    fun `a set with no paired unit at all reports no silence`() {
        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = SensorCapturePolicy.roster(emptyList(), null, emptyMap()),
                pairedImuAddresses = emptyList(),
                state = ConnectionState.Disconnected,
                lastFrameAtMs = null,
                armedAtMs = armedAt,
                nowMs = after(9_000),
            )

        assertNull(sole, "a set that armed no sensor at all was reported as having a silent one")
    }

    /**
     * DIFFERENTIAL, issue #224. Two paired units the app cannot tell apart
     * capture ONE unroled stream, and that stream's silence is sayable too.
     *
     * The near neighbour, fixed with the case rather than after it: the roster
     * arms no role there either, for a different reason, and the single link the
     * app does hold is the same link. Leaving it out would fix the reported set
     * and leave the one beside it silent in both senses.
     */
    @Test
    fun `two paired units that cannot be told apart report their one link too`() {
        val a = soloAddress
        val b = "AA:BB:CC:DD:EE:02"
        val roster =
            SensorCapturePolicy.roster(
                pairedImuAddresses = listOf(a, b),
                preferredAddress = a,
                roleByAddress = emptyMap(),
            )
        assertEquals(DualShortfall.ROLES_UNASSIGNED, roster.shortfall, "the fixture is not the unlabelled pair")

        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = roster,
                pairedImuAddresses = listOf(a, b),
                state = ConnectionState.Disconnected,
                lastFrameAtMs = null,
                armedAtMs = armedAt,
                nowMs = after(9_000),
            )

        assertEquals(ArmedDelivery.NOT_LINKED, sole, "the one link an unlabelled pair does hold reports nothing")
    }

    /**
     * DIFFERENTIAL, issue #224. Too soon is kept for the store and stays quiet
     * on the screen, exactly as it is for a role.
     */
    @Test
    fun `a sole unit inside the window is kept as a fact and says nothing`() {
        val sole =
            ArmedSilencePolicy.soleSilence(
                roster = soloRoster(),
                pairedImuAddresses = listOf(soloAddress),
                state = ConnectionState.Connected("WT901"),
                lastFrameAtMs = null,
                armedAtMs = armedAt,
                nowMs = after(1_000),
            )

        assertEquals(ArmedDelivery.TOO_SOON, sole, "a fact a short set needs to store was dropped")
        assertNull(
            ArmedSilencePolicy.message(emptyMap(), sole, demoMode = false),
            "the app accused a link one second into its connect",
        )
    }

    /**
     * DIFFERENTIAL, issue #224. The sentence for a unit with no role names no
     * role, and still says what it costs.
     *
     * Exhaustive over the enum for the reason the role-carrying case is: a state
     * added later cannot ship without somebody deciding what the lifter does
     * about it. What must NOT appear is a letter -- there is no A on this
     * lifter's unit, and telling them to go and find one is telling them about a
     * device they do not own, which is what the dissolved `ONE_SENSOR_PAIRED`
     * shortfall did.
     */
    @Test
    fun `the roleless sentence names no role and still says what it costs`() {
        val advised = ArmedDelivery.entries.filter { ArmedSilencePolicy.advice(it, null) != null }.toSet()

        assertEquals(
            setOf(ArmedDelivery.NOT_LINKED, ArmedDelivery.LINK_WITHOUT_SENSOR, ArmedDelivery.LINKED_SILENT),
            advised,
            "the roleless sentence advises a different set of states from the role-carrying one",
        )
        ArmedDelivery.entries.forEach { state ->
            val text = ArmedSilencePolicy.advice(state, null) ?: return@forEach
            assertFalse("Sensor A" in text, "$state names a role this lifter does not own: $text")
            assertFalse("Sensor B" in text, "$state names a role this lifter does not own: $text")
            assertTrue("bar sensor" in text, "$state does not name the unit the lifter must touch: $text")
            assertTrue("record nothing" in text, "$state's advice does not say what it costs: $text")
        }
    }

    /**
     * DIFFERENTIAL, issue #224. The card draws for a single silent unit, and
     * draws nothing when there is nothing to say.
     *
     * One function for both shapes, so the sentence the lifter reads on a
     * one-sensor session and the sentence they read on a two-sensor session
     * cannot come from two rules that disagree.
     */
    @Test
    fun `the card speaks for one unroled unit and stays quiet otherwise`() {
        assertEquals(
            "The bar sensor is connected but has sent no data. It will record nothing this set unless you " +
                "power-cycle it.",
            ArmedSilencePolicy.message(emptyMap(), ArmedDelivery.LINKED_SILENT, demoMode = false),
            "the sentence a single-sensor lifter reads before the set starts",
        )
        assertNull(
            ArmedSilencePolicy.message(emptyMap(), null, demoMode = false),
            "the card drew for a set with nothing to say",
        )
    }

    // ---- pairing a role with the link that holds it (#225) --------------------

    private val connected = ConnectionState.Connected("WT901BLE")

    private fun links(
        analysedState: ConnectionState = connected,
        analysedFrameAtMs: Long? = null,
        analysedArmedAtMs: Long = armedAt,
        secondaryState: ConnectionState = connected,
        secondaryFrameAtMs: Long? = null,
        secondaryArmedAtMs: Long = armedAt,
    ) = ArmedLinks(
        analysedState,
        analysedFrameAtMs,
        analysedArmedAtMs,
        secondaryState,
        secondaryFrameAtMs,
        secondaryArmedAtMs,
    )

    /**
     * Each role is judged by ITS OWN link, and the wrong-pair mistake is what
     * [ArmedLinks] exists to make impossible.
     *
     * Moved here from `:app`'s `ArmedDeliveryOfTest` with the function it
     * pins (#225 c1). The analysed link is delivering and the second link is
     * silent; the two readings must not be swapped and must not be merged.
     */
    @Test
    fun `each role is read from the link that holds it`() {
        val now = after(10_000L)

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.DELIVERING, SensorRole.B to ArmedDelivery.LINKED_SILENT),
            ArmedSilencePolicy.liveDeliveryByRole(
                analysed = SensorRole.A,
                secondary = SensorRole.B,
                links = links(analysedFrameAtMs = now - 100L),
                nowMs = now,
            ),
            "a role was judged against the other link's frames",
        )
    }

    /**
     * A set that armed no role reports nothing rather than inventing a key.
     *
     * The ordinary one-sensor set and the set that met two paired units it
     * could not tell apart both arrive with a null analysed role; [soleSilence]
     * is what covers them, and this map staying empty is what keeps a set from
     * carrying both answers.
     */
    @Test
    fun `no armed role produces no reading at all`() {
        assertTrue(
            ArmedSilencePolicy.liveDeliveryByRole(null, null, links(), after(10_000L)).isEmpty(),
            "a set that armed no role was given a role-keyed word",
        )
        assertTrue(
            ArmedSilencePolicy.storedDeliveryByRole(null, null, links(), armedAt, after(10_000L)).isEmpty(),
            "a set that armed no role stored a role-keyed word",
        )
    }

    /**
     * Frames beat the link state THROUGH THE PAIRING, not only inside
     * [ArmedSilencePolicy.deliveryOf]. The state flag is a report; the frames
     * are the fact.
     */
    @Test
    fun `arriving frames beat the state flag through the pairing`() {
        val now = after(10_000L)

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.DELIVERING),
            ArmedSilencePolicy.liveDeliveryByRole(
                analysed = SensorRole.A,
                secondary = null,
                links = links(analysedState = ConnectionState.Disconnected, analysedFrameAtMs = now - 10L),
                nowMs = now,
            ),
        )
    }

    /**
     * A genuinely short set on a genuinely fresh link still reads too soon.
     *
     * The link is armed half a second before the set begins and the set runs
     * two seconds, so it has had 2.5 seconds -- less than the window under
     * either floor -- and `tooSoon` is the honest answer rather than an
     * accusation.
     *
     * C1 OF #225 GOT THIS FIXTURE WRONG AND THE CLAIM WITH IT. It armed the
     * link one second before a two-second set, which is exactly the window
     * from the arming to the set's END, and called the result a boundary item
     * 8 "must NOT move". It does move, and it is supposed to: reaching the
     * floor back past the set's start is the whole of item 8. That sentence is
     * deleted rather than reworded, and the case it got wrong is pinned as a
     * differential below.
     */
    @Test
    fun `a short set on a link armed just before it still reads too soon`() {
        val setStart = after(500L)

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.TOO_SOON),
            ArmedSilencePolicy.storedDeliveryByRole(
                analysed = SensorRole.A,
                secondary = null,
                links = links(),
                setStartedAtMs = setStart,
                setEndedAtMs = setStart + 2_000L,
            ),
            "a link that has had less than the window either way was accused",
        )
    }

    // ---- #225: the grace floor, and the card in demo mode ---------------------

    /**
     * DIFFERENTIAL, issue #225 item 7. Demo mode says nothing about sensors.
     *
     * `RecordViewModel.startDemoStream` fabricates samples with no sensor
     * present, so the set DOES record and "It will record nothing this set" is
     * the one claim demo mode makes FALSE rather than merely fictional -- the
     * dot beside it already takes `demoActive` for that reason. The
     * suppression is whole rather than per-state: with no unit paired there is
     * nothing to switch on, bring near the phone or power-cycle, so every
     * sentence this function can produce names a remedy the lifter cannot
     * carry out.
     */
    @Test
    fun `demo mode says nothing about a sensor the set is not using`() {
        val silent =
            ArmedSilencePolicy.silent(
                listOf(SensorRole.A, SensorRole.B),
                mapOf(SensorRole.A to ArmedDelivery.NOT_LINKED, SensorRole.B to ArmedDelivery.LINKED_SILENT),
            )

        assertNotNull(
            ArmedSilencePolicy.message(silent, sole = null, demoMode = false),
            "the card stopped speaking outside demo mode",
        )
        assertNull(
            ArmedSilencePolicy.message(silent, sole = null, demoMode = true),
            "demo mode told the lifter the set would record nothing while it fabricates samples",
        )
        assertNull(
            ArmedSilencePolicy.message(emptyMap(), ArmedDelivery.LINKED_SILENT, demoMode = true),
            "the one-sensor sentence survived demo mode",
        )
    }

    /**
     * DIFFERENTIAL, issue #225 item 8. A short set does not excuse a link the
     * app has known was silent all session.
     *
     * The stored reading was floored by the SET's start, so a two-second set
     * put `tooSoon` on the row -- "the app does not know yet" -- about a link
     * armed an hour earlier that had never produced a frame. That is the
     * strongest word in this vocabulary being replaced by the weakest at the
     * one place the archive keeps it, and the row is written precisely when
     * that unit's buffer is empty, so it is the row a lifter reads to find out
     * why a set recorded nothing.
     *
     * Grace is a LIVE-warning concept: it stops the app accusing a link two
     * seconds into its connect while the lifter can still act. A row that has
     * already been written has nothing to act on.
     */
    @Test
    fun `a short set does not excuse a link armed long before it`() {
        val setStart = after(3_600_000L)
        val setEnd = setStart + 2_000L

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.LINKED_SILENT, SensorRole.B to ArmedDelivery.NOT_LINKED),
            ArmedSilencePolicy.storedDeliveryByRole(
                analysed = SensorRole.A,
                secondary = SensorRole.B,
                links = links(secondaryState = ConnectionState.Disconnected),
                setStartedAtMs = setStart,
                setEndedAtMs = setEnd,
            ),
            "a short set stored `the app does not know yet` about links silent for an hour",
        )
    }

    /**
     * DIFFERENTIAL, issue #225 item 8, for the configuration the owner trains
     * most.
     *
     * The same floor reaches the roleless answer (#224), so a short set with
     * one paired unit stored the same excuse. Fixed with the role-keyed one
     * rather than after it: one rule, both vocabularies, or the row and the
     * one-sensor row disagree about what a short set means.
     */
    @Test
    fun `a short one-sensor set does not excuse its link either`() {
        val setStart = after(3_600_000L)

        assertEquals(
            ArmedDelivery.LINKED_SILENT,
            ArmedSilencePolicy.storedSoleSilence(
                roster = soloRoster(),
                pairedImuAddresses = listOf(soloAddress),
                links = links(),
                setStartedAtMs = setStart,
                setEndedAtMs = setStart + 2_000L,
            ),
            "the one-sensor row stored `the app does not know yet` about a link silent for an hour",
        )
    }

    /**
     * DIFFERENTIAL, issue #225 item 8. One link's arming does not excuse the
     * other.
     *
     * The live reading floored BOTH links with `maxOf` over the two arming
     * instants, so re-pointing the second link handed three fresh seconds of
     * excused silence to the first one -- a figure measured against the wrong
     * link. Each link now answers to its own arming, which is what the
     * per-sensor dots on Home and Devices already do.
     */
    @Test
    fun `one link's arming does not excuse the other`() {
        val now = after(3_600_000L)

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.LINKED_SILENT, SensorRole.B to ArmedDelivery.TOO_SOON),
            ArmedSilencePolicy.liveDeliveryByRole(
                analysed = SensorRole.A,
                secondary = SensorRole.B,
                links = links(secondaryArmedAtMs = now - 1_000L),
                nowMs = now,
            ),
            "re-pointing one link excused the other",
        )
    }

    /**
     * DIFFERENTIAL, issue #225 item 8. The window is measured from the ARMING,
     * and it is inclusive at exactly T.
     *
     * The case c1's mis-set fixture stumbled into, pinned deliberately: a link
     * armed one second before a two-second set has been armed for exactly
     * [ArmedSilencePolicy.SILENT_AFTER_MS] when the set ends, and
     * [ArmedSilencePolicy.deliveryOf] speaks at T rather than after it. Under
     * the set-start floor the same set stored `tooSoon`, because it measured
     * two seconds and not three.
     *
     * It is the SAME boundary `nothing is said before T and something is said
     * at T` pins for the live reading; what item 8 changes is which instant T
     * is counted from, not where it falls.
     */
    @Test
    fun `the stored window is counted from the arming and speaks at exactly T`() {
        val setStart = after(1_000L)

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.LINKED_SILENT),
            ArmedSilencePolicy.storedDeliveryByRole(
                analysed = SensorRole.A,
                secondary = null,
                links = links(),
                setStartedAtMs = setStart,
                setEndedAtMs = setStart + 2_000L,
            ),
            "a link armed for the whole window was excused because the set was shorter than it",
        )
    }
}
