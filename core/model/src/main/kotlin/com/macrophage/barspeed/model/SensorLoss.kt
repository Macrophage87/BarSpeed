package com.macrophage.barspeed.model

/**
 * What one set has said so far about its bar sensors going quiet, and whether
 * they are quiet now (UX review 2026-11-06, BLE F1).
 *
 * [lost] drives the in-set banner; [lostSaid] and [backSaid] make each word
 * once-per-set, so a link that flaps does not talk over the lifter's set.
 */
data class SensorLossWatch(
    val lost: Boolean = false,
    val lostSaid: Boolean = false,
    val backSaid: Boolean = false,
)

/** One tick's answer: the watch to carry on with, and the word to say now, if any. */
data class SensorLossStep(val watch: SensorLossWatch, val cue: String?)

/**
 * The bar sensor dying mid-set, said out loud (UX review 2026-11-06, BLE F1).
 *
 * On a sensor-counted set a unit whose battery dies or whose link drops at
 * rep 3 stops delivering frames, so the count freezes and the voice stops --
 * which sounds exactly like the detector missing reps. The only sign was a
 * small dot in the top bar that cannot be read from the bench. This is the
 * rule that turns it into one spoken "Sensor lost" and a banner pointing at
 * the `+1 REP` correction, and one "Sensor back" if frames resume.
 *
 * NO NEW CLOCK. "Quiet" is [ArmedSilencePolicy.deliveryOf]'s answer -- no
 * frame inside [ArmedSilencePolicy.SILENT_AFTER_MS], or no link -- read off
 * the same last-frame instants the READY card and the dots read. The caller
 * asks once a second from the set's own elapsed-time tick.
 *
 * ALL ARMED UNITS, NOT ANY. With two sensors the live feed moves to the one
 * still delivering (#207, #280), so one unit going quiet does not stop the
 * count; only the only unit, or both, does.
 *
 * [ArmedDelivery.TOO_SOON] is not lost: the app does not know yet, and
 * accusing a link that is two seconds into its connect is the false claim
 * [ArmedSilencePolicy] exists to avoid.
 */
object SensorLossPolicy {
    const val LOST_CUE = "Sensor lost"
    const val BACK_CUE = "Sensor back"

    /** The in-set banner, naming the control that counts by hand. */
    const val BANNER = "Sensor lost: tap +1 REP to count by hand"

    private val quiet = setOf(
        ArmedDelivery.NOT_LINKED,
        ArmedDelivery.LINK_WITHOUT_SENSOR,
        ArmedDelivery.LINKED_SILENT,
    )

    /**
     * What each link the set armed is doing at [nowMs]: the analysed link
     * always, the second only when the set armed one ([secondaryArmed]).
     */
    fun deliveries(links: ArmedLinks, secondaryArmed: Boolean, nowMs: Long): List<ArmedDelivery> = listOfNotNull(
        ArmedSilencePolicy.deliveryOf(links.analysedState, links.analysedFrameAtMs, links.analysedArmedAtMs, nowMs),
        if (secondaryArmed) {
            ArmedSilencePolicy.deliveryOf(
                links.secondaryState,
                links.secondaryFrameAtMs,
                links.secondaryArmedAtMs,
                nowMs,
            )
        } else {
            null
        },
    )

    /** Whether every armed unit is quiet. An empty list arms nothing and loses nothing. */
    fun allQuiet(deliveries: List<ArmedDelivery>): Boolean = deliveries.isNotEmpty() && deliveries.all { it in quiet }

    /**
     * The next tick. Only a set the SENSOR counts is watched: on any other the
     * count is the lifter's or the guide's and a lost sensor does not stop it.
     */
    fun step(watch: SensorLossWatch, sensorCounted: Boolean, deliveries: List<ArmedDelivery>): SensorLossStep {
        if (!sensorCounted) return SensorLossStep(watch, null)
        val lostNow = allQuiet(deliveries)
        return when {
            lostNow && !watch.lost ->
                SensorLossStep(watch.copy(lost = true, lostSaid = true), LOST_CUE.takeIf { !watch.lostSaid })
            !lostNow && watch.lost ->
                SensorLossStep(watch.copy(lost = false, backSaid = true), BACK_CUE.takeIf { !watch.backSaid })
            else -> SensorLossStep(watch, null)
        }
    }
}
