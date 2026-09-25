package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.LiveCounter
import com.macrophage.barspeed.model.LiveCounterPolicy
import com.macrophage.barspeed.model.RepCounter

/**
 * What a live rep counter is, from the caller's side: one published sample in, a
 * [RepCall] out.
 *
 * Three implementations -- [LiveRepCaller], [DriveImpulseCounter] and
 * [CycleRepCounter]. The interface exists so `:app` can hold a counter without
 * knowing which one it holds; WHICH one is `LiveCounterPolicy`'s decision, in
 * `:core:model` where a test runs on it.
 */
interface LiveRepCounter {
    /**
     * One live sample: the running total to speak, or [RepCall.Hold].
     *
     * Every implementation takes the tracker's own published state rather than
     * a raw `ImuSample`, so none runs a second integrator over the stream, and
     * each takes the ARRIVAL stamp separately because [LiveSetState] carries the
     * reconstructed clock and a cue has to be written on the arrival one.
     */
    fun feed(live: LiveSetState, timestampMs: Long): RepCall
}

/** Builds the live counter a set runs. */
object LiveRepCounters {
    /** The counter named by [choice]. */
    fun of(
        choice: LiveCounter,
        direction: LiftDirection = LiftDirection(),
        config: DspConfig = DspConfig(),
    ): LiveRepCounter = when (choice) {
        LiveCounter.SEGMENTER -> LiveRepCaller(direction, config)
        LiveCounter.DRIVE_IMPULSE -> DriveImpulseCounter(direction, config)
        LiveCounter.CYCLE -> CycleRepCounter(direction, config)
    }

    /**
     * The counter for a set counted by [counter], or null where no live counter
     * runs on such a set at all.
     *
     * The ONE call `:app` makes. Null is a disarm and not a quiet counter --
     * `LiveCounterPolicy`'s KDoc says why -- so a caller that stores the result
     * has both decisions, which counter and whether there is one, from one pure
     * function instead of an `if` beside a `when`.
     */
    fun forCounted(counter: RepCounter, direction: LiftDirection, config: DspConfig = DspConfig()): LiveRepCounter? =
        LiveCounterPolicy.counterFor(counter)?.let { of(it, direction, config) }
}
