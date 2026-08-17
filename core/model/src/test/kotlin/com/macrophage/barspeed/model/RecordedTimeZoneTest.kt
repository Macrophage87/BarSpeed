package com.macrophage.barspeed.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Resolving a device zone id to the UTC offset that applied at a session's own
 * start instant.
 *
 * The whole point of issue 75 is that the export publishes a correct UTC
 * instant with nothing to turn it into a local time of day. This is the one
 * piece of arithmetic in that fix, and it is here rather than in `:app` — where
 * the device zone is actually read — precisely so it can be tested at all.
 *
 * Every expected value below was measured against the JDK's own tz database
 * before being written down, not reasoned about. That matters most for the
 * non-whole-hour zones: they are the cases that catch an implementation working
 * in hours, and they would be easy to leave out because the author's own zone
 * is not one of them.
 */
class RecordedTimeZoneTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** August 2026, when US Eastern is on daylight time. */
    private val august = ms("2026-08-17T08:49:55.593Z")

    /** January 2026, when it is not. */
    private val january = ms("2026-01-17T08:49:55.593Z")

    /**
     * The same zone resolves to different offsets at different instants, which
     * is the reason the instant is a parameter at all.
     *
     * A session recorded in August and one recorded in January are both "New
     * York", and a consumer comparing their local start times across the
     * daylight-saving boundary gets an hour wrong if either carries the other's
     * offset. Resolving against "now" instead of the session's own instant is
     * the natural mistake and this pair is what catches it.
     */
    @Test
    fun `one zone resolves to different offsets either side of a transition`() {
        assertEquals(-240, RecordedTimeZone.resolve("America/New_York", august)?.utcOffsetMinutes)
        assertEquals(-300, RecordedTimeZone.resolve("America/New_York", january)?.utcOffsetMinutes)
    }

    /**
     * Zero is a value, not an absence.
     *
     * London in winter really is on UTC, and this is the case that would be
     * lost if the offset were ever given a Kotlin default: the exporter runs
     * with `encodeDefaults = false`, so a defaulted 0 disappears from the wire
     * and reads to a consumer as "the app did not say".
     */
    @Test
    fun `an offset of zero is a resolved value`() {
        val winter = RecordedTimeZone.resolve("Europe/London", january)
        assertEquals(RecordedTimeZone("Europe/London", 0), winter)
        assertEquals(60, RecordedTimeZone.resolve("Europe/London", august)?.utcOffsetMinutes)
    }

    /**
     * Offsets that are not whole hours, which is why the unit is minutes.
     *
     * An implementation dividing into hours passes every test above and fails
     * every one of these. Chatham is also checked either side of its own
     * transition, because it is the case where a half-hour zone and a
     * daylight-saving shift compound.
     */
    @Test
    fun `offsets that are not whole hours resolve exactly`() {
        assertEquals(330, RecordedTimeZone.resolve("Asia/Kolkata", august)?.utcOffsetMinutes)
        assertEquals(345, RecordedTimeZone.resolve("Asia/Kathmandu", august)?.utcOffsetMinutes)
        assertEquals(765, RecordedTimeZone.resolve("Pacific/Chatham", august)?.utcOffsetMinutes)
        assertEquals(825, RecordedTimeZone.resolve("Pacific/Chatham", january)?.utcOffsetMinutes)
    }

    /**
     * A device need not report an IANA name, and a fixed-offset id still yields
     * a usable offset.
     *
     * The id is kept verbatim rather than normalised to a place name: it is
     * what the device said, and inventing `Asia/Kolkata` from `+05:30` would be
     * claiming to know where the lifter was.
     */
    @Test
    fun `a fixed-offset zone id resolves and is kept as reported`() {
        assertEquals(RecordedTimeZone("+05:30", 330), RecordedTimeZone.resolve("+05:30", august))
        assertEquals(RecordedTimeZone("GMT+05:00", 300), RecordedTimeZone.resolve("GMT+05:00", august))
        assertEquals(RecordedTimeZone("UTC", 0), RecordedTimeZone.resolve("UTC", august))
    }

    /**
     * An id that names no zone, and an id that is not an id, both resolve to
     * nothing rather than to zero.
     *
     * These throw two different exception types out of `ZoneId.of` —
     * `ZoneRulesException` for the first, `DateTimeException` for the second —
     * so a catch narrow enough to admit only one of them would let the other
     * escape into the record flow at the moment a session is opened.
     */
    @Test
    fun `an unresolvable zone id yields nothing, never a zero offset`() {
        assertNull(RecordedTimeZone.resolve("Not/AZone", august))
        assertNull(RecordedTimeZone.resolve("", august))
        assertNull(RecordedTimeZone.resolve("America/Nowhere", august))
    }

    /**
     * An offset carrying seconds is not representable in minutes, so it is
     * absent rather than truncated.
     *
     * Before standard time, zone offsets were local mean solar time: New York
     * in 1880 is -04:56:02. No instant this app records can reach that, and the
     * branch exists anyway because truncating would publish an offset quietly
     * wrong by up to 59 seconds, which is the shape of defect this repository
     * keeps finding — a number that looks measured and is not.
     */
    @Test
    fun `an offset that is not a whole number of minutes is not reported`() {
        val eighteenEighty = ms("1880-01-01T12:00:00Z")
        assertNull(RecordedTimeZone.resolve("America/New_York", eighteenEighty))
        // The same zone, once standard time is in force, resolves normally --
        // so the null above is about the offset's shape, not about old dates.
        assertEquals(-300, RecordedTimeZone.resolve("America/New_York", ms("1920-01-01T12:00:00Z"))?.utcOffsetMinutes)
    }

    /**
     * Reading the pair back off a session row: both columns or nothing.
     *
     * The database can hold half a pair even though nothing writes one, so the
     * half states are pinned as absent rather than left to whichever branch
     * happens to run first.
     */
    @Test
    fun `a half-stated pair reads as not captured`() {
        assertEquals(RecordedTimeZone("America/New_York", -240), RecordedTimeZone.of("America/New_York", -240))
        assertNull(RecordedTimeZone.of("America/New_York", null))
        assertNull(RecordedTimeZone.of(null, -240))
        assertNull(RecordedTimeZone.of(null, null))
        // Zero survives the round trip; it is the value most at risk of being
        // treated as an absence by a null-ish check.
        assertEquals(RecordedTimeZone("UTC", 0), RecordedTimeZone.of("UTC", 0))
    }
}
