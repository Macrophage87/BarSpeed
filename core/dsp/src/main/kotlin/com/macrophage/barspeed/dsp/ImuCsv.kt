package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import java.util.Locale

/**
 * The canonical CSV format for raw IMU streams. One format, three uses:
 * raw-data export, DSP test fixtures, and replay mode. Lines starting with
 * '#' are comments; the header row is required.
 */
object ImuCsv {
    /**
     * `timestamp_ms` is the BLE notification ARRIVAL time: a packet carries
     * several samples and they all share it, so consecutive deltas are 0 ms and
     * then jump ~30 ms. It is exact enough to align against the cue track, and
     * useless as an integration step. `sample_idx` is THIS loop's own index,
     * `0..n-1` by construction. Divided by the set's `sampleRate_hz` from
     * meta.json it gives a uniform clock at the rate the rows ARRIVED. That
     * key is the DELIVERED rate, (n-1) over the span of the rows' arrival
     * stamps across the whole file. It is the sensor's own sample clock only
     * if no frame was lost between the sensor and this file, and nothing in
     * the file can say whether one was (#321). The committed field-42
     * captures show why that matters: one unit's rows arrived at 43.5 to 44.5
     * a second, while the app writes a 100 Hz output rate to every unit.
     * Read no dropped sample from a gap in `sample_idx` either. It has no gap
     * to give: it counts rows written here, never packets the sensor sent.
     */
    const val HEADER =
        "timestamp_ms,ax_g,ay_g,az_g,wx_dps,wy_dps,wz_dps,roll_deg,pitch_deg,yaw_deg,sample_idx"

    fun encode(samples: List<ImuSample>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for ((idx, s) in samples.withIndex()) {
            sb.append(
                String.format(
                    Locale.US,
                    "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d%n",
                    s.timestampMs, s.axG, s.ayG, s.azG,
                    s.wxDps, s.wyDps, s.wzDps,
                    s.rollDeg, s.pitchDeg, s.yawDeg, idx,
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
