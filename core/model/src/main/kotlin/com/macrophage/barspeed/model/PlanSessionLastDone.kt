package com.macrophage.barspeed.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One recorded session as History stores it: which plan and which of its
 * sessions it ran, and when it started. Both names are null on a session
 * recorded without a plan.
 */
data class RecordedPlanSession(val planName: String?, val planSessionName: String?, val startedAtMs: Long)

/**
 * When each session of a plan was last done, read from History (UX review
 * 2026-11-06, plans F7). Displays recorded data; decides nothing about which
 * session comes next.
 *
 * Matched on the plan's NAME and the session's name, which is what a session
 * row stores. A revised plan imported under the same name therefore carries
 * its predecessor's "last done" dates for sessions of the same name -- which
 * is the answer a lifter running "Day A" of week 5 after "Day A" of week 4
 * wants.
 */
object PlanSessionLastDone {
    /** Session name to the start of its most recent recorded run; sessions never done are absent. */
    fun of(planName: String, history: List<RecordedPlanSession>): Map<String, Long> = history
        .filter { it.planName == planName && it.planSessionName != null }
        .groupBy { it.planSessionName!! }
        .mapValues { (_, runs) -> runs.maxOf { it.startedAtMs } }

    /** "Last done Tue 4 Nov", in the zone and locale given. */
    fun label(startedAtMs: Long, zone: ZoneId, locale: Locale): String {
        val day = Instant.ofEpochMilli(startedAtMs).atZone(zone)
        return "Last done " + DateTimeFormatter.ofPattern("EEE d MMM", locale).format(day)
    }
}
