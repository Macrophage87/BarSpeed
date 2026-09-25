package com.macrophage.barspeed.model

/**
 * The rest between two sets as two clock instants measure it, and whether
 * that is short of the rest the plan prescribed (#157).
 *
 * NOTHING CALLS THIS YET. It is the pure half of the measured rest the #157
 * design proposes to publish as `restMeasured_s`; the lane that stores the
 * rest-start instant on the set row and exports the figure wires it. Today
 * the set row does not store the instant its rest ran from.
 *
 * ## What the measurement is
 *
 * The seconds from one set's rest-start instant to the next set's START tap.
 * It includes walking to the next station and setting up. The owner, on where
 * rest ends, 2026-09-25: "I consider rests a minimum. If it takes more time
 * to setup I do."
 *
 * ## A prescribed rest is a FLOOR
 *
 * A measured rest longer than the prescription is normal and is not a
 * deviation: setup takes what it takes, and so does waiting for equipment.
 * The owner, the same day: "With a gym that a lot of people are using, timing
 * can't easily be predicted in advance." So [shortOfPrescribed] is true only
 * where the measured rest is SHORTER than the prescription, and nothing here
 * reads a long rest as anything at all.
 */
object RestMeasurePolicy {
    /**
     * Seconds from [restStartMs] to [nextStartMs], rounded to the nearest
     * tenth, half up.
     *
     * Null where either instant is absent: a set with no stored rest-start,
     * or the last set of a session, which no START followed. Null, never
     * negative, where the next START is before the rest-start: two clock
     * instants in that order describe no rest, and a negative figure would be
     * published as one. Equal instants are a measured 0.0, not an absence.
     */
    fun measuredS(restStartMs: Long?, nextStartMs: Long?): Double? {
        if (restStartMs == null || nextStartMs == null) return null
        val elapsedMs = nextStartMs - restStartMs
        if (elapsedMs < 0) return null
        return (elapsedMs + HALF_TENTH_MS) / TENTH_MS / TENTHS_PER_S
    }

    /**
     * Whether a measured rest fell short of the prescribed minimum.
     *
     * True only where [measuredS] is strictly less than [prescribedS]. A rest
     * exactly at the prescription met it, and a longer one is setup or gym
     * traffic, never a deviation. False where either figure is absent: an
     * unmeasured rest has nothing to judge, and a rest the plan did not
     * prescribe has no minimum to fall short of. The countdown's own default,
     * used where the plan names no rest, is the app's figure and not the
     * plan's; whether a caller passes it here is the wiring lane's decision.
     */
    fun shortOfPrescribed(measuredS: Double?, prescribedS: Int?): Boolean =
        measuredS != null && prescribedS != null && measuredS < prescribedS

    private const val TENTH_MS = 100L
    private const val HALF_TENTH_MS = 50L
    private const val TENTHS_PER_S = 10.0
}
