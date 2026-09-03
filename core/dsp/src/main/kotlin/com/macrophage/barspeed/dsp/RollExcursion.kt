package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far the sensor's roll swept while the set was being WORKED, on a signal
 * unwrapped across the +-180 degree discontinuity (#133).
 *
 * The archive's `rollExcursion_deg` is what an outside analysis uses to decide
 * whether a set's kinematics can be read at all: a rail-guided load barely
 * rotates and integrates cleanly, while a mount that tumbles leaks gravity into
 * every sample. Until this type the figure was `max(roll) - min(roll)` over
 * every row in the file, and that has two independent faults.
 *
 * ## Fault one: the range saturates
 *
 * `roll_deg` is bounded to (-180, 180], so a max-minus-min over it cannot
 * exceed 360 whatever the mount did. A capture that crosses the discontinuity
 * once already reports close to the ceiling, and above that the figure stops
 * telling a heavy rotation from an enormous one. Measured on the archived
 * captures by recomputation over the `roll_deg` column, and asserted against
 * this code by `RollExcursionFieldTest`: field-36 set 1 reports 358.6 wrapped
 * against 909.0 unwrapped, and its set 5 reports 360.0 against 515.2. Two sets
 * three degrees apart on the published figure differ by 394 degrees on what the
 * mount actually did.
 *
 * [unwrap] removes the discontinuity by accumulating differences: a step larger
 * than half a turn is read as the signal having crossed the boundary rather
 * than the mount having jumped, which is the only reading available on a signal
 * sampled far faster than the mount can rotate. THAT ASSUMPTION IS WHAT THIS
 * BUYS AND WHAT IT COSTS: at the archived rates -- around 99 rows per second --
 * a genuine half-turn between two consecutive rows would need over 17,000
 * degrees per second, well beyond the sensor's own reported range, so on these
 * captures the reading is safe. On a capture with a long dropout across which
 * the mount really did turn past 180 degrees, it is not, and the excursion
 * comes out short by a whole turn per crossing. Nothing in the stream can tell
 * those apart, so nothing here pretends to.
 *
 * ## Fault two: the interval is not the set
 *
 * The whole file is not the set. It opens at the lifter's tap, seconds before
 * the work starts, and on a guided set it keeps recording after the app has
 * called the set over -- the sensor is then put down, unclipped or carried
 * while the load is racked, which is the same tail issue #125 is about.
 *
 * Both bounds are already on the record: `PrepWindow.workStartedAtMs` opens the
 * working interval and [SetEnd] closes it at the terminal cue -- the same
 * instant [SetEnd] bounds the rep list at, deliberately read through that type
 * rather than restated, so the archive cannot come to hold two answers to
 * "when did this set end".
 *
 * Measured on field-37 (2026-09-02, app 0.1.48), recomputed from each set's
 * `imu-a` CSV and confirmed against this code by `RollExcursionFieldTest`: set
 * 3 reports 92.9 over the file against 54.0 over the working window and set 4
 * reports 86.7 against 63.7 -- and neither has a single sample after its
 * terminal cue, so on those two the entire excess is the PREP. A fix that
 * trimmed only the tail would have left both of them wrong.
 *
 * ## What is not claimed
 *
 * This is a measure of how the STREAM's reported roll moved. It is not a claim
 * about the barbell, the lifter or the mount: `roll_deg` is the sensor's own
 * fused attitude estimate, nothing here observes the hardware, and no device
 * has been run against this code.
 */
object RollExcursion {
    /** Half a turn, in degrees: the step above which [unwrap] reads a crossing. */
    private const val HALF_TURN_DEG = 180.0

    /** A whole turn, in degrees. */
    private const val FULL_TURN_DEG = 360.0

    /**
     * Which interval a figure was measured over, published beside it.
     *
     * Four values because there are two independent bounds and either can be
     * missing, so the reader is told which of the four cases a given set is in
     * rather than being left to infer it from keys elsewhere in the document.
     * A set recorded before prep windows existed, or one whose voice was off,
     * still gets a figure -- withholding it would delete the only rotation
     * measure those captures have -- but it gets one that says what it covers.
     */
    enum class Basis(val published: String) {
        /** Both bounds known: work start to terminal cue. */
        WORKING_WINDOW("workingWindow"),

        /** Work start known, nothing said when the set ended. */
        FROM_WORK_START("fromWorkStart"),

        /** Terminal cue known, no prep window stored. */
        TO_TERMINAL_CUE("toTerminalCue"),

        /** Neither bound known, so the figure covers the whole capture. */
        WHOLE_CAPTURE("wholeCapture"),
    }

    /**
     * A measured excursion and the interval it covers.
     *
     * The two travel together on purpose. A degree figure whose window is
     * stated somewhere else is a figure a reader can quote against the wrong
     * interval, which is the defect this type exists to end.
     */
    data class Measured(val degrees: Double, val basis: Basis)

    /**
     * [rollDeg] with the +-180 discontinuity removed, first sample unchanged.
     *
     * The result is NOT bounded and is not an attitude: it is a continuous
     * signal whose differences are the same as the input's shortest-arc
     * differences, suitable for taking a range over and for nothing else.
     *
     * An empty input gives an empty result. A step of exactly half a turn is
     * left alone rather than being called a crossing, because at exactly 180
     * degrees the two readings are equidistant and there is no shortest arc to
     * prefer; the choice matters only on a synthetic signal, since a real one
     * hitting it exactly is a measure-zero event.
     */
    fun unwrap(rollDeg: List<Double>): List<Double> {
        if (rollDeg.isEmpty()) return emptyList()
        val out = ArrayList<Double>(rollDeg.size)
        out += rollDeg.first()
        var offset = 0.0
        var previous = rollDeg.first()
        for (index in 1 until rollDeg.size) {
            val raw = rollDeg[index]
            val step = raw - previous
            if (abs(step) > HALF_TURN_DEG) {
                offset -= FULL_TURN_DEG * (step / FULL_TURN_DEG).roundToInt()
            }
            previous = raw
            out += raw + offset
        }
        return out
    }

    /**
     * How far [samples]' roll swept over the working window, or null when the
     * window holds too little to say.
     *
     * [workStartedAtMs] is `PrepWindow.workStartedAtMs` or null where the set
     * stored no window; [end] is [SetEnd.of] over the set's cue track. Both
     * bounds are INCLUSIVE and are on the samples' own arrival clock, so no
     * conversion happens here and none can be got wrong.
     *
     * Null rather than 0.0 on fewer than two samples in the window, and the
     * distinction is the whole reason this returns a nullable: a single sample
     * has a maximum equal to its minimum, so a range over it reads as "this set
     * did not rotate" when what happened is that nothing measured whether it
     * did -- and that is the most reassuring answer available to a reader
     * deciding whether to trust the set. A window that excludes everything is
     * the same fact and gets the same answer.
     */
    fun of(samples: List<ImuSample>, workStartedAtMs: Long?, end: SetEnd): Measured? {
        val endedAtMs = (end as? SetEnd.Cued)?.atMs
        val windowed =
            samples.filter { sample ->
                (workStartedAtMs == null || sample.timestampMs >= workStartedAtMs) &&
                    (endedAtMs == null || sample.timestampMs <= endedAtMs)
            }
        if (windowed.size < 2) return null
        val unwrapped = unwrap(windowed.map { it.rollDeg })
        return Measured(unwrapped.max() - unwrapped.min(), basisOf(workStartedAtMs, endedAtMs))
    }

    private fun basisOf(workStartedAtMs: Long?, endedAtMs: Long?): Basis = when {
        workStartedAtMs != null && endedAtMs != null -> Basis.WORKING_WINDOW
        workStartedAtMs != null -> Basis.FROM_WORK_START
        endedAtMs != null -> Basis.TO_TERMINAL_CUE
        else -> Basis.WHOLE_CAPTURE
    }
}
