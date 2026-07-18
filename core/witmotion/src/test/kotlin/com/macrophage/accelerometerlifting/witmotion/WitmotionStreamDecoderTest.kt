package com.macrophage.accelerometerlifting.witmotion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WitmotionStreamDecoderTest {
    private fun measurementFrame(
        axG: Double = 0.0,
        ayG: Double = 0.0,
        azG: Double = 1.0,
        wxDps: Double = 0.0,
        rollDeg: Double = 0.0,
        pitchDeg: Double = 0.0,
        yawDeg: Double = 0.0,
    ): ByteArray {
        fun enc(value: Double, fullScale: Double): Pair<Byte, Byte> {
            val raw = (value / fullScale * 32768.0).toInt().coerceIn(-32768, 32767)
            return Pair((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
        }

        val parts =
            listOf(
                enc(axG, 16.0), enc(ayG, 16.0), enc(azG, 16.0),
                enc(wxDps, 2000.0), enc(0.0, 2000.0), enc(0.0, 2000.0),
                enc(rollDeg, 180.0), enc(pitchDeg, 180.0), enc(yawDeg, 180.0),
            )
        return byteArrayOf(0x55, 0x61) + parts.flatMap { listOf(it.first, it.second) }.toByteArray()
    }

    @Test
    fun `decodes a single measurement frame with correct scaling`() {
        val decoder = WitmotionStreamDecoder()
        val frames = decoder.feed(measurementFrame(axG = -0.5, azG = 1.0, wxDps = 100.0, pitchDeg = -45.0), 42L)
        assertEquals(1, frames.size)
        val sample = (frames[0] as WitmotionFrame.Measurement).sample
        assertEquals(42L, sample.timestampMs)
        assertTrue(abs(sample.axG - (-0.5)) < 0.001)
        assertTrue(abs(sample.azG - 1.0) < 0.001)
        assertTrue(abs(sample.wxDps - 100.0) < 0.1)
        assertTrue(abs(sample.pitchDeg - (-45.0)) < 0.01)
    }

    @Test
    fun `reassembles frames split across notifications`() {
        val decoder = WitmotionStreamDecoder()
        val frame = measurementFrame(azG = 1.0)
        assertTrue(decoder.feed(frame.copyOfRange(0, 7), 1L).isEmpty())
        val frames = decoder.feed(frame.copyOfRange(7, 20), 2L)
        assertEquals(1, frames.size)
    }

    @Test
    fun `decodes batched frames in one notification`() {
        val decoder = WitmotionStreamDecoder()
        val frames = decoder.feed(measurementFrame() + measurementFrame() + measurementFrame(), 5L)
        assertEquals(3, frames.size)
    }

    @Test
    fun `resyncs after garbage bytes`() {
        val decoder = WitmotionStreamDecoder()
        val garbage = byteArrayOf(0x01, 0x55, 0x02, 0x33)
        val frames = decoder.feed(garbage + measurementFrame(azG = 1.0), 9L)
        assertEquals(1, frames.size)
    }

    @Test
    fun `decodes register frame`() {
        val decoder = WitmotionStreamDecoder()
        // Battery register read-back: reg 0x64, first value 395 (3.95 V style encoding).
        val frame =
            byteArrayOf(0x55, 0x71, 0x64, 0x00) +
                byteArrayOf((395 and 0xFF).toByte(), (395 shr 8).toByte()) +
                ByteArray(14)
        val frames = decoder.feed(frame, 3L)
        assertEquals(1, frames.size)
        val reg = frames[0] as WitmotionFrame.RegisterData
        assertEquals(0x64, reg.startRegister)
        assertEquals(395, reg.values[0].toInt())
    }

    @Test
    fun `command builders produce documented byte sequences`() {
        assertTrue(
            WitmotionCommands.setOutputRate(WitmotionProtocol.OutputRate.RATE_100_HZ)
                .contentEquals(byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03, 0x09, 0x00)),
        )
        assertTrue(
            WitmotionCommands.readRegister(0x64)
                .contentEquals(byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, 0x64, 0x00)),
        )
    }
}
