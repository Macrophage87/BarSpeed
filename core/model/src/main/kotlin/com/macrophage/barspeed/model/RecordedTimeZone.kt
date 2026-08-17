package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * The device's time zone as it stood when a session was recorded, together with
 * the UTC offset that applied at that session's own start instant.
 *
 * Both facts are carried, and neither is redundant.
 *
 * The **offset** is what a reader actually needs and cannot get wrong: it needs
 * no time-zone database, it cannot go stale, and it records what the device
 * believed at the time, which is the fact the lifter's wall clock showed.
 *
 * The **id** says *where*, and it is what makes a later re-derivation possible:
 * an id plus an instant yields the correct offset for any instant, including
 * across a daylight-saving boundary, which a single stored offset cannot do for
 * a different date. It also covers the one case a start-instant offset does not
 * — a session that ran across a transition, where the end instant is on the
 * other side of it.
 *
 * **Neither field may be absent while the other is present.** A reader that has
 * an offset but no zone, or a zone the app itself could not resolve, is being
 * handed half an answer it has no way to complete. Making this one object with
 * two non-null fields is what stops that state existing: it is not checked for,
 * it is unrepresentable.
 *
 * **Neither field has a default**, and that is load-bearing rather than
 * stylistic. The session exporter runs with `encodeDefaults = false`, so a field
 * defaulted to `0` would be dropped from the wire and its absence would read as
 * "not stated" — and `0` is a real, correct offset for a lifter in London in
 * winter, verified against the tz database rather than assumed. Same reasoning
 * as [GeometryExport], which documents the same trap.
 *
 * This is used both as what the record flow hands to storage and as what the
 * export publishes, rather than being copied into a separate wire type the way
 * [ResolvedGeometry] is copied into [GeometryExport]. That split exists there
 * for a reason that does not apply here: [ResolvedGeometry] holds Kotlin enums
 * that become lowercase strings on the wire, so the two shapes genuinely
 * differ. These two fields are already wire-shaped, so a second type would be a
 * copy of this one plus a mapping between them, and the mapping would be the
 * only thing that could be wrong. Storage is unaffected either way: this is
 * held as two ordinary columns on the session row, not as serialized JSON, so
 * the `@Serializable` here reaches the export and nothing else.
 */
@Serializable
data class RecordedTimeZone(
    /**
     * The zone id the device reported, normally an IANA name such as
     * `America/New_York`.
     *
     * Not guaranteed to be an IANA name. A device can report a fixed-offset id
     * like `GMT+05:00`, which resolves to an offset perfectly well but says
     * nothing about *where* — so a reader may use this to compute an offset,
     * and may not assume it names a place.
     */
    val id: String,
    /**
     * Minutes east of UTC in effect at the session's start instant; negative
     * west of it. `-240` is US Eastern daylight time.
     *
     * Minutes rather than hours because whole-hour offsets are not the only
     * ones there are: India is +05:30, Nepal +05:45 and the Chatham Islands
     * +12:45 or +13:45. All three are whole minutes, which is what makes this
     * unit exact — see [resolve] for what happens when an offset is not.
     */
    val utcOffsetMinutes: Int,
) {
    companion object {
        private const val SECONDS_PER_MINUTE = 60

        /**
         * The zone [zoneId] with the offset it had at [instantMs], or null when
         * that cannot be established.
         *
         * Resolved against the session's own instant rather than against "now".
         * The two differ whenever a zone's rules changed between the two
         * moments — `America/New_York` is -04:00 in August and -05:00 in
         * January — and using the session's instant is free and is the only
         * form that stays correct for a set recorded either side of a
         * transition.
         *
         * Null, never a zero or a guess, in three cases:
         *
         *  - the id names no zone (`ZoneRulesException`),
         *  - the id is not a well-formed zone id at all (`DateTimeException`),
         *  - the offset is not a whole number of minutes.
         *
         * The third is not hypothetical: before standard time was adopted, zone
         * offsets were local mean solar time and carried seconds, so
         * `America/New_York` in 1880 is -04:56:02. No instant this app records
         * can reach that, but truncating would publish an offset that is
         * quietly wrong by up to 59 seconds, and the house rule is that an
         * unrepresentable value is absent rather than approximated.
         */
        fun resolve(zoneId: String, instantMs: Long): RecordedTimeZone? {
            val seconds =
                try {
                    ZoneId.of(zoneId).rules.getOffset(Instant.ofEpochMilli(instantMs)).totalSeconds
                } catch (e: Exception) {
                    return null
                }
            if (seconds % SECONDS_PER_MINUTE != 0) return null
            return RecordedTimeZone(id = zoneId, utcOffsetMinutes = seconds / SECONDS_PER_MINUTE)
        }

        /**
         * The pair as it comes back off a session row, or null when the row does
         * not carry a complete one.
         *
         * A row holds the two values in two nullable columns, so "half a zone"
         * is representable in the database even though nothing writes it. Both
         * present is the only state that yields a value; anything else reads as
         * not captured, which is the same answer a row written before the
         * columns existed gives.
         */
        fun of(id: String?, utcOffsetMinutes: Int?): RecordedTimeZone? =
            if (id != null && utcOffsetMinutes != null) RecordedTimeZone(id, utcOffsetMinutes) else null
    }
}
