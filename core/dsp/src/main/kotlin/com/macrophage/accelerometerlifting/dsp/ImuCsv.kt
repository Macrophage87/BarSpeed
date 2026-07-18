package com.macrophage.accelerometerlifting.dsp

import com.macrophage.accelerometerlifting.model.ImuSample
import java.util.Locale

/**
 * The canonical CSV format for raw IMU streams. One format, three uses:
 * raw-data export, DSP test fixtures, and replay mode. Lines starting with
 * '#' are comments; the header row is required.
 */
object ImuCsv {
    const val HEADER = "timestamp_ms,ax_g,ay_g,az_g,wx_dps,wy_dps,wz_dps,roll_deg,pitch_deg,yaw_deg"

    fun encode(samples: List<ImuSample>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (s in samples) {
            sb.append(
                String.format(
                    Locale.US,
                    "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    s.timestampMs, s.axG, s.ayG, s.azG,
                    s.wxDps, s.wyDps, s.wzDps,
                    s.rollDeg, s.pitchDeg, s.yawDeg,
                ),
            )
        }
        return sb.toString()
    }

    fun decode(text: String): List<ImuSample> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp_ms") }
        .map { line ->
            val f = line.split(',')
            require(f.size >= 10) { "Bad CSV line: $line" }
            ImuSample(
                timestampMs = f[0].toLong(),
                axG = f[1].toDouble(),
                ayG = f[2].toDouble(),
                azG = f[3].toDouble(),
                wxDps = f[4].toDouble(),
                wyDps = f[5].toDouble(),
                wzDps = f[6].toDouble(),
                rollDeg = f[7].toDouble(),
                pitchDeg = f[8].toDouble(),
                yawDeg = f[9].toDouble(),
            )
        }
        .toList()
}
