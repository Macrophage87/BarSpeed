package com.macrophage.accelerometerlifting.hrm

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateMeasurementParserTest {
    @Test
    fun `parses 8-bit heart rate`() {
        val sample = HeartRateMeasurementParser.parse(byteArrayOf(0x00, 72), 10L)!!
        assertEquals(72, sample.bpm)
        assertTrue(sample.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parses 16-bit heart rate`() {
        val sample = HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x2C, 0x01), 10L)!!
        assertEquals(300, sample.bpm)
    }

    @Test
    fun `parses RR intervals in 1024ths of a second`() {
        // Flags: RR present; HR 60; one RR of 1024 (= 1000 ms).
        val sample = HeartRateMeasurementParser.parse(byteArrayOf(0x10, 60, 0x00, 0x04), 10L)!!
        assertEquals(60, sample.bpm)
        assertEquals(1, sample.rrIntervalsMs.size)
        assertTrue(abs(sample.rrIntervalsMs[0] - 1000.0) < 0.01)
    }

    @Test
    fun `skips energy expended field before RR`() {
        val payload = byteArrayOf(0x18, 65, 0x34, 0x12, 0x00, 0x02)
        val sample = HeartRateMeasurementParser.parse(payload, 10L)!!
        assertEquals(65, sample.bpm)
        assertEquals(1, sample.rrIntervalsMs.size)
        assertTrue(abs(sample.rrIntervalsMs[0] - 500.0) < 0.01)
    }

    @Test
    fun `malformed payloads return null`() {
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(), 0L))
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x48), 0L))
    }
}
