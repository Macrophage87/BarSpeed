package com.macrophage.barspeed.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [sessionTimestamp] against the properties its own KDoc argues for, not
 * only its arithmetic: idempotency across two exports, the null fallback's
 * visibility, and -- the one this file exists to guard -- that the
 * RECORDED offset is what renders, not whatever zone happens to be running
 * the code.
 */
class SessionTimestampTest {
    @Test
    fun `a zoned session renders in its recorded offset`() {
        // 2026-08-19T10:15:30Z, recorded at +02:00 -> 12:15:30 local.
        val startedAtMs = Instant.parse("2026-08-19T10:15:30Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = "Europe/Paris", utcOffsetMinutes = 120)
        assertEquals("2026-08-19_121530", result)
    }

    @Test
    fun `a session with no recorded zone falls back to UTC with a Z marker`() {
        val startedAtMs = Instant.parse("2026-08-19T10:15:30Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = null, utcOffsetMinutes = null)
        assertEquals("2026-08-19_101530Z", result)
    }

    /**
     * Half a zone -- one column present, the other absent -- is not enough
     * to render zoned. [RecordedTimeZone.of] already refuses this pair;
     * this pins that the refusal reaches the rendered string as the same
     * UTC-with-Z fallback, not a crash and not a guess built from the one
     * value that IS present.
     */
    @Test
    fun `a row with only half a recorded zone falls back to UTC, like a fully null row`() {
        val startedAtMs = Instant.parse("2026-08-19T10:15:30Z").toEpochMilli()
        assertEquals(
            sessionTimestamp(startedAtMs, zoneId = null, utcOffsetMinutes = null),
            sessionTimestamp(startedAtMs, zoneId = "Europe/Paris", utcOffsetMinutes = null),
        )
        assertEquals(
            sessionTimestamp(startedAtMs, zoneId = null, utcOffsetMinutes = null),
            sessionTimestamp(startedAtMs, zoneId = null, utcOffsetMinutes = 120),
        )
    }

    /**
     * THE #75 REGRESSION GUARD. [startedAtMs] is chosen so the recorded
     * offset (+09:00, Tokyo) and UTC disagree not only on the clock but on
     * the CALENDAR DATE -- 23:15 UTC on the 19th is already 08:15 on the
     * 20th at +09:00. That makes this fixture fail loudly under either of
     * the two wrong implementations someone could reach for: rendering in
     * UTC outright, or rendering in `ZoneId.systemDefault()`, which is UTC
     * on this project's own CI runner (ubuntu-latest) and is exceedingly
     * unlikely to coincide with +09:00 on whatever machine runs this
     * locally. A same-day, same-offset fixture could pass under either
     * mistake by accident; this one cannot.
     */
    @Test
    fun `the recorded offset renders, not UTC and not the running machine's zone`() {
        val startedAtMs = Instant.parse("2026-08-19T23:15:00Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = "Asia/Tokyo", utcOffsetMinutes = 540)
        assertEquals("2026-08-20_081500", result)
        assertNotEquals(
            "2026-08-19_231500",
            result,
            "rendered in UTC rather than the session's own recorded +09:00 offset",
        )
    }

    /**
     * Exporting the same session twice must produce the same name -- the
     * property the whole design rests on, checked directly for both the
     * zoned and the null-fallback case rather than assumed from the
     * function being pure.
     */
    @Test
    fun `the same session renders identically on a second call`() {
        val startedAtMs = Instant.parse("2026-08-19T10:15:30Z").toEpochMilli()
        assertEquals(
            sessionTimestamp(startedAtMs, "Europe/Paris", 120),
            sessionTimestamp(startedAtMs, "Europe/Paris", 120),
        )
        assertEquals(
            sessionTimestamp(startedAtMs, null, null),
            sessionTimestamp(startedAtMs, null, null),
        )
    }

    /**
     * A session genuinely recorded in UTC (`utcOffsetMinutes = 0`) gets NO
     * `Z` marker -- its zone WAS captured, so it takes the ordinary zoned
     * branch. Only an unrecorded zone gets the marker. This is the
     * distinction the function's own KDoc names as easy to misread a year
     * from now, pinned here rather than left to the KDoc alone.
     */
    @Test
    fun `a session actually recorded in UTC is not marked with Z`() {
        val startedAtMs = Instant.parse("2026-08-19T10:15:30Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = "Etc/UTC", utcOffsetMinutes = 0)
        assertEquals("2026-08-19_101530", result)
    }

    @Test
    fun `midnight-boundary fields are zero-padded to fixed width`() {
        val startedAtMs = Instant.parse("2026-01-05T00:03:07Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = null, utcOffsetMinutes = null)
        assertEquals("2026-01-05_000307Z", result)
    }

    /**
     * A negative offset. The zoned test above (+02:00) and the #75 guard
     * (+09:00) are both positive; a naive sign error in `offset * 60`
     * would pass both and only show up on the other side of UTC.
     */
    @Test
    fun `a negative offset renders correctly`() {
        // 2026-08-19T02:00:00Z at -05:00 -> Aug 18, 21:00. The stored offset is
        // used as-is, not re-derived from the zone id -- see RecordedTimeZone's
        // own KDoc for why -- so this pairing need not match a real zone's
        // actual rules for the date; it stands in for one.
        val startedAtMs = Instant.parse("2026-08-19T02:00:00Z").toEpochMilli()
        val result = sessionTimestamp(startedAtMs, zoneId = "America/New_York", utcOffsetMinutes = -300)
        assertEquals("2026-08-18_210000", result)
    }

    /** Lexicographic order must match chronological order, the whole reason for this format. */
    @Test
    fun `names sort lexicographically in chronological order`() {
        val earlier = sessionTimestamp(Instant.parse("2026-08-19T09:00:00Z").toEpochMilli(), null, null)
        val later = sessionTimestamp(Instant.parse("2026-08-19T09:00:01Z").toEpochMilli(), null, null)
        assertEquals(listOf(earlier, later), listOf(earlier, later).sorted())
    }
}
