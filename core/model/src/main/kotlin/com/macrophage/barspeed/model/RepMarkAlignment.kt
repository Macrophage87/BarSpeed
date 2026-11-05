package com.macrophage.barspeed.model

import kotlin.math.abs

/**
 * Whether a set's stored rep list is the reps its voice guide counted, judged
 * IN TIME against the guide's own marks rather than by length alone (#246).
 *
 * ## The defect this exists for
 *
 * `repMetricsComplete` was count equality: the segmenter's number of reps
 * against the number the set records. A segmenter that misses reps and finds
 * as many movements that are not reps passes it. #246 measured that on two
 * field-38 pushdowns (12 of 12 and 14 of 14, each with cycles holding no
 * detection and others holding two), and two committed captures show the same
 * shape: `field-legcurl-1030-12rep` and `-12rep-b` resolve 12 detections
 * against 12 cued cycles with some cycles empty and others doubled.
 * `RepMarkAlignmentFieldTest` in `:core:dsp` measures all of them.
 *
 * ## The rule
 *
 * Sort both lists and pair the i-th detection with the i-th mark. Each pair's
 * RESIDUAL is the drive's start minus the mark. On a list that is the guide's
 * reps every residual is the same phase of the same cycle, give or take the
 * lifter's timing; on a list where a rep was missed and another invented, every
 * pair between the two is off by a whole cycle. So the list is aligned when
 * every residual lies within HALF A CYCLE of the residuals' median, the cycle
 * being the median gap between consecutive marks -- the width
 * `BatchCueCoverageTest` already scores the batch path's windows at.
 *
 * WHY THE MEDIAN AND NOT THE MARK. Where in a cycle the guide advances its
 * count is a property of the guide (`GuidedCadenceRunner.onRepCounted`, after
 * the beat a plan names), and where in a cycle the lifter starts the drive is a
 * property of the lift: concentric-first work starts it at the cycle boundary,
 * eccentric-first work most of a cycle later. Windows hung on the mark itself
 * split concentric-first reps across a boundary --
 * `field-legpress-single-2011-8rep-s36-set07` scores one empty and one doubled
 * window that way while its eight residuals are a smooth drift. The median
 * places the window where this set's reps actually sit, so the rule does not
 * depend on which cue a mark coincides with, and a constant offset of every
 * mark changes nothing.
 *
 * WHY HALF A CYCLE. On the seven committed captures whose counts agree, the
 * five aligned ones deviate at most 0.42 of a cycle from their median (1681 ms
 * of 4004, `field-inclinepress-3010-12rep-s38-set02`, whose lifter drifted a
 * second and a half against the guide) and the two cancelling ones at least
 * 0.90 (4493 ms of 5005). A missed rep and an invented one shift every pair
 * between them by a whole cycle, so their residuals sit a cycle from the rest
 * and half a cycle is the boundary that is furthest from both.
 *
 * WHAT IT CANNOT CATCH. A list shifted by one whole cycle -- a movement before
 * the first cycle and the last rep missed -- moves every residual alike, which
 * is exactly what a guide counting at a different phase looks like, so it
 * reads aligned; the head of that shape is what the work-start bound (#245)
 * removes before the list is built. A miss and an invention that split the
 * list exactly in half put the median between the two blocks, half a cycle
 * from each, and with the lifter's timing on top either answer can come out.
 * And it says nothing about a detection that IS a rep but measured it badly --
 * that is what the per-rep bounds (`romBounded`, `artefactSamples`) are for.
 *
 * ## What it is NOT
 *
 * A judgement of a tapped set. A lifter's tap follows the rep it counts by a
 * lag nothing bounds, and a lifter can tap several reps at once, so a mark on
 * a hand-counted set is not a phase of anything. Only the guide's marks are a
 * grid. The caller decides which marks it hands in; see [repMetricsComplete].
 */
object RepMarkAlignment {
    /** Two marks are the fewest that give a cycle to measure a residual against. */
    const val MIN_MARKS = 2

    /**
     * True when each detection sits in its own cycle of the guide's grid, false
     * when some do not, null when the question cannot be asked: fewer than
     * [MIN_MARKS] marks, a different number of detections from marks, or marks
     * that do not advance.
     *
     * @param marks the instants the guide counted a rep, epoch ms.
     * @param driveStartsMs the instant each detection's drive began, on the
     *   same clock -- the arrival stamp of the sample, never the DSP's
     *   reconstructed time base.
     */
    fun aligned(marks: List<Long>, driveStartsMs: List<Long>): Boolean? {
        if (marks.size < MIN_MARKS || driveStartsMs.size != marks.size) return null
        val m = marks.sorted()
        val d = driveStartsMs.sorted()
        val cycleMs = upperMedian(m.zipWithNext { a, b -> b - a })
        if (cycleMs <= 0L) return null
        val residuals = d.indices.map { d[it] - m[it] }
        val centre = upperMedian(residuals)
        return residuals.all { abs(it - centre) * 2 < cycleMs }
    }

    /**
     * `repMetricsComplete` as published from export 1.26: null when the
     * segmenter resolved nothing, false when its count differs from the
     * recorded one, and otherwise true unless the cadence marks show the
     * equal counts are a miss and an invention cancelling.
     *
     * [cadenceMarks] is EMPTY unless the set's count is the guide's -- a
     * `metronome` set; the caller decides, because only it knows who counted.
     * An empty list, a rep stored without its drive instant (every rep analysed
     * before #246's fix), or a mark count other than the detection count all
     * leave the answer at count equality: nothing stored can judge the order,
     * and the published description says true is then the counts and nothing
     * more.
     */
    fun repMetricsComplete(
        detections: Int,
        recordedReps: Int,
        cadenceMarks: List<Long>,
        driveStartsMs: List<Long?>,
    ): Boolean? {
        if (detections == 0) return null
        if (detections != recordedReps) return false
        val starts = driveStartsMs.filterNotNull()
        if (starts.size != driveStartsMs.size) return true
        return aligned(cadenceMarks, starts) != false
    }

    /** The upper of the two middle values on an even count, as the batch-coverage scorer takes its cycle. */
    private fun upperMedian(values: List<Long>): Long = values.sorted()[values.size / 2]
}
