package com.macrophage.barspeed.model

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanSessionLastDoneTest {
    private val london = ZoneId.of("Europe/London")

    private fun at(day: Int, hour: Int = 18): Long =
        ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, london).toInstant().toEpochMilli()

    @Test
    fun `the latest run of each session of this plan, and nothing else`() {
        val history = listOf(
            RecordedPlanSession("Block", "Day A", at(2)),
            RecordedPlanSession("Block", "Day A", at(4)),
            RecordedPlanSession("Block", "Day B", at(3)),
            RecordedPlanSession("Other", "Day C", at(5)),
            RecordedPlanSession(null, null, at(6)),
            RecordedPlanSession("Block", null, at(6)),
        )
        assertEquals(mapOf("Day A" to at(4), "Day B" to at(3)), PlanSessionLastDone.of("Block", history))
    }

    @Test
    fun `the label is the day in the lifter's zone`() {
        assertEquals("Last done Wed 4 Nov", PlanSessionLastDone.label(at(4), at(6), london, Locale.UK))
        // 23:30 in London on the 4th is already the 5th in Tokyo.
        assertEquals(
            "Last done Thu 5 Nov",
            PlanSessionLastDone.label(at(4, hour = 23), at(6), ZoneId.of("Asia/Tokyo"), Locale.UK),
        )
    }

    @Test
    fun `a date from another year says the year`() {
        val lastYear = ZonedDateTime.of(2025, 11, 4, 18, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals("Last done Tue 4 Nov 2025", PlanSessionLastDone.label(lastYear, at(6), london, Locale.UK))
    }
}
