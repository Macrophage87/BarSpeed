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

    /**
     * `CycleRepCounter`: a drive measured by the velocity it GAINS, met by its
     * brake, and called only once the bar is back at the floor -- a contact, a
     * fall, a stillness or the next drive, at least `DspConfig.cycleMinCycleS`
     * after the drive ends and after enough descent. A contact or fall sooner
     * rejects the attempt and nothing is spoken for it.
     *
     * Issue #305's design round, measured on the eight deadlift sets the
     * corpus holds (field-43 sets 4-6, field-44 sets 1-5): 35 of 36 completed
     * reps with one phantom, field-44 set 5's failed pull not called, where
     * [DRIVE_IMPULSE] counts 28 and nothing at 111 and 120 kg. It speaks as the
     * bar lands rather than at the brake -- median 1.16 s after the batch
     * window ends -- and on a light soft landing a rep late.
     * `ClosingRuleCandidateTest` pins every figure.
     */
    CYCLE,
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
     * ## The other three rows are unchanged, and they are NOT a barbell gate
     *
     * A counter that is not the sensor's arms no live detector, so this change
     * cannot reach a tempo-guided set, a set the lifter counts or a timed one.
     * What it does NOT do is hold the drive-impulse rule to barbell work.
     * [CountingPolicy.counterFor] gates on the prescribed tempo, on whether the
     * set is measured in seconds and on the exercise kind; it never reads
     * `travelRatio` or `sensorOnStack`, and a tempo is OPTIONAL on a
     * rep-prescribed set -- `docs/schemas/plan.schema.json` requires `reps` or
     * `duration_s` on a set and forbids a tempo only on the timed one. So a leg
     * curl, a pulldown or a single-leg press whose sets say `reps: 10` with no
     * tempo, recorded with an IMU connected, is [RepCounter.SENSOR] and counts
     * on impulse TODAY. This row does not have to widen for that to happen.
     *
     * What holds the corpus's machine captures away from this counter is their
     * TEMPO and nothing structural: every stack or machine capture the candidate
     * corpus scores carries a `-cues.csv` of metronome stroke words, so a
     * cadence ran on it and [RepCounter.METRONOME] counted it. On that shape the
     * cost is measured -- 2 calls against 10 performed on
     * `field-legcurl-1030-10rep`, 0 against 12 on `field-legcurl-1030-12rep`, 0
     * against 8 on `field-legpress-single-2011-8rep-s36-set07`, 0 against 8 on
     * `field-pullup-3010-8rep-s37-set09`, and over the 33 committed captures
     * that carry a truth 180 calls against 253 performed with an over-count of
     * 19, where the segmenter calls 108 with an over-count of 1.
     * `DriveImpulseCandidateTest` and `LiveCountDifferentialTest` hold those
     * figures.
     *
     * An UNTEMPO'D machine set is unmeasured rather than known-bad, which is the
     * exposure this row leaves open. The three committed cable captures with no
     * tempo code -- `field-cablerow-static-8rep`,
     * `field-facepull-static-12rep`, `field-pallof-static-12rep` -- carry no cue
     * track and no rep marks, so nothing scores them and nothing here says what
     * this counter would call on one. Whether the gate should NARROW, counting
     * on impulse only where the geometry is a barbell's, is an owner decision
     * and is tracked as issue #303 rather than settled in this KDoc.
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
