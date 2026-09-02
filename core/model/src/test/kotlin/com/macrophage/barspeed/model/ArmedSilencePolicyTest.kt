package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
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
            ArmedSilencePolicy.message(silent),
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
        val message = assertNotNull(ArmedSilencePolicy.message(silent))
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
        assertNull(ArmedSilencePolicy.message(silent))
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
        assertNull(ArmedSilencePolicy.message(silent), "the app told the lifter something it did not know")
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
}
