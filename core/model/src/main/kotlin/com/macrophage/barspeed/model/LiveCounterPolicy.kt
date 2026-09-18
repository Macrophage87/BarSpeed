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
     * The counter every sensor-counted set used from #286 until issue #301, and
     * the counter nothing selects now.
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
     * ## The owner's decision, 2026-09-17, issue #301
     *
     * *"Go with C."* A sensor-counted set counts on impulse. The evidence it was
     * taken on: 13 of field-43's 15 deadlift reps called with no phantom,
     * against 5 of 15 with one phantom from [LiveCounter.SEGMENTER], and the
     * touch-and-go set going from one call to five where no re-tuned ZUPT band
     * could reach it at all.
     *
     * ## The other three rows are the fix
     *
     * A counter that is not the sensor's arms no live detector, so this change
     * cannot reach a tempo-guided set, a set the lifter counts or a timed one.
     * That is not a side effect of the scoping -- it is the reason the
     * recommendation was sound, because the drive-impulse rule costs a great
     * deal outside a straight-reps barbell set: over the 33 committed captures
     * that carry a truth it calls 180 against 253 performed with an over-count
     * of 19, where the segmenter calls 108 with an over-count of 1, and on two
     * captures of eight performed reps it calls ZERO.
     * `LiveCountDifferentialTest` and `DriveImpulseCandidateTest` hold those
     * figures. If this row ever widens past [RepCounter.SENSOR], that is the
     * cost it takes on.
     *
     * ## What SEGMENTER is now
     *
     * Nothing selects it. `LiveRepCaller` ran on exactly the sets this row
     * governs -- a tempo-guided set is counted by the metronome and never armed
     * one -- so after this change no production path builds it. It is kept
     * rather than deleted for three reasons, stated so it is not read as
     * oversight: reverting this decision is one row, it is the only live counter
     * with a thirteen-capture corpus pin behind it (`LiveRepCallCorpusTest`),
     * and the design round's rejected hybrid composed the two.
     */
    fun counterFor(counter: RepCounter): LiveCounter? = when (counter) {
        RepCounter.SENSOR -> LiveCounter.DRIVE_IMPULSE
        RepCounter.MANUAL, RepCounter.METRONOME, RepCounter.NOBODY -> null
    }
}
