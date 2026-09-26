package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample

/**
 * How many frames one unit's link DELIVERED per second over a set's working
 * window, and how often it handed a notification over (#321).
 *
 * DECLARED AND NOT YET MEASURED. [of] answers null for every stream, which is
 * what every document this app writes has always said about a unit's delivered
 * rate over the working window: nothing. Nothing calls it yet. The arithmetic
 * and its pins arrive with the fix.
 */
object DeliveredRate {
    /**
     * One unit's delivery over the window.
     *
     * [hz] is frames per second. [burstSpacingMs] is the median gap between
     * consecutive distinct arrival stamps, or null where the window holds only
     * one distinct stamp.
     */
    data class Measured(val hz: Double, val burstSpacingMs: Long?)

    /** Null for every stream until the fix; see the object's own KDoc. */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun of(samples: List<ImuSample>, workStartedAtMs: Long?, end: SetEnd): Measured? = null
}
