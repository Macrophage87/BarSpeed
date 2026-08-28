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

/**
 * Canonical CSV for rep marks: the instants a rep was COUNTED, on the same
 * epoch-ms clock as ImuCsv, HrCsv and CueCsv.
 *
 * One column, because a mark carries nothing but its instant. What a rep was
 * worth, how long it took and how far it travelled are all questions about the
 * bar, and the bar is in the IMU stream; this file answers only when somebody
 * decided a rep had happened, which is the one fact no reprocessing of any
 * stream can rebuild.
 *
 * A separate document from the cue track, and the separation is the point. A
 * cue is what the app SAID -- the guide speaks "Rep 1" on a schedule whether or
 * not anybody moved -- and a mark is what was COUNTED. Sharing a file would
 * make the two indistinguishable on read-back.
 *
 * [SetJournal] writes this same format line by line as a set is performed, and
 * [SetJournal.REPS_HEADER] is [HEADER]. The two are one format with one header
 * so that a mark recovered from an interrupted capture and a mark read out of a
 * stored set are the same column.
 */
object RepMarkCsv {
    const val HEADER = "timestamp_ms"

    fun encode(marks: List<Long>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (m in marks) sb.append(m).append('\n')
        return sb.toString()
    }

    fun decode(text: String): List<Long> = text.lineSequence().flatMap { decodeLine(it) }.toList()

    /**
     * One line as zero or one mark: the header, a blank line and a comment
     * yield none, and anything else must parse as a whole number or throw.
     *
     * Zero-or-one rather than nullable because that is the shape
     * `SetJournalStore` reads a partially written capture with: it parses line
     * by line and stops at the first REFUSAL, so "this line carries nothing"
     * and "this line is not a mark" have to be different answers. A malformed
     * line throwing is what ends that read at the damage instead of stepping
     * over it.
     */
    fun decodeLine(line: String): List<Long> {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("#") || text.startsWith(HEADER)) return emptyList()
        return listOf(text.toLong())
    }
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
