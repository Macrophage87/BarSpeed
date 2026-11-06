package com.macrophage.barspeed.model

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ExportNaming]: what a robocoach folder full of BarSpeed exports is named (history-export F4, F5). */
class ExportNamingTest {
    private val startedAtMs = Instant.parse("2026-11-06T09:30:12Z").toEpochMilli()
    private val timestamp = sessionTimestamp(startedAtMs, zoneId = "UTC", utcOffsetMinutes = 0)

    @Test
    fun `the raw zip leads with the session start and carries no app version`() {
        assertEquals("BarSpeed-2026-11-06_093012-raw.zip", ExportNaming.rawZipName(timestamp, null))
        assertFalse(Regex("v\\d").containsMatchIn(ExportNaming.rawZipName(timestamp, "Lower A")))
    }

    @Test
    fun `a plan session adds its name as a slug after the start`() {
        assertEquals("BarSpeed-2026-11-06_093012-Lower-A-raw.zip", ExportNaming.rawZipName(timestamp, "Lower A"))
    }

    @Test
    fun `the same session gets the same name on every export`() {
        assertEquals(ExportNaming.rawZipName(timestamp, "Upper B"), ExportNaming.rawZipName(timestamp, "Upper B"))
    }

    @Test
    fun `raw zip names sort by start time as plain text`() {
        val earlier = sessionTimestamp(startedAtMs - 86_400_000L, "UTC", 0)
        val names = listOf(
            ExportNaming.rawZipName(timestamp, "Lower A"),
            ExportNaming.rawZipName(earlier, "Upper B"),
        )
        assertEquals(listOf(names[1], names[0]), names.sorted())
    }

    @Test
    fun `the slug keeps letters and digits and collapses the rest to single dashes`() {
        assertEquals("Day-1-Squat-Bench", ExportNaming.slug("  Day 1: Squat / Bench!! "))
        assertEquals("Lower-A", ExportNaming.slug("Lower A"))
    }

    @Test
    fun `a name with nothing filename-safe adds no slug`() {
        assertNull(ExportNaming.slug("!!! ///"))
        assertNull(ExportNaming.slug(""))
        assertNull(ExportNaming.slug(null))
        assertEquals("BarSpeed-2026-11-06_093012-raw.zip", ExportNaming.rawZipName(timestamp, "***"))
    }

    @Test
    fun `a long name is cut to the slug limit without a trailing dash`() {
        val slug = ExportNaming.slug("Week 3 heavy lower body with extra accessories")!!
        assertTrue(slug.length <= ExportNaming.MAX_SLUG_LENGTH, slug)
        assertFalse(slug.endsWith("-"), slug)
        assertEquals("Week-3-heavy-lower-body", slug)
    }

    @Test
    fun `the json exports keep the version in their names`() {
        assertEquals(
            "BarSpeed-v0.2.1-2026-11-06_093012-detailed.json",
            ExportNaming.versionedName("0.2.1", timestamp, "detailed.json"),
        )
    }

    @Test
    fun `the all-sessions archive is named after the day it was made`() {
        assertEquals("BarSpeed-all-2026-11-06.zip", ExportNaming.allSessionsArchiveName(LocalDate.of(2026, 11, 6)))
    }

    @Test
    fun `a repeated entry name gets a numbered suffix and keeps both`() {
        val taken = mutableSetOf<String>()
        assertEquals("a-raw.zip", ExportNaming.uniqueEntryName("a-raw.zip", taken))
        assertEquals("a-raw-2.zip", ExportNaming.uniqueEntryName("a-raw.zip", taken))
        assertEquals("a-raw-3.zip", ExportNaming.uniqueEntryName("a-raw.zip", taken))
        assertEquals("b-raw.zip", ExportNaming.uniqueEntryName("b-raw.zip", taken))
    }

    @Test
    fun `the summary counts sessions and names the ones that failed`() {
        assertEquals("Exported 47 sessions", ExportNaming.allSessionsSummary(47, emptyList()))
        assertEquals("Exported 1 session", ExportNaming.allSessionsSummary(1, emptyList()))
        assertEquals(
            "Exported 6 of 7 sessions. 1 failed: Tue 3 Nov",
            ExportNaming.allSessionsSummary(6, listOf("Tue 3 Nov")),
        )
        assertEquals(
            "Exported 0 of 5 sessions. 5 failed: a, b, c, and 2 more",
            ExportNaming.allSessionsSummary(0, listOf("a", "b", "c", "d", "e")),
        )
    }
}
