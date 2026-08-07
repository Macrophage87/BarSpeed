package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.VoiceCue
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Canonical CSV for heart-rate streams (companion to ImuCsv). */
object HrCsv {
    const val HEADER = "timestamp_ms,hr_bpm,rr_ms"

    fun encode(samples: List<HrSample>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (s in samples) {
            val rr = s.rrIntervalsMs.joinToString("|") { String.format(Locale.US, "%.1f", it) }
            sb.append("${s.timestampMs},${s.bpm},$rr\n")
        }
        return sb.toString()
    }

    fun decode(text: String): List<HrSample> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp_ms") }
        .map { line ->
            val f = line.split(',')
            HrSample(
                timestampMs = f[0].toLong(),
                bpm = f[1].toInt(),
                rrIntervalsMs =
                f.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    ?.split('|')?.map { it.toDouble() } ?: emptyList(),
            )
        }
        .toList()
}

/** Canonical CSV for spoken voice cues, same epoch-ms clock as ImuCsv/HrCsv. */
object CueCsv {
    const val HEADER = "timestamp_ms,cue"

    fun encode(cues: List<VoiceCue>): String {
        val sb = StringBuilder(HEADER).append('\n')
        // Cue texts are single words/short phrases; commas are stripped defensively.
        for (c in cues) sb.append("${c.timestampMs},${c.cue.replace(",", " ")}\n")
        return sb.toString()
    }

    fun decode(text: String): List<VoiceCue> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp_ms") }
        .map { line ->
            val f = line.split(',', limit = 2)
            VoiceCue(timestampMs = f[0].toLong(), cue = f.getOrNull(1).orEmpty())
        }
        .toList()
}

object Gzip {
    fun compress(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun decompress(bytes: ByteArray): String =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
}
