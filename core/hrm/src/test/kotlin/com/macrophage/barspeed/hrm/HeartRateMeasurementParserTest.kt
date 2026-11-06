package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.SensorContact
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

    /**
     * Sensor Contact Status (flags bit 1) means nothing unless Sensor Contact
     * Support (bit 2) is set, so a notification with Support clear says
     * nothing about contact whatever bit 1 holds (#82). Status set and
     * Support clear is the case a reader of bit 1 alone would get wrong.
     */
    @Test
    fun `a notification without contact support says nothing about contact`() {
        assertEquals(SensorContact.NOT_RECORDED, HeartRateMeasurementParser.parse(byteArrayOf(0x00, 72), 10L)!!.contact)
        assertEquals(SensorContact.NOT_RECORDED, HeartRateMeasurementParser.parse(byteArrayOf(0x02, 72), 10L)!!.contact)
    }

    /**
     * DIFFERENTIAL, #82. With Sensor Contact Support (bit 2) set, Status
     * (bit 1) is the strap's own report of skin contact, per notification,
     * and it is the only evidence left when a strap streams bpm with the R-R
     * Present flag clear. The parser read the flags byte and discarded it.
     * Asserted with and without R-R and the 16-bit bpm form, so the bits are
     * read from the flags byte and not from wherever a payload happens to
     * put them.
     */
    @Test
    fun `contact is read from the flags when the strap supports it`() {
        val worn = HeartRateMeasurementParser.parse(byteArrayOf(0x06, 72), 10L)!!
        val off = HeartRateMeasurementParser.parse(byteArrayOf(0x04, 72), 10L)!!
        val offWithRr = HeartRateMeasurementParser.parse(byteArrayOf(0x14, 60, 0x00, 0x04), 10L)!!
        val worn16Bit = HeartRateMeasurementParser.parse(byteArrayOf(0x07, 0x2C, 0x01), 10L)!!

        assertEquals(SensorContact.DETECTED, worn.contact)
        assertEquals(SensorContact.NOT_DETECTED, off.contact)
        assertEquals(SensorContact.NOT_DETECTED, offWithRr.contact)
        assertEquals(1, offWithRr.rrIntervalsMs.size, "reading the contact bits moved the R-R offset")
        assertEquals(SensorContact.DETECTED, worn16Bit.contact)
        assertEquals(300, worn16Bit.bpm)
    }

    @Test
    fun `malformed payloads return null`() {
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(), 0L))
        assertNull(HeartRateMeasurementParser.parse(byteArrayOf(0x01, 0x48), 0L))
    }
}
