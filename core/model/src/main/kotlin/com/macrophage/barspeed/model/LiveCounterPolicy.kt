package com.macrophage.barspeed.model

/**
 * WHICH live counter a sensor-counted set runs.
 *
 * [CountingPolicy] answers who counts -- the sensor, the lifter, the metronome
 * or nobody. This answers the question that only has an answer once the first
 * of those wins: which of the two rep detectors in `:core:dsp` the arriving
 * samples are fed to. They are different detectors, not two tunings of one, and
 * a set is fed to exactly one.
 *
 * A type rather than a branch in `RecordViewModel` so that changing the answer
 * is ONE ROW of a `when` with a test on it, visible in a diff and pinned in
 * `LiveCounterPolicyTest`. The alternative -- an `if` at the construction site
 * in `:app` -- is a decision no test on the CI path can reach, which is what
 * `CountingPolicy`'s own KDoc records about the `manualSet` boolean it
 * replaced.
 */
enum class LiveCounter {
    /**
     * `LiveRepCaller`: `RepSegmenter`'s pairing rule, run over the causal
     * velocity `StreamingSetTracker` publishes.
     *
     * The counter every sensor-counted set used from #286 until issue #301.
     * It counts a rep from the VELOCITY, so it inherits everything the live
     * integrator does: the ZUPT anchor it may never find, and
     * `DspConfig.maxRunDisplacementM`, which refuses a run it cannot bound.
     * On field-43's three deadlifts that cost ten of fifteen reps.
     */
    SEGMENTER,

    /**
     * `DriveImpulseCounter`: an upward acceleration impulse followed by
     * braking, with no velocity in it at all.
     *
     * Chosen for issue #301 because no integrator, no anchor and no
     * displacement cap can reach it, so the three mechanisms that lost
     * field-43's reps cannot. What it costs instead is measured, not guessed:
     * it under-counts a GRIND (it missed the two slowest pulls of field-43's
     * 102 kg set), and on stack and machine captures its sensor-frame
     * thresholds are applied through a pulley ratio and it collapses --
     * `DriveImpulseCandidateTest`'s corpus row is where both are pinned.
     */
    DRIVE_IMPULSE,
}

/**
 * The counter a set of a given [RepCounter] feeds, or null where no live
 * counter runs at all.
 *
 * ## Null is not a third counter
 *
 * A set the lifter counts, a set the metronome counts and a timed set feed no
 * live rep detector, and that is an ABSENCE rather than a quiet detector: a
 * counter armed on such a set would call reps nobody hears and would leave a
 * count behind for the next set to read. `RecordViewModel`'s `SensorRepCounter`
 * treats null as DISARM for exactly that reason.
 */
object LiveCounterPolicy {
    /**
     * The live detector for a set counted by [counter].
     *
     * ## This row is TODAY'S answer and is about to move
     *
     * [LiveCounter.SEGMENTER] on a sensor-counted set is what #286 shipped and
     * what field-43 measured at three, one and two calls for five performed
     * reps a set. The owner's decision on issue #301 is that a sensor-counted
     * set moves to [LiveCounter.DRIVE_IMPULSE]; this commit extracts the seam
     * and changes NO behaviour, so the row still reads SEGMENTER and
     * `LiveCounterPolicyTest` pins it as the shipped answer. The commit that
     * flips it is the one that has to carry the evidence.
     *
     * The scope is deliberately the SET and not the exercise or the geometry.
     * `CountingPolicy.counterFor` returns [RepCounter.SENSOR] for a rep-based
     * set with no prescribed tempo and an IMU connected, which on the committed
     * corpus is the three field-43 deadlifts and nothing else, so scoping the
     * choice here leaves every tempo-guided capture on the segmenter whatever
     * this row becomes.
     */
    fun counterFor(counter: RepCounter): LiveCounter? = when (counter) {
        RepCounter.SENSOR -> LiveCounter.SEGMENTER
        RepCounter.MANUAL, RepCounter.METRONOME, RepCounter.NOBODY -> null
    }
}
