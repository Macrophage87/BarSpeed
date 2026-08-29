package com.macrophage.barspeed.model

/**
 * When a hold or a carry ends, what it records when it does, and how a
 * genuine overage is added afterwards.
 *
 * [SetClockPolicy] answers which instant a timed set is measured FROM. This
 * answers the other end of it, which until #168 was not a decision at all: a
 * timed set ran until the lifter ended it by hand, so the end instant was
 * whenever the phone was back in their hands.
 *
 * ## Why the end is a decision
 *
 * The voice already announces the planned end -- the milestones, the final
 * ten digits and the terminal word all come from `TimedSetVoice`, against the
 * same clock this policy reads. So the lifter puts the bar down on the word,
 * and everything from there until the phone is out and tapped is recorded as
 * hold time. Every timed set is inflated by phone-retrieval time, in one
 * direction, systematically, and nothing in the record distinguishes the
 * inflation from a longer hold.
 *
 * The owner's own words, which is why the correction is where it is: *"for
 * holds and carries, just have them stop when they are supposed to end. Allow
 * for manual addition, but in most cases it just adds the time that it takes
 * to get the phone back out."*
 *
 * ## The one instant, and why nothing else may compute it
 *
 * [remainingS] is the only place the distance to the planned end is worked
 * out. The tick loop calls it once per second and hands the SAME value to the
 * voice and to [endsNow], so the word and the end cannot land on different
 * seconds. Two independent computations of one instant is the failure this
 * arrangement exists to make impossible: the set written before the word is
 * spoken, or the clock running a beat past it. `TimedSetVoiceCouplingTest`
 * asserts the two agree across a range rather than at the single point.
 *
 * ## What this cannot check
 *
 * That `:app` calls it once per second, from the set's own clock, and ends
 * the set when it says so. `:app` has one test file and none of it reaches a
 * coroutine or a composable, so that half is compile- and lint-gated only,
 * and is verified on the bench instead.
 */
object TimedSetEndPolicy {
    /**
     * Fraction of the prescription a timed set must reach to count as
     * delivered.
     *
     * The canonical copy. `TIMED_CLOSE_ENOUGH_FRACTION` in `:app` is declared
     * from this one, so there is a single number rather than two that agree
     * today. It is a `const val` deliberately: `:app`'s unit tests run on JDK
     * 17 against `:core:model` classes compiled to class-file version 65, so
     * a `:app` test that caused this object to be LOADED would die with
     * `UnsupportedClassVersionError` before asserting anything. A const is
     * inlined into the caller's constant pool at compile time and loads
     * nothing -- see `PlanQueueTest`'s KDoc, which found that trap the hard
     * way.
     */
    const val CLOSE_ENOUGH_FRACTION = 0.9

    /**
     * Seconds one tap of the post-set duration correction moves the recorded
     * hold by.
     *
     * Five rather than one, because the thing being corrected is the walk
     * back to the phone: the owner's estimate of it is "the time that it
     * takes to get the phone back out", which is seconds to tens of seconds,
     * not one second. A one-second step would need ten taps to say what one
     * tap says here, on a rest screen with a countdown running.
     */
    const val CORRECTION_STEP_S = 5

    /**
     * Distance in seconds from where the set's clock is now to the planned
     * end, or null when no duration was prescribed.
     *
     * Null is the ad-hoc timed set: the lifter started a hold with no target,
     * so there is no planned end, no countdown to speak and nothing to end
     * the set at. Absence, not zero -- a zero here would end the set on its
     * first tick.
     *
     * A prescription of zero or less is treated as no prescription for the
     * same reason: it names no instant a hold could reach.
     */
    fun remainingS(elapsedS: Int, targetS: Int?): Int? = targetS?.takeIf { it > 0 }?.let { it - elapsedS }

    /**
     * Whether the set's clock has reached the planned end, given the [remainingS]
     * this tick.
     *
     * Takes the already-computed remainder rather than the elapsed seconds and
     * the target, so a caller cannot pass one pair here and a different pair
     * to the voice. That is the whole reason for the shape.
     *
     * `<= 0` rather than `== 0`: a tick loop that misses a second -- the
     * process paused, the coroutine descheduled -- must still end the set,
     * one tick late, rather than sail past the target and never stop.
     */
    fun endsNow(remainingS: Int?): Boolean = remainingS != null && remainingS <= 0

    /**
     * Seconds the set records.
     *
     * [measuredS] is what the clock actually measured, from [SetClockPolicy].
     * [autoEnded] says the set ended because [endsNow] said so rather than
     * because the lifter ended it.
     *
     * A set that ran to its planned end records the seconds it was working
     * to exactly -- [targetS], which is the plan's declaration unless the
     * lifter changed the hold -- and nothing else does.
     *
     * Not a rounding nicety. The tick loop counts ticks and [measuredS] comes
     * off `System.currentTimeMillis()` deltas, so the two disagree by whatever
     * the dispatcher did: `delay(1_000)` drifts positive, and a sixty-tick
     * hold measures 60 or 61 depending on the scheduler. Recording 61 for a
     * 60 s prescription says the lifter carried it a second past target, on
     * every set, for a reason that has nothing to do with the lifter. A paused
     * process drifts the other way and records a hold that ran to its word as
     * having fallen short of it.
     *
     * Recording [targetS] is honest here precisely BECAUSE the app ended the
     * set: the instant is the app's, announced a second earlier by the same
     * figure, so that figure is the measurement of an event the app itself
     * timed. It is not honest anywhere else, which is why the
     * lifter's own end returns the measurement untouched -- a hold stopped
     * short is never rounded up to what it was asked for.
     *
     * A hold with no prescription records its measurement whatever [autoEnded]
     * says, because there is no target to substitute and inventing one is the
     * only alternative.
     */
    fun recordedSeconds(measuredS: Int, targetS: Int?, autoEnded: Boolean): Int =
        if (autoEnded && targetS != null) targetS else measuredS

    /**
     * Whether a recorded hold fell short of its prescription.
     *
     * A restatement of the rule the app already applies at the set write, in
     * a module where it can be asserted. Absent figures are not shortfalls:
     * a set with no prescription cannot fall short of one, and a set carrying
     * no measured seconds has nothing to judge.
     */
    fun fellShort(recordedS: Int?, plannedS: Int?): Boolean {
        if (plannedS == null || recordedS == null) return false
        return recordedS < (plannedS * CLOSE_ENOUGH_FRACTION).toInt()
    }

    /**
     * The recorded seconds after one tap of the post-set correction.
     *
     * Floored at zero. Negative seconds are not a hold that ran backwards:
     * `duration_s` is published with `"minimum": 0`, and a negative would make
     * every downstream comparison meaningless. The zero it floors to is a
     * measured zero -- the set happened and lasted no time worth recording --
     * not an absence, which is why it is a number and not a null.
     */
    fun adjustedSeconds(currentS: Int, deltaS: Int): Int = (currentS + deltaS).coerceAtLeast(0)
}
