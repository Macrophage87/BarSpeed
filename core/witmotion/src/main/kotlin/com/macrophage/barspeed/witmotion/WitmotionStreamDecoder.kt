package com.macrophage.barspeed.witmotion

import com.macrophage.barspeed.model.ImuSample

sealed interface WitmotionFrame {
    /** Default streaming frame (flag 0x61): acceleration, angular velocity, angles. */
    data class Measurement(val sample: ImuSample) : WitmotionFrame

    /** Register read-back frame (flag 0x71): starting register plus 8 int16 values. */
    data class RegisterData(val startRegister: Int, val values: ShortArray, val timestampMs: Long) : WitmotionFrame {
        override fun equals(other: Any?): Boolean = other is RegisterData &&
            other.startRegister == startRegister &&
            other.timestampMs == timestampMs &&
            other.values.contentEquals(values)

        override fun hashCode(): Int = 31 * startRegister + values.contentHashCode()
    }
}

/**
 * Streaming decoder for the WitMotion BLE frame protocol.
 *
 * BLE notifications may split or batch frames arbitrarily, so bytes are buffered
 * across calls and the decoder resyncs on the 0x55 header after garbage. The
 * 20-byte BLE frames carry no checksum; a frame is accepted when the header and
 * a known flag byte line up.
 */
class WitmotionStreamDecoder {
    private val buffer = ArrayDeque<Byte>()

    /** Feed raw notification bytes; returns every complete frame decoded so far. */
    fun feed(bytes: ByteArray, timestampMs: Long): List<WitmotionFrame> {
        bytes.forEach { buffer.addLast(it) }
        val frames = mutableListOf<WitmotionFrame>()
        while (buffer.size >= WitmotionProtocol.FRAME_LENGTH) {
            val header = buffer.first().toInt() and 0xFF
            if (header != WitmotionProtocol.FRAME_HEADER) {
                buffer.removeFirst()
                continue
            }
            val flag = buffer.elementAt(1).toInt() and 0xFF
            if (flag != WitmotionProtocol.FLAG_MEASUREMENT && flag != WitmotionProtocol.FLAG_REGISTER) {
                // Not a frame start we recognize; drop the header byte and resync.
                buffer.removeFirst()
                continue
            }
            val frame = ByteArray(WitmotionProtocol.FRAME_LENGTH) { buffer.removeFirst() }
            frames +=
                when (flag) {
                    WitmotionProtocol.FLAG_MEASUREMENT -> decodeMeasurement(frame, timestampMs)
                    else -> decodeRegister(frame, timestampMs)
                }
        }
        return frames
    }

    private fun decodeMeasurement(frame: ByteArray, timestampMs: Long): WitmotionFrame.Measurement {
        fun int16(offset: Int): Int = (frame[offset].toInt() and 0xFF) or (frame[offset + 1].toInt() shl 8)

        val accScale = WitmotionProtocol.ACC_FULL_SCALE_G / 32768.0
        val gyroScale = WitmotionProtocol.GYRO_FULL_SCALE_DPS / 32768.0
        val angleScale = WitmotionProtocol.ANGLE_FULL_SCALE_DEG / 32768.0
        return WitmotionFrame.Measurement(
            ImuSample(
                timestampMs = timestampMs,
                axG = int16(2) * accScale,
                ayG = int16(4) * accScale,
                azG = int16(6) * accScale,
                wxDps = int16(8) * gyroScale,
                wyDps = int16(10) * gyroScale,
                wzDps = int16(12) * gyroScale,
                rollDeg = int16(14) * angleScale,
                pitchDeg = int16(16) * angleScale,
                yawDeg = int16(18) * angleScale,
            ),
        )
    }

    private fun decodeRegister(frame: ByteArray, timestampMs: Long): WitmotionFrame.RegisterData {
        fun int16(offset: Int): Short =
            ((frame[offset].toInt() and 0xFF) or (frame[offset + 1].toInt() shl 8)).toShort()

        val startRegister = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val values = ShortArray(8) { int16(4 + it * 2) }
        return WitmotionFrame.RegisterData(startRegister, values, timestampMs)
    }
}
