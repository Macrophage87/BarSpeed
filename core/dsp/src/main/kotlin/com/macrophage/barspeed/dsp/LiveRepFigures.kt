package com.macrophage.barspeed.dsp

/** One live bar's figures: the drive run's mean and peak velocity, m/s. */
data class RepFigure(val meanMps: Double, val peakMps: Double)

/**
 * The live per-rep bars of a sensor-counted set: ONE ENTRY PER REP THE COUNT
 * STANDS AT, so the bars under the count are as many as the number the voice
 * just said. UX review 2026-11-06, dsp-core F1.
 *
 * The bars used to be `LiveSetState.repMeanVelocities` and
 * `repPeakVelocities`, which [StreamingSetTracker]'s own pairing rule fills.
 * That rule was retired from the count by #301/#305, and on the field-43
 * deadlifts it pairs 3, 1 and 2 reps where the voice says 5, 5 and 5.
 *
 * Here a bar is made at each [RepCall.Speak]: the figures of the tracker's
 * qualified drive run that overlaps the call's [RepCall.Speak.drive] most, or
 * a null entry -- a rep with no figure -- where no unclaimed run overlaps it
 * or the call carries no drive. A run gives at most one bar. A `+1 REP` tap
 * adds a null entry, because the lifter stated a rep and the sensor measured
 * nothing for it. Null is absence, never a low velocity.
 *
 * WHAT THE FIGURE IS: the velocity the tracker integrated over that run, with
 * whatever drift its integrator carried ([LiveSetState.countTrusted]). Making
 * the bar belong to the right rep does not make its height any more right.
 */
class LiveRepFigures {
    private class Bar(val figure: RepFigure?, val fromSensor: Boolean)

    private val bars = mutableListOf<Bar>()
    private var sensorCalls = 0
    private var claimedUntilS = Double.NEGATIVE_INFINITY

    /** One entry per rep, in the order the count reached them; null where there is no figure. */
    val figures: List<RepFigure?>
        get() = bars.map { it.figure }

    /** A new set: no bars. */
    fun clear() {
        bars.clear()
        sensorCalls = 0
        claimedUntilS = Double.NEGATIVE_INFINITY
    }

    /**
     * The live counter spoke [call]; [runs] is the tracker's
     * [StreamingSetTracker.recentDriveRuns] at that sample.
     *
     * [RepCall.Speak.count] is a running total and may jump by more than one;
     * the reps it skipped get a null entry each, and the call's own drive the
     * last one.
     */
    fun onCall(call: RepCall.Speak, runs: List<StreamingSetTracker.DriveRun>) {
        while (sensorCalls < call.count - 1) {
            bars += Bar(null, fromSensor = true)
            sensorCalls++
        }
        if (call.count <= sensorCalls) return
        bars += Bar(call.drive?.let { match(it, runs) }, fromSensor = true)
        sensorCalls++
    }

    /** One `+1 REP` correction: a rep with no figure. */
    fun onCorrection() {
        bars += Bar(null, fromSensor = false)
    }

    /**
     * The detector was rebuilt mid-set and its count restarts at 0, while the
     * lifter's corrections are kept (#280): the sensor's bars go, the
     * corrections' stay.
     */
    fun onRebuild() {
        bars.removeAll { it.fromSensor }
        sensorCalls = 0
        claimedUntilS = Double.NEGATIVE_INFINITY
    }

    /** Not matched yet: every sensor bar has no figure until the run matching lands. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    private fun match(drive: DriveWindow, runs: List<StreamingSetTracker.DriveRun>): RepFigure? = null
}
