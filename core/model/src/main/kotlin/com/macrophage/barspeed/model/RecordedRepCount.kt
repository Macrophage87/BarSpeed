package com.macrophage.barspeed.model

/**
 * The two rep figures a finished set is written with.
 *
 * Two facts and not one, for the reason `autoFailed` and `tappedFailed` are two
 * facts: correcting a count must not erase the count that was corrected. The
 * sensor's own live figure is what a first session's hand count is scored
 * against, and a correction that overwrote it would destroy exactly the sets
 * where the sensor was wrong -- the informative ones.
 */
data class RecordedRepCount(
    /**
     * The count a PERSON or the guide states, or null where nobody stated one.
     *
     * Fills `CompletedSet.manualReps`, so a non-null value is what
     * `repsManual` is written from and what the stored `actualReps` becomes.
     * Null on a sensor-counted set the lifter did not correct.
     */
    val stated: Int?,
    /**
     * What the sensor's live detector counted, or null where no live counter
     * ran on this set.
     *
     * Non-null on exactly the sets [RepCounter.SENSOR] counted, INCLUDING a
     * set it counted zero reps on: zero is a count, and absence is the
     * separate state of no counter having run. That distinction is the whole
     * reason the export can name the source of a row's figure at all.
     */
    val live: Int?,
)

/**
 * Which rep figures a set carries, in the set and on the row.
 *
 * Lifted out of `RecordViewModel` because the expression it replaces --
 * `val manualReps = if (s.manualSet) s.manualReps else null` -- is the single
 * line that decided what the archive records a set as, in a module no test on
 * the CI path reaches.
 */
object RepCountPolicy {
    /**
     * The count a sensor-counted set stands at after [correctionDelta] taps of
     * `+1 REP`.
     *
     * An OFFSET rather than a replacement: the sensor goes on counting behind
     * the correction, so a lifter who adds the rep the detector missed at rep
     * 3 still sees 6 when the detector has called 5. Replacing the figure
     * would make every later sensor call look like a count going backwards.
     *
     * Floored at zero. Nothing in the app offers a negative tap mid-set today
     * -- the rest screen is where a count comes down -- so the floor guards a
     * caller rather than a lifter.
     */
    fun correctedCount(liveCount: Int, correctionDelta: Int): Int = (liveCount + correctionDelta).coerceAtLeast(0)

    /**
     * The count the in-set ring draws and the voice names, which are one
     * number by construction: both read this.
     *
     * That they are one number is issue #252's rule applied to the sensor's
     * counter. A ring drawn from one count while the voice says another reads
     * to the lifter as a lost rep.
     *
     * A set nothing counts ([RepCounter.NOBODY]) draws no count at all, and
     * the zero returned here is not a count -- the timed branch of the screen
     * never asks.
     */
    fun displayedCount(counter: RepCounter, tally: Int, liveCount: Int, correctionDelta: Int): Int = when (counter) {
        RepCounter.SENSOR -> correctedCount(liveCount, correctionDelta)
        RepCounter.MANUAL, RepCounter.METRONOME -> tally
        RepCounter.NOBODY -> 0
    }

    /**
     * What the row is written with.
     *
     * [tally] is the lifter's taps or the guide's own count -- the one field
     * both write into. [liveCount] is the sensor's live figure, and
     * [correctionDelta] the taps made against it.
     *
     * The two returned figures are never both non-null EXCEPT on a corrected
     * sensor set, which is the state the export publishes as `corrected`: the
     * stated figure is what the set is recorded as, the live one is what the
     * sensor said before the lifter disagreed.
     */
    fun recorded(counter: RepCounter, tally: Int, liveCount: Int, correctionDelta: Int): RecordedRepCount =
        when (counter) {
            RepCounter.MANUAL, RepCounter.METRONOME -> RecordedRepCount(stated = tally, live = null)
            RepCounter.SENSOR ->
                RecordedRepCount(
                    stated = if (correctionDelta != 0) correctedCount(liveCount, correctionDelta) else null,
                    live = liveCount,
                )
            RepCounter.NOBODY -> RecordedRepCount(stated = null, live = null)
        }
}
