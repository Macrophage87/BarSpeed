package com.macrophage.barspeed.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A session's own start instant, rendered as a filename-safe, sortable
 * timestamp -- issue #29's own export names used a database row id
 * (`session12`), meaningless to a lifter and useless for sorting a folder
 * full of them. This is what replaces it.
 *
 * THE SESSION'S OWN START TIME, IN THE ZONE IT WAS RECORDED IN -- not the
 * time of the export, and not the device's current zone. Exporting the same
 * session twice must produce the same name, which rules out the export
 * instant; and #75 landed [RecordedTimeZone] specifically so a session
 * recorded in another zone reads in the zone it happened in, not whatever
 * zone the device is in when someone later looks at it. Using the device's
 * current zone here would re-introduce that defect one layer up, in the one
 * place left that could still do it.
 *
 * No colons: `yyyy-MM-dd_HHmmss`, not ISO 8601's `T18:42:32`, because this
 * string is used as a literal filename twice -- once under `cacheDir` for
 * the share sheet, once as a SAF suggested name -- and a colon is illegal on
 * Windows and hostile on Android. Zero-padded and fixed-width, so a folder
 * of these sorts correctly as plain text with no parsing required.
 *
 * SECONDS, NOT MINUTES. Two sessions cannot start in the same SECOND on one
 * device -- starting one is a deliberate, sequential user action, and this
 * is the property that lets the row id be dropped entirely rather than kept
 * as a collision-breaking suffix. At minute resolution that claim is not
 * true; at second resolution it is.
 *
 * NULL IS HANDLED, NOT AVOIDED. [zoneId] and [utcOffsetMinutes] are null for
 * every session recorded before schema 1.3, and the fallback is UTC with a
 * trailing `Z` -- visible in the name, the house rule this repository
 * already follows elsewhere: a value that differs silently is worse than
 * one that differs and says so.
 *
 * UTC, NOT THE DEVICE'S CURRENT ZONE, FOR THE FALLBACK TOO, and this is the
 * non-obvious half: the device's zone is not stable across two exports of
 * the SAME old session -- a lifter who travels, or simply changes their
 * device's zone setting, would get a different name for an unchanged
 * session on a second export. UTC never moves. Idempotency is the property
 * this whole design rests on, and the fallback has to keep it exactly as
 * much as the ordinary case does.
 *
 * `Z` MEANS THE ZONE WAS UNKNOWN, NOT THAT THE SESSION HAPPENED IN UTC.
 * Read the other way round -- as marking the offset rather than the reason
 * a fallback was needed -- it looks backwards: a session genuinely recorded
 * in UTC (`utcOffsetMinutes = 0`) gets NO marker, because its zone WAS
 * captured, while an unknown-zone session gets one even though its true
 * offset might have been anything. The marker names provenance, not
 * offset, and a reader cannot recover offset from the filename alone in
 * either case. Say this plainly here so it does not read as the opposite
 * in a year.
 */
fun sessionTimestamp(startedAtMs: Long, zoneId: String?, utcOffsetMinutes: Int?): String {
    val instant = Instant.ofEpochMilli(startedAtMs)
    val recorded = RecordedTimeZone.of(zoneId, utcOffsetMinutes)
    return if (recorded != null) {
        val offset = ZoneOffset.ofTotalSeconds(recorded.utcOffsetMinutes * SECONDS_PER_MINUTE)
        FORMATTER.format(instant.atOffset(offset))
    } else {
        FORMATTER.format(instant.atOffset(ZoneOffset.UTC)) + "Z"
    }
}

private const val SECONDS_PER_MINUTE = 60

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.US)
