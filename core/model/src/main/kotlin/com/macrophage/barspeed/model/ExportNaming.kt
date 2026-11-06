package com.macrophage.barspeed.model

import java.time.LocalDate

/**
 * The names the app gives what it exports, in one place a test reaches.
 *
 * THE RAW ZIP LEADS WITH THE SESSION'S START AND CARRIES NO APP VERSION
 * (UX review 2026-11-06, history-export F4). It was
 * `BarSpeed-v<version>-<start>-raw.zip`, so the same session exported again
 * after an update got a second name, a folder of these sorted by version
 * before date (and "v0.1.10" before "v0.1.9"), and a reader that takes every
 * zip in a folder counted a re-exported session twice. The build that wrote
 * the zip is still inside it, as `meta.json` `appVersion`; the name only has
 * to say which session it is. [sessionTimestamp] supplies the start and
 * carries the reasoning for its zone, its fallback and its seconds; seconds
 * are kept here, not cut to minutes, because they are what makes two
 * sessions' names distinct without a row id.
 *
 * THE JSON EXPORTS KEEP THE VERSION. A bare `session.json` has no
 * `meta.json` beside it, so its filename is the only place that names the
 * build that wrote it, and the published schema and the plan prompt say so.
 */
object ExportNaming {
    /** Longest plan session name slug a raw zip name carries; a longer one is cut. */
    const val MAX_SLUG_LENGTH = 24

    /**
     * `BarSpeed-<start>[-<plan session slug>]-raw.zip`, e.g.
     * `BarSpeed-2026-11-06_093012-Lower-A-raw.zip`. [timestamp] is
     * [sessionTimestamp]'s output; an ad-hoc session (no plan session name)
     * gets no slug.
     */
    fun rawZipName(timestamp: String, planSessionName: String?): String {
        val slug = slug(planSessionName)
        return if (slug == null) "BarSpeed-$timestamp-raw.zip" else "BarSpeed-$timestamp-$slug-raw.zip"
    }

    /** `BarSpeed-v<version>-<start>-<suffix>`: the JSON exports' names, unchanged. */
    fun versionedName(appVersion: String, timestamp: String, suffix: String): String =
        "BarSpeed-v$appVersion-$timestamp-$suffix"

    /** The one archive "Export all sessions" writes: `BarSpeed-all-<yyyy-MM-dd>.zip`. */
    fun allSessionsArchiveName(today: LocalDate): String = "BarSpeed-all-$today.zip"

    /**
     * A plan session name made safe for a filename: ASCII letters and digits
     * kept, every other run of characters one `-`, no leading or trailing
     * `-`, at most [MAX_SLUG_LENGTH] characters. Null where nothing is left,
     * so a name of only punctuation adds no empty segment.
     */
    fun slug(name: String?): String? {
        if (name == null) return null
        val dashed = name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return dashed.take(MAX_SLUG_LENGTH).trimEnd('-').ifEmpty { null }
    }

    /**
     * [name], or `<stem>-2.zip`, `<stem>-3.zip`, ... where [taken] already
     * holds it; the returned name is added to [taken]. The archive's entries
     * must be distinct, and two sessions cannot normally share a start
     * second, so this only fires on rows that do (an imported or repaired
     * database); it keeps both sessions rather than letting one replace the
     * other.
     */
    fun uniqueEntryName(name: String, taken: MutableSet<String>): String {
        if (taken.add(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (!taken.add("$stem-$n$ext")) n++
        return "$stem-$n$ext"
    }

    /**
     * The one line "Export all sessions" ends with. [failed] names each
     * session that could not be written, as the lifter would recognise it
     * (its date); at most [MAX_NAMED_FAILURES] are listed.
     */
    fun allSessionsSummary(exported: Int, failed: List<String>): String {
        val noun = if (exported == 1) "session" else "sessions"
        if (failed.isEmpty()) return "Exported $exported $noun"
        val total = exported + failed.size
        val named = failed.take(MAX_NAMED_FAILURES).joinToString(", ")
        val more = if (failed.size > MAX_NAMED_FAILURES) ", and ${failed.size - MAX_NAMED_FAILURES} more" else ""
        return "Exported $exported of $total sessions. ${failed.size} failed: $named$more"
    }

    /** How many failed sessions [allSessionsSummary] names before it says "and N more". */
    const val MAX_NAMED_FAILURES = 3
}
