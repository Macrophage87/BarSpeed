package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.SensorContact

/** Standard BLE Heart Rate Profile constants (works with Garmin HRM 600 and any compliant strap). */
object HeartRateProfile {
    const val SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
    const val MEASUREMENT_CHARACTERISTIC_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL_CHARACTERISTIC_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
}

/** Parser for the Heart Rate Measurement characteristic (0x2A37). */
object HeartRateMeasurementParser {
    private const val FLAG_HR_16_BIT = 0x01
    private const val FLAG_CONTACT_STATUS = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_EXPENDED = 0x08
    private const val FLAG_RR_PRESENT = 0x10

    /** Returns null for a malformed payload rather than throwing. */
    fun parse(payload: ByteArray, timestampMs: Long): HrSample? {
        if (payload.isEmpty()) return null
        val flags = payload[0].toInt() and 0xFF
        var offset = 1

        val bpm: Int
        if (flags and FLAG_HR_16_BIT != 0) {
            if (payload.size < offset + 2) return null
            bpm = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            if (payload.size < offset + 1) return null
            bpm = payload[offset].toInt() and 0xFF
            offset += 1
        }

        if (flags and FLAG_ENERGY_EXPENDED != 0) offset += 2

        val rrIntervals = mutableListOf<Double>()
        if (flags and FLAG_RR_PRESENT != 0) {
            while (offset + 1 < payload.size) {
                val rrRaw = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
                // RR is in 1/1024 s units per the spec; convert to milliseconds.
                rrIntervals += rrRaw * 1000.0 / 1024.0
                offset += 2
            }
        }
        return HrSample(timestampMs = timestampMs, bpm = bpm, rrIntervalsMs = rrIntervals, contact = contactOf(flags))
    }

    /**
     * The Sensor Contact bits (#82). Status means nothing without Support, so
     * Support clear is [SensorContact.NOT_RECORDED] whatever Status holds;
     * the strap is claiming contact, and nothing here measures whether it was
     * worn.
     */
    private fun contactOf(flags: Int): SensorContact = when {
        flags and FLAG_CONTACT_SUPPORTED == 0 -> SensorContact.NOT_RECORDED
        flags and FLAG_CONTACT_STATUS != 0 -> SensorContact.DETECTED
        else -> SensorContact.NOT_DETECTED
    }
}
