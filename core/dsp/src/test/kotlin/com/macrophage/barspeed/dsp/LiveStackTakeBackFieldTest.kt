package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.RepCounter
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.StackMountSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the live count WOULD do if the stack rule's inversion were taken back
 * mid-set, measured on the one capture that has a unit on the rope (#334).
 * A characterization, green at the commit it lands on: it records why the
 * take-back is not wired into the live readout, and it reds if any of the
 * facts that decision rests on moves.
 *
 * ## The question
 *
 * On a set whose inversion came from the stack rule alone, on a mount the
 * plan left to the stack table, `SetGeometryPolicy.analysedUnder` takes the
 * inversion back at set end when the analysed unit's roll says it did not
 * ride the stack (#323, #327). The live tracker and counter are built at
 * `beginSet` under the rule's inversion and keep it. #334 asks whether the
 * live count should take it back too.
 *
 * ## Why it could, and when it would fire
 *
 * On a set with no prep the set-end verdict is taken over a window that opens
 * at the capture's first sample, and both of `StackRollSignature`'s inputs --
 * the unwrapped roll range and the peak `|wx|` -- can only grow as that
 * window grows. So a prefix of the stream that reads
 * [StackMountSignal.NOT_ON_STACK] proves the set-end verdict early; the live
 * readout could act on it without making a second judgement. On field-41 set
 * 16's rope stream it would fire once 105 leading samples are in (the sample
 * at index 104), about a second into the capture and before the first rep.
 * The stack stream never fires.
 *
 * ## Why it is not wired
 *
 * Replayed through the live counter a sensor-counted set runs
 * ([RepCounter.SENSOR], so `LiveCounterPolicy`'s full-cycle detector), the
 * rope stream calls 13 of the 14 reps performed under the rule's inversion
 * and 5 with the inversion taken back. On this one guided set replayed as
 * straight reps, the take-back would cost eight called reps; nothing here
 * says what a lifter would hear on a real straight set. The stack stream
 * calls 0 under either reading, so the live count on that exercise is not
 * an inversion problem at all, and nothing about the take-back can repair
 * it. (The figures #323 quoted -- 1 inverted, 2 uninverted -- were taken
 * under the live counter of that time, before #305 moved sensor-counted sets
 * to the full cycle.)
 *
 * The set-end path is not this. `SetAnalyzer` reads the same rope stream 16
 * inverted and 15 uninverted (`PushdownInversionFieldTest`), so its take-back
 * stands.
 *
 * ## The near neighbour this exposes, and does not fix
 *
 * The geometry the take-back produces is the CORRECT one for this unit: the
 * set-end path reads the rope under it. It is also exactly what `beginSet`
 * builds for a rope pushdown whose plan writes `sensorOnStack: false`, since
 * the stack rule then never applies (`SetGeometryPolicy.stackInversion`),
 * except that the take-back leaves `sensorOnStack` true, which on a vertical
 * lift measures the same plane; `a plan that keeps the unit off the stack
 * gets the taken-back geometry` pins both. So on that set, today, with no take-back anywhere, the live
 * counter calls 5 of 14 on this stream. The better live count, 13, comes
 * from the WRONG geometry. Why the full-cycle detector under-counts on the
 * correct one is not measured here and is not fixed by anything in this
 * file; it is recorded as a remainder of #334. Keeping the inversion is
 * also a departure from #334's brief, which asked that the live count not
 * apply the stack rule, and is the owner's to confirm or overrule.
 *
 * ## What is not claimed
 *
 * Field-41 set 16 was GUIDED: a metronome counted it, and no live sensor
 * count of it exists. These are the streams replayed as if the set had been
 * straight reps, which is the shape #334 is about and which no capture on
 * disk records. One rope stream is not a population; a second rope capture,
 * unguided and with a counted rep total, is `[Field]`.
 */
class LiveStackTakeBackFieldTest {
    private val stack = "field-pushdown-1120-14rep-s41-set16"
    private val rope = "$stack-imu-b"

    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    /** Field-41's declaration of the set, with the tempo left off: a straight-reps set. */
    private val declared = PlanExerciseDef(
        exercise = "triceps_pushdown",
        start = "top",
        concentric = "down",
        plane = "vertical",
        sets = listOf(PlanSetDef(reps = 14)),
    )

    private val base = ExerciseDef(
        id = "triceps_pushdown",
        displayName = "triceps_pushdown",
        startsWith = ExerciseDef.inferStartPhase("triceps_pushdown"),
    )

    private val used = SetGeometryPolicy.resolve(base, declared)

    /** The definition the set-end path reads a unit under once its roll says it left the stack. */
    private val takenBack = SetGeometryPolicy.analysedUnder(
        used = used,
        geometry = SetGeometryPolicy.describe(used, declared),
        stackRuleApplied = SetGeometryPolicy.stackRuleApplied(base, declared),
        analysedSignal = StackMountSignal.NOT_ON_STACK,
    ).exercise

    /** The verdict over the first [n] samples, with no work start and no terminal cue. */
    private fun prefixSignal(samples: List<ImuSample>, n: Int): StackMountSignal =
        StackRollSignature.of(samples.subList(0, n), null, SetEnd.NotCued)

    /**
     * The fewest leading samples whose verdict is NOT_ON_STACK, or null where
     * the whole stream never reads it. A binary search, which the verdict's
     * monotonicity over a growing prefix makes valid; [monotone] checks it.
     */
    private fun firstNotOnStack(samples: List<ImuSample>): Int? {
        if (prefixSignal(samples, samples.size) != StackMountSignal.NOT_ON_STACK) return null
        var lo = 1
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (prefixSignal(samples, mid) == StackMountSignal.NOT_ON_STACK) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun monotone(samples: List<ImuSample>, from: Int) {
        (from..samples.size step 97).forEach {
            assertEquals(StackMountSignal.NOT_ON_STACK, prefixSignal(samples, it), "prefix of $it")
        }
    }

    /** What the live counter of a sensor-counted set calls over [samples] under [def]. */
    private fun liveCalls(def: ExerciseDef, samples: List<ImuSample>): Int {
        val direction = def.liftDirection()
        val tracker = StreamingSetTracker.forLift(direction)
        val counter = LiveRepCounters.forCounted(RepCounter.SENSOR, direction)!!
        var called = 0
        samples.forEach { sample ->
            val call = counter.feed(tracker.feed(sample), sample.timestampMs)
            if (call is RepCall.Speak) called = call.count
        }
        return called
    }

    @Test
    fun `the rule inverts this set and its roll decides`() {
        assertEquals(true, used.sensorInverted, "the stack rule's inversion")
        assertEquals(false, takenBack.sensorInverted, "taken back off the stack")
    }

    @Test
    fun `a plan that keeps the unit off the stack gets the taken-back geometry`() {
        val offStack = SetGeometryPolicy.resolve(base, declared.copy(sensorOnStack = false))
        // The two differ in one field, sensorOnStack, which the take-back
        // leaves as the stack table set it. On a vertical lift both measure
        // the same plane, and the live count below is the same five.
        assertEquals(
            takenBack.liftDirection().copy(sensorOnStack = false),
            offStack.liftDirection(),
            "declared off the stack against taken back, sensorOnStack aside",
        )
        assertEquals(takenBack.liftDirection().measuredPlane, offStack.liftDirection().measuredPlane, "measured plane")
        assertEquals(5, liveCalls(offStack, load(rope)), "rope, live, under the geometry its plan declared")
    }

    @Test
    fun `the rope stream proves it left the stack a second in, and the stack stream never does`() {
        val ropeSamples = load(rope)
        val at = firstNotOnStack(ropeSamples)
        assertEquals(105, at, "leading samples before the rope stream reads NOT_ON_STACK")
        assertEquals(1050L, ropeSamples[at!! - 1].timestampMs - ropeSamples.first().timestampMs, "ms into the capture")
        monotone(ropeSamples, at)
        assertEquals(null, firstNotOnStack(load(stack)), "the stack stream over its whole capture")
    }

    @Test
    fun `taking the inversion back would cost the rope stream eight live reps`() {
        val ropeSamples = load(rope)
        assertEquals(13, liveCalls(used, ropeSamples), "rope, under the rule's inversion")
        assertEquals(5, liveCalls(takenBack, ropeSamples), "rope, inversion taken back")
        val before = liveCalls(used, ropeSamples.subList(0, firstNotOnStack(ropeSamples)!!))
        assertEquals(0, before, "reps called before the take-back would fire")
    }

    @Test
    fun `the stack stream's live count is not an inversion problem`() {
        val stackSamples = load(stack)
        assertEquals(0, liveCalls(used, stackSamples), "stack, under the rule's inversion")
        assertEquals(0, liveCalls(takenBack, stackSamples), "stack, inversion taken back")
        assertTrue(stackSamples.size > 6000, "the stream is the whole set")
    }
}
