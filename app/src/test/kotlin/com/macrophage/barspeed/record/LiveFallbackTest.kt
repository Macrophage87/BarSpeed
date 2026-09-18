package com.macrophage.barspeed.record

import com.macrophage.barspeed.dsp.LiftDirection
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.LiveFallback
import com.macrophage.barspeed.model.LiveFeed
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wiring in `:app` between [LiveFeedPolicy]'s switch and
 * [com.macrophage.barspeed.model.LiveFallbackPolicy]'s answer (#280).
 *
 * THE DECISION IS NOT HERE. The rule is `LiveFallbackPolicy.atSwitch` in
 * `:core:model` and `LiveFallbackPolicyTest` pins all 24 of its rows; the
 * measurement is `StackRollSignature` in `:core:dsp` and `StackMountFieldTest`
 * pins that against the corpus. What lives in `:app`, and what this file pins,
 * is [liveFallbackAt]: WHICH of the two buffers the verdict is taken from, and
 * that no verdict is taken at all before the set's work has begun.
 *
 * BOTH ARE SILENT FAILURES IF WRONG. Measuring one unit's roll and attributing
 * it to the other reverses the repair -- it would rebuild the tracker under the
 * mounting of the unit that stopped feeding, which is the defect #280 exists
 * for. Taking a roll range over a set that has not started reads ON_STACK for
 * every unit, because nothing has moved, and re-applies the same declaration by
 * a different route.
 *
 * GREEN WHEN ADDED, and that is stated rather than implied: the red-before-green
 * evidence for this change is on the POLICY, pushed alone at its own SHA. These
 * pins guard wiring that did not exist before them, so what shows they can fail
 * is the mutation table in the commit that adds them, not a prior red.
 *
 * REACHABLE ONLY BECAUSE `app/build.gradle.kts` PINS THE TEST JVM TO 21, for
 * [CaptureAtTest]'s reason.
 */
class LiveFallbackTest {
    private val t0 = 1_000L

    /** [n] frames at 10 ms whose roll sits still at [rollDeg]: a unit riding a load. */
    private fun still(n: Int, rollDeg: Double = 90.0): List<ImuSample> = List(n) { i ->
        ImuSample(
            timestampMs = t0 + i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0,
            wxDps = 0.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = rollDeg,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    /** [n] frames at 10 ms whose roll sweeps 10 degrees a frame: a unit being handled. */
    private fun swept(n: Int): List<ImuSample> = List(n) { i ->
        ImuSample(
            timestampMs = t0 + i * 10L,
            axG = 0.0,
            ayG = 0.0,
            azG = 1.0,
            wxDps = 60.0,
            wyDps = 0.0,
            wzDps = 0.0,
            rollDeg = i * 10.0,
            pitchDeg = 0.0,
            yawDeg = 0.0,
        )
    }

    /** A lat pulldown as the seed declares it: a stack mount and no other mount term. */
    private val stackOnly = LiftDirection(sensorOnStack = true)

    private val switchedToB = LiveFeed(role = SensorRole.B, fellBack = true, switched = true)

    /**
     * The verdict comes from the buffer of the role that is now FEEDING.
     *
     * The armed unit's stream sweeps and the partner's sits still, so a verdict
     * read from the right buffer says the partner is the one on the stack and
     * the declaration stands. Read from the wrong one it would say the opposite
     * and drop the stack term -- the same stream, scored under the other unit's
     * mounting, which is the whole defect.
     */
    @Test
    fun `the roll verdict is taken from the stream that is now feeding`() {
        assertEquals(
            LiveFallback.Rebuild(sensorOnStack = true),
            liveFallbackAt(
                feed = switchedToB,
                declared = stackOnly,
                secondaryRole = SensorRole.B,
                analysedBuffer = swept(12),
                secondaryBuffer = still(12),
                workStartedAtMs = t0,
            ),
        )
    }

    /** And the other way round, so a rule reading one buffer always cannot pass. */
    @Test
    fun `a handled partner drops the stack term rather than keeping it`() {
        assertEquals(
            LiveFallback.Rebuild(sensorOnStack = false),
            liveFallbackAt(
                feed = switchedToB,
                declared = stackOnly,
                secondaryRole = SensorRole.B,
                analysedBuffer = still(12),
                secondaryBuffer = swept(12),
                workStartedAtMs = t0,
            ),
        )
    }

    /**
     * No work start, no verdict, however still the stream looks.
     *
     * `StackRollSignature`'s bound was fitted over whole working windows. A
     * switch fires tens of milliseconds into a set, before one opens, and every
     * unit is still then -- so a verdict taken there would say ON_STACK for a
     * unit clipped to a handle. The buffer here is the one the test above reads
     * as ON_STACK; only the missing work start changes the answer.
     */
    @Test
    fun `nothing is measured before the set's work has begun`() {
        assertEquals(
            LiveFallback.Withhold,
            liveFallbackAt(
                feed = switchedToB,
                declared = stackOnly,
                secondaryRole = SensorRole.B,
                analysedBuffer = swept(12),
                secondaryBuffer = still(12),
                workStartedAtMs = null,
            ),
        )
    }

    /** A window that has opened but holds too few frames is absence, not stillness. */
    @Test
    fun `too few frames since the work started is unmeasured`() {
        assertEquals(
            LiveFallback.Withhold,
            liveFallbackAt(
                feed = switchedToB,
                declared = stackOnly,
                secondaryRole = SensorRole.B,
                analysedBuffer = swept(12),
                secondaryBuffer = still(4),
                workStartedAtMs = t0,
            ),
        )
    }

    /**
     * No switch, nothing happens -- which is every set with one unit and every
     * dual set whose armed unit kept the readout, and the whole committed
     * corpus.
     */
    @Test
    fun `a set that never switched carries on`() {
        assertEquals(
            LiveFallback.Continue,
            liveFallbackAt(
                feed = LiveFeed(role = SensorRole.A, fellBack = false, switched = false),
                declared = stackOnly,
                secondaryRole = SensorRole.B,
                analysedBuffer = still(12),
                secondaryBuffer = swept(12),
                workStartedAtMs = t0,
            ),
        )
    }

    /** A barbell declaration describes the lift, so it carries to the other unit. */
    @Test
    fun `a mount-free declaration carries across the switch`() {
        assertEquals(
            LiveFallback.Continue,
            liveFallbackAt(
                feed = switchedToB,
                declared = LiftDirection(),
                secondaryRole = SensorRole.B,
                analysedBuffer = still(12),
                secondaryBuffer = swept(12),
                workStartedAtMs = t0,
            ),
        )
    }

    /**
     * A declared inversion withholds whatever the roll says, because nothing
     * measures an inversion. The partner's stream here reads ON_STACK, so a rule
     * that consulted the signature first would rebuild instead.
     */
    @Test
    fun `a declared inversion withholds whatever the roll says`() {
        assertEquals(
            LiveFallback.Withhold,
            liveFallbackAt(
                feed = switchedToB,
                declared = LiftDirection(sensorOnStack = true, sensorInverted = true),
                secondaryRole = SensorRole.B,
                analysedBuffer = swept(12),
                secondaryBuffer = still(12),
                workStartedAtMs = t0,
            ),
        )
    }

    /**
     * A set with no second role reads the armed buffer, which is the only one it
     * has. `LiveFeedPolicy` cannot report a switch on such a set; this pins that
     * the buffer choice does not depend on it having been able to.
     */
    @Test
    fun `a set with no secondary role reads the armed buffer`() {
        assertEquals(
            LiveFallback.Rebuild(sensorOnStack = false),
            liveFallbackAt(
                feed = LiveFeed(role = SensorRole.A, fellBack = false, switched = true),
                declared = stackOnly,
                secondaryRole = null,
                analysedBuffer = swept(12),
                secondaryBuffer = still(12),
                workStartedAtMs = t0,
            ),
        )
    }
}
