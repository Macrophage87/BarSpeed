package com.macrophage.barspeed.dsp

/**
 * A set's rep-mark track, read from the `-reps.csv` beside a capture.
 *
 * A MARK IS NOT ALWAYS A TAP. `SessionExport.repMarks` holds "the instant the
 * lifter tapped the rep button, or the instant the voice guide called a rep"
 * -- the file records WHEN a rep was marked and says nothing about which of
 * the two marked it. On a guided set the guide marks, and every capture
 * committed to this repository is a guided set.
 *
 * [RepMarkTrackTest] measures what that means for this corpus: all 103 marks
 * across the thirteen captures land within 1 ms of a row of the same
 * capture's own `-cues.csv`, and 94 of them share its exact millisecond. So a
 * mark here carries nothing the cue track does not already carry, and reading
 * one as per-rep truth independent of the metronome is the mistake this
 * paragraph exists to prevent.
 *
 * The marks a spoken count actually needs to be scored against are a
 * STRAIGHT-REP set's, where no guide runs and the lifter's own taps are the
 * only marks there are. No such capture exists; issue #145 names it F1 and it
 * is still owed.
 *
 * Same epoch-ms arrival clock as [CueTrack] and the IMU stream, so the same
 * skew against the DSP's reconstructed clock applies -- see
 * [CueTrack.MAX_SKEW_MS] and [CueTrack.WINDOW_TOLERANCE_MS].
 */
internal object RepMarks {
    fun read(fixture: String): List<Long> {
        return RepMarks::class.java.getResourceAsStream("/$fixture-reps.csv")!!
            .readBytes()
            .decodeToString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("timestamp_ms") }
            .map { it.toLong() }
            .toList()
    }

    /**
     * The median mark-to-mark gap in milliseconds -- the set's own rep cycle,
     * taken from its own marks rather than from a prescribed tempo, so it
     * follows what the set was actually paced at.
     *
     * `gaps[gaps.size / 2]` of the sorted gaps -- the upper of the two middle
     * values on an even-length list. Written that way because it is character
     * for character what [BatchCueCoverageTest]'s `windows` takes off the
     * `Down` track, and a second convention here would make the two files'
     * windows differ for a reason no reader could see. Zero marks and one mark
     * both give null: a cycle needs two.
     */
    fun cycleMs(fixture: String): Double? {
        val gaps = read(fixture).zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        return if (gaps.isEmpty()) null else gaps[gaps.size / 2]
    }
}
