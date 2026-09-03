package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which buffer [armedCaptureOf] hands the DSP, and what the row it builds says
 * about that choice (#207).
 *
 * THE DECISION IS NOT HERE. [com.macrophage.barspeed.model.SensorCapturePolicy]
 * decides which role is analysed and whether that was a fallback, and
 * `SensorCapturePolicyTest` in `:core:model` pins it. What lives in `:app`, and
 * what this file pins, is the step after: turning a role into the LIST OF
 * SAMPLES the analysis actually runs on, and into the second capture's role and
 * rows. That is the pairing a policy test cannot see, and the one whose failure
 * mode is silent -- a right role beside the wrong buffer publishes a summary of
 * one sensor under the other one's name.
 *
 * IT HAD NOTHING RUNNING AGAINST IT UNTIL THIS FILE. Round 3 of #207 found that
 * `samples = decision.role?.let { byRole[it] } ?: analysedBuffer` could be cut
 * back to `analysedBuffer` -- the shipped defect, restored -- with the whole
 * suite still green, because no test on the CI path reached [armedCaptureOf] at
 * all. `AnalysedRoleFallbackTest` in `:core:data` re-types the same four steps
 * against its own copy of them, and a mirror agrees with itself.
 *
 * REACHABLE ONLY BECAUSE `app/build.gradle.kts` PINS THE TEST JVM TO 21, for
 * the reason `AppendedSlotTest` states: [ImuSample] and [RecordedSensors] are
 * `:core:model` types at class file 65 and `:app` is `jvmToolchain(17)`.
 */
class ArmedCaptureTest {
    private fun samples(vararg atMs: Long): List<ImuSample> = atMs.map { t ->
        ImuSample(
            timestampMs = t,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0,
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = 0.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    /**
     * A capture of [n] frames at 10 ms, which is a stream the analysis can be
     * pointed at whenever [n] reaches
     * [com.macrophage.barspeed.model.SensorCapturePolicy.MIN_ANALYSABLE_FRAMES].
     *
     * Every fixture below that stands for a unit that STREAMED is built from
     * this rather than from a hand-listed two or three timestamps. #209 is the
     * reason: a handful of frames is no longer a stream the analysis will
     * accept, so a fixture of three frames standing for a full capture asks
     * the wrong question and would pass for the wrong reason.
     */
    private fun stream(n: Int, firstMs: Long = 0L): List<ImuSample> = samples(*LongArray(n) { firstMs + it * 10L })

    private fun armed(vararg roles: SensorRole) =
        RecordedSensors(count = roles.size, expected = roles.toList(), analysed = roles.firstOrNull())

    /**
     * The ordinary dual set: the armed unit streamed, so nothing moves.
     *
     * The row says the armed role and carries no flag, and the second capture
     * is the other unit's own rows -- not a copy of the analysed ones, which is
     * what a lookup keyed by the wrong role would produce.
     */
    @Test
    fun `a set whose armed unit streamed is analysed from it and carries no flag`() {
        val a = stream(12)
        val b = stream(9, firstMs = 1L)

        val capture = armedCaptureOf(armed(SensorRole.A, SensorRole.B), SensorRole.B, a, b)

        assertEquals(a, capture.samples, "the analysis was pointed away from the armed unit that streamed")
        val sensors = assertNotNull(capture.sensors, "a dual set must still record what it was armed with")
        assertEquals(SensorRole.A, sensors.analysed, "the analysed role moved off a unit that streamed")
        assertFalse(sensors.analysedFellBack, "a set that never fell back is flagged as though it had")
        assertEquals(SensorRole.B, capture.secondary?.role, "the second capture named the wrong unit")
        assertEquals(b, capture.secondary?.samples, "the second capture carries the analysed unit's rows")
    }

    /**
     * The set this issue was filed for: the armed unit produced nothing and its
     * partner produced a capture.
     *
     * `samples` is the SURVIVING unit's buffer. Asserted by identity as well as
     * by value, because the two lists this function chooses between are both
     * `List<ImuSample>` and equality alone would pass on two lists that happen
     * to hold the same rows; identity says the lookup returned the right one.
     */
    @Test
    fun `a set whose armed unit was silent is analysed from the one that streamed`() {
        val armedButSilent = emptyList<ImuSample>()
        val survivor = stream(12)

        val capture = armedCaptureOf(armed(SensorRole.A, SensorRole.B), SensorRole.B, armedButSilent, survivor)

        assertSame(survivor, capture.samples, "the analysis is still pointed at the armed unit's empty buffer")
        assertTrue(capture.samples.isNotEmpty(), "the surviving capture was discarded and the summary is empty")
        val sensors = assertNotNull(capture.sensors, "a set that fell back must still record what it armed")
        assertEquals(SensorRole.B, sensors.analysed, "the row names the armed role over the one that streamed")
        assertTrue(sensors.analysedFellBack, "the row does not say the analysed role is not the armed one")
        assertEquals(
            SensorRole.A,
            capture.secondary?.role,
            "the second capture does not name the armed-and-silent unit",
        )
        assertEquals(
            emptyList<ImuSample>(),
            capture.secondary?.samples,
            "the armed-and-silent unit was handed rows it never produced",
        )
    }

    /**
     * Neither unit streamed, which is NOT a fallback.
     *
     * The row keeps the armed role and carries no flag: the figures are empty
     * because there was no capture at all, and renaming the role would say a
     * unit was analysed when none was. The buffer handed to the analysis is
     * empty either way, so the flag is the whole difference between this set
     * and the one above.
     */
    @Test
    fun `a set where nothing streamed keeps the armed role and no flag`() {
        val capture = armedCaptureOf(armed(SensorRole.A, SensorRole.B), SensorRole.B, emptyList(), emptyList())

        assertTrue(capture.samples.isEmpty(), "an empty set was handed samples from somewhere")
        val sensors = assertNotNull(capture.sensors, "a set that captured nothing must still say what it armed")
        assertEquals(SensorRole.A, sensors.analysed, "the armed role was renamed on a set with nothing to move to")
        assertFalse(sensors.analysedFellBack, "a set with no second stream to move onto is flagged as fallen back")
        assertEquals(SensorRole.B, capture.secondary?.role, "the second capture stopped naming the silent partner")
        assertEquals(emptyList<ImuSample>(), capture.secondary?.samples, "the silent partner was handed rows")
    }

    /**
     * The one-sensor set, and the set that met two paired units it could not
     * tell apart: one UNROLED stream, so there is no role to look a buffer up
     * by.
     *
     * `samples` falls through to the analysed buffer, which is the only capture
     * there is. Nothing here may invent a role -- a `secondary` on this set
     * would label a capture nobody labelled.
     */
    @Test
    fun `a set whose stream carries no role is analysed from the only buffer there is`() {
        val only = samples(0L, 10L)

        val capture = armedCaptureOf(RecordedSensors(count = 1), null, only, emptyList())

        assertSame(only, capture.samples, "the unroled set's only capture was not the one analysed")
        val sensors = assertNotNull(capture.sensors, "a one-sensor set must still record its count")
        assertNull(sensors.analysed, "a role was invented for a stream that carries none")
        assertFalse(sensors.analysedFellBack, "a set with one unroled stream is flagged as fallen back")
        assertNull(capture.secondary, "a second capture was invented for a set that recorded one stream")
    }

    /**
     * The set armed with no [RecordedSensors] at all -- a manual set, or one
     * begun before any unit was connected.
     *
     * Not one of the four cases round 3 named. It is here because it is the
     * fifth branch of the same function, and the near-neighbour class is what
     * happens when a fix pins four of five: nothing may be constructed for a
     * set that declared nothing, and the analysis still gets the buffer.
     */
    @Test
    fun `a set that recorded no sensor declaration constructs none`() {
        val only = samples(0L, 10L)

        val capture = armedCaptureOf(null, null, only, emptyList())

        assertSame(only, capture.samples, "the capture was dropped on a set with no sensor declaration")
        assertNull(capture.sensors, "a sensor declaration was invented for a set that made none")
        assertNull(capture.secondary, "a second capture was invented for a set that made no declaration")
    }

    /**
     * DIFFERENTIAL, issue #209. The set this issue was filed for: the armed
     * unit delivered SEVEN frames and its partner delivered a full capture.
     *
     * Seven is not zero, so every rule written for #207 answered "the armed
     * unit streamed" and the app kept the armed role -- and then the analysis
     * refused the buffer, because `VelocityEstimator.estimate` will not run
     * under `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`. The published outcome
     * is #207's exactly: an empty summary over a capture the app is holding,
     * on the narrower population where the armed unit sent a handful of frames
     * rather than none.
     *
     * SEVEN RATHER THAN ONE. One frame would pass under any bound above zero,
     * so it cannot tell a fix at eight from a fix at two; seven is the largest
     * delivery that still fails, so it is the only fixture that goes red again
     * if the bound is ever lowered.
     *
     * NO FIELD CAPTURE OF THIS POPULATION EXISTS. Field sessions 36 and 37
     * each produced a unit that delivered NOTHING, not a unit that delivered a
     * handful; this fixture is constructed from the source, as #209's own body
     * says its claims are, and no archive in this repo shows a 1-7 frame
     * stream. Whether a real WT901 that half-connects produces one is [Field].
     */
    @Test
    fun `an armed unit delivering seven frames is not what the partner's full capture is passed over for`() {
        val sevenFrames = stream(7)
        val fullCapture = stream(400)

        val capture = armedCaptureOf(armed(SensorRole.A, SensorRole.B), SensorRole.B, sevenFrames, fullCapture)

        assertSame(fullCapture, capture.samples, "the analysis was left pointed at a buffer the estimator refuses")
        val sensors = assertNotNull(capture.sensors, "a set that fell back must still record what it armed")
        assertEquals(SensorRole.B, sensors.analysed, "the row names the armed role over the one that could be analysed")
        assertTrue(sensors.analysedFellBack, "the row does not say the analysed role is not the armed one")
        assertEquals(SensorRole.A, capture.secondary?.role, "the second capture does not name the seven-frame unit")
        assertEquals(sevenFrames, capture.secondary?.samples, "the seven frames were dropped rather than archived")
    }

    /**
     * DIFFERENTIAL, issue #209. The stored half: the seven-frame unit gets the
     * silence word, so the record says which unit the analysis moved off.
     *
     * #213 and #225 shipped the word and #209 is the population they left out:
     * seven frames is a non-empty buffer, so `present` named the role and the
     * word was filtered away before it could be stored. The word itself is
     * still the LINK reading and still whatever the app could see -- nothing
     * here invents one -- and `ArmedSilencePolicy.silent` still drops a role
     * reading `DELIVERING`, which is a set whose last of those seven frames
     * arrived inside the three-second window ending when the set ended.
     */
    @Test
    fun `the seven-frame unit's silence word reaches the row`() {
        val capture =
            armedCaptureOf(
                armed(SensorRole.A, SensorRole.B),
                SensorRole.B,
                stream(7),
                stream(400),
                deliveryByRole = mapOf(SensorRole.A to ArmedDelivery.LINKED_SILENT),
            )

        val sensors = assertNotNull(capture.sensors, "a set that fell back must still record what it armed")
        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.LINKED_SILENT),
            sensors.silent,
            "the row says nothing about the unit whose handful of frames the analysis could not use",
        )
    }

    /**
     * DIFFERENTIAL, issue #209, the NEAR NEIGHBOUR: the same shortfall on a
     * set whose single stream carries no role.
     *
     * One bar sensor is the owner's habitual configuration, and #224 gave that
     * set `soleSilent` on the buffer being EMPTY. Seven frames is not empty,
     * so such a set publishes "No sensor data recorded." with no word beside
     * it -- the same defect one shape over, and the same predicate fixes it.
     * There is no partner to move onto here; what changes is only that the
     * record can say the link went quiet.
     */
    @Test
    fun `a one-sensor set whose unit delivered seven frames stores the word for it`() {
        val capture =
            armedCaptureOf(null, null, stream(7), emptyList(), soleDelivery = ArmedDelivery.LINKED_SILENT)

        val sensors = assertNotNull(capture.sensors, "the one armed unit sent too little to analyse and said nothing")
        assertEquals(ArmedDelivery.LINKED_SILENT, sensors.soleSilent, "the word never reached the row")
        assertEquals(emptyList(), sensors.expected, "a role was invented for a stream that carries none")
    }

    /**
     * DIFFERENTIAL, issue #224. The set this issue was filed for: ONE armed bar
     * sensor, no role, and nothing came down the link.
     *
     * The declaration is CONSTRUCTED here, on a set that declared nothing when
     * it was armed. That is the whole of the change at this site: the roster
     * gives a single unit no role -- #198's rule, and it stays -- so nothing
     * before this could hang a fact off the set at all.
     *
     * THE DECISION IS NOT HERE. `SensorCapturePolicy.withSoleSilence` decides
     * what a declaration becomes and `ArmedSilencePolicy.soleSilence` decides
     * whether there is a word at all; both are pinned in `:core:model`. What
     * lives in `:app`, and what this pins, is that the word reaches the row.
     */
    @Test
    fun `a single armed unit that went silent reaches the row with no role invented`() {
        val capture =
            armedCaptureOf(null, null, emptyList(), emptyList(), soleDelivery = ArmedDelivery.LINKED_SILENT)

        val sensors = assertNotNull(capture.sensors, "the one armed unit went silent and the row said nothing")
        assertEquals(1, sensors.count, "a set that armed one unit recorded another number")
        assertEquals(emptyList(), sensors.expected, "a role was invented for a stream that carries none")
        assertNull(sensors.analysed, "a role was named as analysed on a set that armed none")
        assertEquals(ArmedDelivery.LINKED_SILENT, sensors.soleSilent, "the word never reached the row")
        assertNull(capture.secondary, "a second capture was invented for a set that recorded one stream")
    }

    /**
     * DIFFERENTIAL, issue #224. The control: a one-sensor set whose unit
     * delivered still constructs nothing.
     *
     * What keeps every ordinary single-sensor set byte-identical to what this
     * app has always written. The parameter is passed explicitly rather than
     * defaulted, because the pairing of "nothing was silent" with "no
     * declaration" is the assertion, not the call shape.
     */
    @Test
    fun `a single armed unit that delivered constructs no declaration`() {
        val only = samples(0L, 10L)

        val capture = armedCaptureOf(null, null, only, emptyList(), soleDelivery = null)

        assertSame(only, capture.samples, "the capture was dropped on a set with no sensor declaration")
        assertNull(capture.sensors, "a declaration was invented for a set whose one unit delivered")
    }

    /**
     * DIFFERENTIAL, issue #224. The set-end path ASKS about the one link, and
     * the answer reaches the row.
     *
     * A second pin over the same fix, and it exists because a mutation survived
     * the first one. Deleting the argument [captureAt] passes down --
     * `soleSilenceOver(startedAtMs, endedAtMs)` cut to `null` -- left every test
     * in this file green, because they all call [armedCaptureOf] directly and
     * nothing reached the caller. That is the shape round 3 of #207 found in
     * this same function and the reason this file was written at all; finding
     * it again one call up is what a mutation table is for.
     *
     * [RecordState] is constructed here rather than mocked: every one of its
     * properties is a pure Kotlin or `:core:model` type with a default, so the
     * four that matter -- the paired list, the preference, the link state and
     * the frame instant -- can be stated and the rest left alone.
     *
     * The unit is `Connected` and has never delivered, and the set ran for a
     * minute, so the reading is [ArmedDelivery.LINKED_SILENT]: the state
     * field-37 drew a connected indicator for. Whether a real WT901 produces it
     * is [Field] and is not asserted here.
     */
    @Test
    fun `the set end path asks about the one link and freezes the answer onto the row`() {
        val address = "AA:BB:CC:DD:EE:01"
        val state =
            RecordState(
                pairedImuAddresses = listOf(address),
                preferredImuAddress = address,
                imuState = ConnectionState.Connected("WT901"),
                imuFrameAtMs = null,
            )

        val capture =
            state.captureAt(
                armed = null,
                secondaryRole = null,
                analysedBuffer = emptyList(),
                secondaryBuffer = emptyList(),
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
            )

        val sensors = assertNotNull(capture.sensors, "the set end path never asked what the one link was doing")
        assertEquals(ArmedDelivery.LINKED_SILENT, sensors.soleSilent, "the reading did not reach the row")
        assertEquals(1, sensors.count, "a set that armed one unit recorded another number")
        assertEquals(emptyList(), sensors.expected, "a role was invented for a stream that carries none")
    }

    /**
     * DIFFERENTIAL, issue #224. The control at the same call: a one-sensor set
     * whose unit was delivering when it ended stores nothing at all.
     *
     * What keeps every ordinary single-sensor set byte-identical. The frame is
     * one second before the set ended, inside `ArmedSilencePolicy`'s window, so
     * the link reads as delivering and there is nothing to say.
     */
    @Test
    fun `the set end path stores nothing when the one link was delivering`() {
        val address = "AA:BB:CC:DD:EE:01"
        val only = samples(0L, 10L)
        val state =
            RecordState(
                pairedImuAddresses = listOf(address),
                preferredImuAddress = address,
                imuState = ConnectionState.Connected("WT901"),
                imuFrameAtMs = 60_000L,
            )

        val capture =
            state.captureAt(
                armed = null,
                secondaryRole = null,
                analysedBuffer = only,
                secondaryBuffer = emptyList(),
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
            )

        assertNull(capture.sensors, "a declaration was invented for a set whose one unit delivered")
        assertEquals(only, capture.samples, "the capture was dropped on an ordinary one-sensor set")
    }

    /**
     * DIFFERENTIAL, issue #224 round 1, finding 1. A one-sensor set that
     * STREAMED and then lost its link stores no declaration at all.
     *
     * THE DEFECT THIS PINS. `soleSilenceOver` reads the link's state and its
     * last frame instant and nothing else, and `deliveryOf` tests a fixed
     * `ArmedSilencePolicy.SILENT_AFTER_MS` window ending when the set ended.
     * So a unit that fed the whole set and dropped in its last seconds reads
     * `LINKED_SILENT`, and before this pin that word was written onto a row
     * sitting beside a full summary and a real `imu.csv`. The archive then
     * said the one unit delivered nothing while the archive itself held its
     * stream -- one document contradicting itself about one set, which is
     * worse than saying nothing, because a reader has no way to tell which
     * half to believe.
     *
     * WHAT DECIDES IT IS THE BUFFER, not a second link reading. The buffer is
     * the same source `SensorCapturePolicy.present` is read from for a
     * role-keyed set, so the roleless set is judged by the fact the role-keyed
     * one is judged by rather than by a near neighbour of it.
     *
     * The fixture: one paired unit, `Connected`, last frame at 40 s on a set
     * that ran 1 s to 61 s -- twenty seconds past the window, so the reading
     * is `LINKED_SILENT` and is NOT the reason nothing is stored. Twelve
     * frames are in the buffer. Only the buffer can make this pass.
     *
     * THE FIXTURE GREW FROM FOUR FRAMES TO TWELVE at #209, and the sentence
     * saying a hundred samples were in it is deleted rather than reworded: it
     * was false when it was written and four frames was never a stream.
     * Twelve is above `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`, so the set
     * this pins is one whose unit really did feed an analysable capture --
     * which is what "streamed and then lost its link" has to mean for the pin
     * to be about what its name says.
     */
    @Test
    fun `a one-sensor set that streamed and then lost its link stores no declaration`() {
        val address = "AA:BB:CC:DD:EE:01"
        val streamed = stream(12)
        val state =
            RecordState(
                pairedImuAddresses = listOf(address),
                preferredImuAddress = address,
                imuState = ConnectionState.Connected("WT901"),
                imuFrameAtMs = 40_000L,
            )

        assertEquals(
            ArmedDelivery.LINKED_SILENT,
            state.soleSilenceOver(1_000L, 61_000L),
            "the fixture does not reach the state this case is about",
        )

        val capture =
            state.captureAt(
                armed = null,
                secondaryRole = null,
                analysedBuffer = streamed,
                secondaryBuffer = emptyList(),
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
            )

        assertNull(
            capture.sensors,
            "a set whose one unit filled the buffer was recorded as having delivered nothing",
        )
        assertEquals(streamed, capture.samples, "the capture was dropped on a set that streamed")
    }
}
