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
