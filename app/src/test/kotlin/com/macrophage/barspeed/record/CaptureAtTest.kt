package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
