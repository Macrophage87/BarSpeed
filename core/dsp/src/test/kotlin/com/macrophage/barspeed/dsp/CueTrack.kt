package com.macrophage.barspeed.dsp

/**
 * A metronome cue track, read from the `-cues.csv` beside a capture.
 *
 * A CUE IS AN INSTRUCTION, NOT A MEASUREMENT. The track records what the app
 * told the lifter to do and when it said so. It does not record what the
 * barbell did. The lifter was following the metronome, so the two are closely
 * coupled -- and "closely coupled" is exactly the kind of claim that hardens
 * into "identical" over a few rounds, so it is written down here rather than
 * left to be remembered.
 *
 * THE TRACK AND THE CAPTURE ARE ON TWO DIFFERENT CLOCKS. Cue timestamps come
 * from `GuidedCadenceRunner`, which speaks on wall-clock `delay`. The DSP does
 * not use arrival timestamps at all: `VelocityEstimator.estimate` builds
 * `timeS = DoubleArray(n) { it * dt }` from a single span-based rate, so every
 * phase boundary it reports is on a uniform RECONSTRUCTED clock. Converting a
 * cue time into DSP time therefore carries an error. See [MAX_SKEW_MS] and
 * [WINDOW_TOLERANCE_MS] for its measured size and what to do about it.
 */
internal object CueTrack {
    /**
     * Worst arrival-versus-reconstructed skew measured across the four barbell
     * captures, in milliseconds: `i * dt` against `(t[i] - t[0])`.
     *
     * It is zero at both ends of every capture by construction -- the rate is
     * `(n - 1) / span`, so the reconstructed clock is pinned to the arrival
     * clock at the first and last sample -- and it peaks in the middle, where
     * burst arrivals bunch samples that the uniform clock spreads evenly. The
     * worst is 105.3 ms on field-ohp-rotating-8rep-b, at 43.8 s into the set.
     */
    const val MAX_SKEW_MS = 105.3

    /**
     * Tolerance for matching a DSP-derived time against a cue time. DERIVED,
     * not chosen: it is [MAX_SKEW_MS] rounded up to the next 50 ms, which
     * leaves 1.42x margin over the worst skew actually measured.
     *
     * It also has to stay well under half the shortest cued phase, or windows
     * would overlap and a rep could match its neighbour. The shortest is the
     * 1.001 s concentric, so the ceiling is about 500 ms and this sits at 30%
     * of it.
     *
     * The failure this exists to prevent is specific: a rep falling just
     * outside its window because of accumulated skew reads as a MISSED rep,
     * and the clock artefact then gets attributed to the rep counter.
     */
    const val WINDOW_TOLERANCE_MS = 150.0

    data class Cue(val timestampMs: Long, val label: String)

    fun read(fixture: String): List<Cue> {
        return CueTrack::class.java.getResourceAsStream("/$fixture-cues.csv")!!
            .readBytes()
            .decodeToString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("timestamp_ms") }
            .map {
                val f = it.split(',', limit = 2)
                Cue(f[0].toLong(), f[1])
            }
            .toList()
    }

    /** Movement cues only, in order. */
    fun movement(fixture: String, label: String): List<Long> =
        read(fixture).filter { it.label == label }.map { it.timestampMs }

    /**
     * Reps the metronome CALLED, counted as complete Down-to-Up cycles.
     *
     * This is the figure that corroborates the per-set hand counts the
     * regression suite asserts as "performed". Those were bare numbers in a
     * test with nothing behind them until these tracks were committed.
     */
    fun calledReps(fixture: String): Int = movement(fixture, "Down").size
}
