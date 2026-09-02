package com.macrophage.barspeed.record

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
        val a = samples(0L, 10L, 20L)
        val b = samples(1L, 11L)

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
        val survivor = samples(0L, 10L, 20L, 30L)

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
}
