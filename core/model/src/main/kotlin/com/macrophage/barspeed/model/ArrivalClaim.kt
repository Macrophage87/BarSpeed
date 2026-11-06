package com.macrophage.barspeed.model

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether a hand-off has arrived that nothing has announced yet, claimable
 * once (UX review 2026-11-06, plans F1).
 *
 * Held in the process beside the plan it describes, never in saved UI state:
 * a count saved by the screen outlives the process while the arrivals it
 * counted do not, and after a process death every later share compared below
 * the saved figure and was silently ignored. A flag that dies with the
 * process cannot disagree with the arrivals it describes.
 *
 * Several arrivals before a claim are one announcement: the lifter is told a
 * plan arrived, and the newest one is the plan waiting.
 */
class ArrivalClaim {
    private val unannounced = AtomicBoolean(false)

    fun arrive() {
        unannounced.set(true)
    }

    /** True for exactly one caller after each [arrive]; false otherwise. */
    fun claim(): Boolean = unannounced.getAndSet(false)
}
