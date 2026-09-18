package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the live readout may keep counting across a change of unit (#280).
 *
 * The rows here are the ones that DO NOT change: no switch, and a declaration
 * that names no mount at all. They are the whole committed corpus -- every
 * capture held in this repository is a one-unit set or a dual set whose armed
 * unit kept the readout -- so these are what a regression would land on.
 *
 * The rows that change are pinned in the same class at their own SHA, as
 * failing tests, before the rule that makes them pass exists.
 */
class LiveFallbackPolicyTest {
    /**
     * Every one-unit set, and every dual set whose armed unit kept the readout.
     *
     * Asserted across all four declaration shapes and all three signals,
     * because the switch flag has to be read BEFORE any of them: a rule that
     * consulted the signature first would withhold on a set that never moved.
     */
    @Test
    fun `no switch continues whatever the declaration and the signature say`() {
        for (stack in listOf(false, true)) {
            for (other in listOf(false, true)) {
                for (signal in StackMountSignal.entries) {
                    assertEquals(
                        LiveFallback.Continue,
                        LiveFallbackPolicy.atSwitch(
                            switched = false,
                            declaresStackMount = stack,
                            declaresOtherMount = other,
                            signal = signal,
                        ),
                        "stack=$stack other=$other signal=$signal",
                    )
                }
            }
        }
    }

    /**
     * A declaration that names no mount describes the LIFT, so it applies to
     * the partner's stream as readily as to the armed unit's.
     *
     * This is `LiftDirection.mountSpecific`'s gate asked live, and it is the
     * same reason `SetAnalyzer` analyses an ordinary two-unit fallback rather
     * than refusing it (#247). Asserted over all three signals: a barbell set
     * has both units on one bar, so the signature says nothing useful there and
     * must not be reached.
     */
    @Test
    fun `a mount-free declaration continues across a switch`() {
        for (signal in StackMountSignal.entries) {
            assertEquals(
                LiveFallback.Continue,
                LiveFallbackPolicy.atSwitch(
                    switched = true,
                    declaresStackMount = false,
                    declaresOtherMount = false,
                    signal = signal,
                ),
                "signal=$signal",
            )
        }
    }

    /**
     * A MOUNT TERM NOTHING MEASURES refuses, whether or not a stack is declared
     * too.
     *
     * `sensorInverted` says a unit moved opposite to the load and `travelRatio`
     * scales its travel against the lifter's, and no code in this repository
     * reads a stream and answers for either. So on a switch there is no repair
     * available and the only honest answers are "count off a mounting nobody
     * can vouch for" or "stop counting". This is the population `SetAnalyzer`
     * already refuses at set end for the same reason (#247), and the refusal is
     * asserted over all three signals: a roll verdict says nothing about an
     * inversion, so reaching it here would be reading one measurement as an
     * answer to a different question.
     */
    @Test
    fun `a mount term nothing measures withholds the count`() {
        for (stack in listOf(false, true)) {
            for (signal in StackMountSignal.entries) {
                assertEquals(
                    LiveFallback.Withhold,
                    LiveFallbackPolicy.atSwitch(
                        switched = true,
                        declaresStackMount = stack,
                        declaresOtherMount = true,
                        signal = signal,
                    ),
                    "stack=$stack signal=$signal",
                )
            }
        }
    }

    /**
     * A stack-only declaration and a signature that DECIDED: the tracker is
     * rebuilt under the geometry that signature assigns to the unit now
     * feeding.
     *
     * ON_STACK is a CONFIRMATION -- the declaration describes this unit too, so
     * the stack term stays. NOT_ON_STACK drops it, and because the stack term
     * was the declaration's only mount term what is left is the lift's own
     * geometry; `LiftDirectionTest` pins that the dropped form is mount-free.
     *
     * The rebuild is not a no-op even on the confirming row: the tracker had
     * been integrating the ARMED unit's frames, and a switch hands it a
     * different unit's motion. What the switch's own condition bounds is how
     * much -- fewer than `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES` frames.
     */
    @Test
    fun `a decided signature rebuilds under the geometry it measured`() {
        assertEquals(
            LiveFallback.Rebuild(sensorOnStack = true),
            LiveFallbackPolicy.atSwitch(
                switched = true,
                declaresStackMount = true,
                declaresOtherMount = false,
                signal = StackMountSignal.ON_STACK,
            ),
        )
        assertEquals(
            LiveFallback.Rebuild(sensorOnStack = false),
            LiveFallbackPolicy.atSwitch(
                switched = true,
                declaresStackMount = true,
                declaresOtherMount = false,
                signal = StackMountSignal.NOT_ON_STACK,
            ),
        )
    }

    /**
     * A stack-only declaration and a signature that measured NOTHING: the count
     * stops.
     *
     * UNMEASURED is the working window holding too few samples to take a roll
     * range over -- absence, never a flat reading -- and defaulting it to the
     * declaration is exactly the defect #280 exists for. It is also the row a
     * real switch is most likely to land on: `LiveFeedPolicy` moves the feed
     * within roughly the first tenth of a second of the set, before the
     * partner's own working window has filled.
     */
    @Test
    fun `an unmeasured signature withholds rather than assuming the declaration`() {
        assertEquals(
            LiveFallback.Withhold,
            LiveFallbackPolicy.atSwitch(
                switched = true,
                declaresStackMount = true,
                declaresOtherMount = false,
                signal = StackMountSignal.UNMEASURED,
            ),
        )
    }

    /**
     * The whole cross product, so no combination is left to a reader to infer:
     * switch or not, four declaration shapes, three signals.
     *
     * Twenty-four rows. The twelve with no switch and the three mount-free ones
     * are Continue, the six carrying an unmeasurable mount term are Withhold,
     * and the three stack-only ones are the signature's answer.
     */
    @Test
    fun `the cross product has no unstated row`() {
        val seen = mutableSetOf<String>()
        for (switched in listOf(false, true)) {
            for (stack in listOf(false, true)) {
                for (other in listOf(false, true)) {
                    for (signal in StackMountSignal.entries) {
                        val expected =
                            when {
                                !switched -> LiveFallback.Continue
                                other -> LiveFallback.Withhold
                                !stack -> LiveFallback.Continue
                                signal == StackMountSignal.ON_STACK -> LiveFallback.Rebuild(true)
                                signal == StackMountSignal.NOT_ON_STACK -> LiveFallback.Rebuild(false)
                                else -> LiveFallback.Withhold
                            }
                        val row = "switched=$switched stack=$stack other=$other signal=$signal"
                        seen += row
                        assertEquals(
                            expected,
                            LiveFallbackPolicy.atSwitch(switched, stack, other, signal),
                            row,
                        )
                    }
                }
            }
        }
        assertEquals(24, seen.size)
    }

    /**
     * The word is a word and not a number, and it is not a stroke word.
     *
     * A count that simply stopped would be indistinguishable to the lifter from
     * a detector that missed every rep, and the owner cannot watch the ring
     * mid-set. That it cannot be mistaken for a terminal cue is pinned in
     * `:core:dsp`, beside `SetEnd`, where the vocabulary lives.
     */
    @Test
    fun `the withheld word is speakable and is not a digit`() {
        assertTrue(LiveFallbackPolicy.WITHHELD_CUE.isNotBlank())
        assertTrue(LiveFallbackPolicy.WITHHELD_CUE.none { it.isDigit() })
    }
}
