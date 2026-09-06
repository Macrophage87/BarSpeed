package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.ByteSize

/**
 * What one journal stream file holds, measured without decoding a line of it.
 *
 * [rows] is the number of sample lines under the header row, derived from a
 * count of newline bytes and never from a parse. It is EXACT only when
 * [oversize] and [malformed] are both false; otherwise the scan stopped early
 * and this is a lower bound, which is why the two flags travel with it rather
 * than being folded into a smaller number.
 *
 * [bytes] is the file's real length in every case, including the two where the
 * scan stopped, because the size is the one thing a lifter deciding whether to
 * keep a damaged capture can act on.
 */
data class JournalStreamScan(
    val name: String,
    val bytes: Long,
    val rows: Long,
    val oversize: Boolean,
    val malformed: Boolean,
) {
    /** True when [rows] is the file's real row count rather than a lower bound. */
    val counted: Boolean get() = !oversize && !malformed
}

/**
 * How a byte scan's observations become a [JournalStreamScan]. Pure: nothing
 * here opens a file, and every rule the listing decides by is here rather than
 * inside the loop that reads bytes.
 *
 * THE LISTING NEVER DECODES A STREAM. Issue #271: a journal file under
 * `files/inflight` held hundreds of megabytes with no newline in any of them,
 * and `BufferedReader.readLine` grew one line's `StringBuilder` until the
 * phone's 256 MB heap was gone. The scan was replayed on every launch, so the
 * app could not start at all -- eight `OutOfMemoryError` crashes in the
 * dropbox between 03:47:58 and 04:16:22, at process runtimes of 8 to 176
 * seconds, and two driven launches that died 8 s after Home drew with the same
 * `Arrays.copyOf <- ensureCapacityInternal <- BufferedReader.readLine` stack.
 * A count of bytes cannot do that: it holds one fixed buffer whatever the file
 * turns out to be.
 */
object JournalScanPolicy {
    /**
     * The fixed buffer the scan reads through, and the window a stream file
     * has to produce its first newline in.
     *
     * The same 64 KB the journal WRITER buffers with, which is not a
     * coincidence worth hiding: a stream file's header row is under a hundred
     * bytes and is written and flushed before any sample line, so a file whose
     * first 64 KB holds no newline at all is not a CSV this format ever wrote.
     */
    const val BUFFER_BYTES = 64 * 1024

    /**
     * How much of one stream file the scan will look at before giving up on an
     * exact row count.
     *
     * A real set at ~100 Hz writes on the order of a hundred kilobytes, so 64
     * MB is a thousandfold headroom over anything a capture produces and still
     * a bounded amount of reading on the launch path. Past it the entry is
     * marked [JournalStreamScan.oversize] and listed by its size: the point of
     * the card is to let the lifter decide whether to keep the capture, and
     * that decision does not need a row count to three significant figures.
     */
    const val SCAN_CAP_BYTES = 64L * 1024L * 1024L

    /**
     * The largest `header.json` the listing will read into memory.
     *
     * The header is one small JSON object -- the largest this format writes is
     * a few hundred bytes -- and it is read WHOLE because it has to be parsed.
     * That makes it the one unbounded read left on this path, and #271 is
     * exactly what an unbounded read on the launch path costs. A directory
     * whose header is larger than this is refused rather than parsed, which is
     * the same direction [SetJournalStore.JOURNAL_VERSION] is refused in: a
     * file format has no compiler standing behind it.
     */
    const val HEADER_MAX_BYTES = 64L * 1024L

    /**
     * The scan's observations, ruled on.
     *
     * [newlines] and [scannedBytes] describe the PREFIX the scan looked at,
     * which is the whole file unless a rule below stopped it.
     * [endsWithNewline] is the last byte the scan saw and is only consulted
     * when the prefix is the whole file.
     *
     * ROWS = LINES - 1, FLOORED AT ZERO, AND A TRUNCATED LAST ROW COUNTS AS A
     * ROW. The header line is the one that is subtracted. A process killed
     * mid-append leaves a final line with no newline after it, and that line
     * is counted: it is a row the writer began, the zip carries its bytes
     * verbatim, and reporting it as absent would understate a capture at
     * exactly the moment the lifter is deciding whether to keep it. It is NOT
     * a decodable sample -- `ImuCsv.decode` refuses it -- so this count and
     * the number of samples a later decode of the same file yields can differ
     * by one. That difference is deliberate and is the only one there is on a
     * well-formed file.
     *
     * A file holding a header row and nothing else is zero rows, with or
     * without its trailing newline. An empty file is zero rows.
     */
    fun of(
        name: String,
        bytes: Long,
        newlines: Long,
        scannedBytes: Long,
        endsWithNewline: Boolean,
    ): JournalStreamScan {
        val whole = scannedBytes >= bytes
        val truncatedLastRow = whole && bytes > 0L && !endsWithNewline
        val lines = newlines + if (truncatedLastRow) 1L else 0L
        return JournalStreamScan(
            name = name,
            bytes = bytes,
            rows = (lines - 1L).coerceAtLeast(0L),
            oversize = bytes > SCAN_CAP_BYTES,
            malformed = newlines == 0L && bytes > BUFFER_BYTES.toLong(),
        )
    }
}

/**
 * What the interrupted-set card says about a capture, in the terms the field
 * check reads.
 *
 * Here rather than in the Compose function that draws it, and that is the
 * point: `:app` has almost no test source set, and every one of the rulings
 * below is one this repository has already got wrong somewhere -- a count of
 * zero standing in for "nothing was measured", a damaged stream reported as an
 * empty one, a second sensor's absence indistinguishable from its silence. In
 * `:core:data` they are pinned on every push.
 *
 * NO WALL CLOCK. The card used to end with the last sample's timestamp, read
 * from the decoded stream; nothing here decodes a stream, so that line is gone
 * rather than reworded into something the scan cannot support. The size on
 * disk replaces it as the fact that distinguishes a capture that reached the
 * filesystem from one the app merely remembered, and it is the fact #271's
 * capture needed: hundreds of megabytes is itself the finding. The size is
 * formatted by `ByteSize`, which is where this repository states a byte count
 * in a lifter's units, rather than by a second rule here.
 */
object InterruptedSetSummary {
    /**
     * The detail line's parts, in order, joined by the caller.
     *
     * A DAMAGED STREAM IS NEVER REPORTED AS A COUNT. A malformed or oversize
     * file has a row count that is a lower bound, and printing "0 from the
     * armed sensor" for a 300 MB file nobody can read is this repository's
     * dominant defect: absence rendered as a value. It says so in words
     * instead, and the size beside it is what the lifter rules on.
     */
    fun lines(orphan: OrphanedSet): List<String> {
        val second = secondSensor(orphan)
        val reps = orphan.repMarks?.rows ?: 0L
        return listOfNotNull(
            armedSensor(orphan, second),
            "${ByteSize.format(orphan.bytes)} on disk",
            unreadable(orphan),
            if (reps > 0L) "$reps reps counted" else null,
        )
    }

    private fun armedSensor(orphan: OrphanedSet, second: String): String {
        val imu = orphan.imu
        return when {
            imu != null && !imu.counted -> "the armed sensor's stream could not be read$second"
            orphan.header.imuConnected -> "${imu?.rows ?: 0L} from the armed sensor$second"
            second.isNotEmpty() -> "no armed sensor connected$second"
            else -> "no sensor connected"
        }
    }

    private fun secondSensor(orphan: OrphanedSet): String {
        val secondary = orphan.secondaryImu
        return when {
            secondary != null && !secondary.counted -> ", the second sensor's stream could not be read"
            (secondary?.rows ?: 0L) > 0L -> ", ${secondary?.rows} from the second sensor"
            orphan.header.secondaryImuConnected -> ", none from the second sensor"
            else -> ""
        }
    }

    /**
     * Which files the counts above are a lower bound for, named.
     *
     * The armed and second streams already say so in their own words; this
     * exists so that a damaged `hrm.csv`, `cues.csv` or `reps.csv` is not
     * silently reported as an empty one.
     */
    private fun unreadable(orphan: OrphanedSet): String? {
        val damaged = orphan.streams.filter { !it.counted }.map { it.name }
        return if (damaged.isEmpty()) null else "unreadable: ${damaged.joinToString(", ")}"
    }
}
