package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.AnalysedRoleBasis
import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the SET-END PATH in `:app` asks about the one armed link, and that the
 * answer reaches the row (#224).
 *
 * THE DECISION IS NOT HERE. `armedCaptureOf` and `SensorCapturePolicy` and
 * `ArmedSilencePolicy` all decide, all in `:core:model`, and `ArmedCaptureTest`
 * there pins the first of them. What lives in `:app`, and what this file pins,
 * is [RecordState.captureAt]: the wiring that reads the four link fields and
 * the set's two instants off the state and hands them down. That wiring is a
 * `:app` extension on a `:app` type and is the reason these three tests stayed
 * behind when #212 moved the other ten to `:core:model`; they were
 * `ArmedCaptureTest`'s last three methods until then.
 *
 * REACHABLE ONLY BECAUSE `app/build.gradle.kts` PINS THE TEST JVM TO 21, for
 * the reason `AppendedSlotTest` states: [ImuSample] is a `:core:model` type at
 * class file 65 and `:app` is `jvmToolchain(17)`.
 */
class CaptureAtTest {
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
     * `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`.
     *
     * Every fixture below that stands for a unit that STREAMED is built from
     * this rather than from a hand-listed two or three timestamps. #209 is the
     * reason: a handful of frames is no longer a stream the analysis will
     * accept, so a fixture of three frames standing for a full capture asks
     * the wrong question and would pass for the wrong reason.
     */
    private fun stream(n: Int, firstMs: Long = 0L): List<ImuSample> = samples(*LongArray(n) { firstMs + it * 10L })

    /**
     * [n] frames at 10 ms whose ROLL advances [degreesPerSample] each frame,
     * which is the only axis `StackRollSignature` reads.
     *
     * Two streams built from this differ in nothing else, so a verdict that
     * separates them separated them on their roll.
     */
    /**
     * An exercise that declares the sensor on a weight stack, which is the only
     * field `captureAt` reads off it: field-42's seated cable row as its own
     * meta.json declares it.
     */
    private val stackDeclared =
        ExerciseDef(
            id = "seated_cable_row",
            displayName = "Seated Cable Row",
            horizontal = true,
            sensorOnStack = true,
        )

    private fun rollingStream(n: Int, degreesPerSample: Double, firstMs: Long = 0L): List<ImuSample> =
        (0 until n).map { i ->
            ImuSample(
                timestampMs = firstMs + i * 10L,
                axG = 0.0,
                ayG = 0.0,
                azG = 1.0,
                wxDps = 0.0,
                wyDps = 0.0,
                wzDps = 0.0,
                rollDeg = i * degreesPerSample,
                pitchDeg = 0.0,
                yawDeg = 0.0,
            )
        }

    /**
     * DIFFERENTIAL, issue #224. The set-end path ASKS about the one link, and
     * the answer reaches the row.
     *
     * A second pin over the same fix, and it exists because a mutation survived
     * the first one. Deleting the argument [captureAt] passes down --
     * `soleSilenceOver(startedAtMs, endedAtMs)` cut to `null` -- left every
     * test that calls `armedCaptureOf` directly green, because nothing reached
     * the caller. Those tests are `ArmedCaptureTest` in `:core:model` since
     * #212, which is exactly why these three could not go with them. That is
     * the shape round 3 of #207 found one level down, and finding it again one
     * call up is what a mutation table is for.
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

    /**
     * DIFFERENTIAL, issue #278. The set-end path in `:app` hands the mount
     * question down, and the answer reaches the row.
     *
     * A pin one level up from `ArmedCaptureTest`'s, for the reason the first
     * test in this file states: deleting what `captureAt` passes down leaves
     * every test that calls `armedCaptureOf` directly green, because nothing
     * reaches the caller. That is the shape round 3 of #207 found, and this is
     * the only place on the CI path that can catch it for the two arguments
     * this issue adds.
     *
     * The fixture is field-42's seated cable row in miniature: two paired units
     * labelled apart, the armed one rolling and the partner still, both
     * delivering. The two streams differ ONLY in roll -- 40 degrees against a
     * tenth of one -- so nothing but `StackRollSignature`'s own verdict can
     * decide between them, and the analysis must come out on the partner with
     * the row saying `stackSignature`.
     *
     * The window is left open at both ends: no work-start instant and
     * `SetEnd.NotCued`, which is `RollExcursion.Basis.WHOLE_CAPTURE` and the
     * looser reading. A looser window can only refuse a stack candidate, never
     * invent one, so a pin that passes here would pass under a tighter one too.
     */
    @Test
    fun `the set end path hands down the mount question and the row says why`() {
        val handleUnit = "AA:BB:CC:DD:EE:01"
        val stackUnit = "AA:BB:CC:DD:EE:02"
        val rolling = rollingStream(12, degreesPerSample = 4.0)
        val still = rollingStream(12, degreesPerSample = 0.01, firstMs = 1L)
        val state =
            RecordState(
                pairedImuAddresses = listOf(handleUnit, stackUnit),
                preferredImuAddress = handleUnit,
                imuState = ConnectionState.Connected("WT901"),
                imuFrameAtMs = 60_000L,
                sensorRoles = mapOf(handleUnit to SensorRole.A, stackUnit to SensorRole.B),
            )

        val capture =
            state.captureAt(
                armed = RecordedSensors(
                    count = 2,
                    expected = listOf(SensorRole.A, SensorRole.B),
                    analysed = SensorRole.A,
                ),
                secondaryRole = SensorRole.B,
                analysedBuffer = rolling,
                secondaryBuffer = still,
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
                exercise = stackDeclared,
            )

        assertEquals(still, capture.samples, "the analysis stayed on the rolling unit")
        val sensors = assertNotNull(capture.sensors, "a dual set must still record what it was armed with")
        assertEquals(SensorRole.B, sensors.analysed, "the row names the armed role, so nothing was handed down")
        assertEquals(AnalysedRoleBasis.STACK_SIGNATURE, sensors.analysedRoleBasis, "the row does not say why")
        assertFalse(sensors.analysedFellBack, "a signature verdict set the flag #247's refusal keys off")
    }

    /**
     * THE CONTROL AT THE SAME CALL: the same two streams with no stack
     * declaration are analysed from the armed unit, and the row says
     * `declared`.
     *
     * Every barbell set is this one. Without it, a pin that only ever asserts
     * the move would pass on a wiring that moved every two-unit set.
     */
    @Test
    fun `the set end path leaves a set that declares no stack mount alone`() {
        val handleUnit = "AA:BB:CC:DD:EE:01"
        val stackUnit = "AA:BB:CC:DD:EE:02"
        val rolling = rollingStream(12, degreesPerSample = 4.0)
        val still = rollingStream(12, degreesPerSample = 0.01, firstMs = 1L)
        val state =
            RecordState(
                pairedImuAddresses = listOf(handleUnit, stackUnit),
                preferredImuAddress = handleUnit,
                imuState = ConnectionState.Connected("WT901"),
                imuFrameAtMs = 60_000L,
                sensorRoles = mapOf(handleUnit to SensorRole.A, stackUnit to SensorRole.B),
            )

        val capture =
            state.captureAt(
                armed = RecordedSensors(
                    count = 2,
                    expected = listOf(SensorRole.A, SensorRole.B),
                    analysed = SensorRole.A,
                ),
                secondaryRole = SensorRole.B,
                analysedBuffer = rolling,
                secondaryBuffer = still,
                startedAtMs = 1_000L,
                endedAtMs = 61_000L,
            )

        assertEquals(rolling, capture.samples, "a set declaring no stack mount was moved onto its partner")
        assertEquals(SensorRole.A, capture.sensors?.analysed)
        assertEquals(AnalysedRoleBasis.DECLARED, capture.sensors?.analysedRoleBasis)
    }
}
