package com.macrophage.barspeed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules the interrupted-set listing decides by, with no file in sight.
 *
 * `JournalScanPolicy` is where #271's fix actually lives: the listing counts
 * newline bytes instead of decoding samples, and every question that used to
 * be answered by a `List.size` -- how many rows, is this file readable, is it
 * too big to count -- is answered here. Pure, so it is pinned on every push;
 * the byte loop that feeds it is exercised through `SetJournalStore.orphans()`
 * in the two files beside this one.
 */
class JournalScanTest {
    private fun scan(bytes: Long, newlines: Long, scannedBytes: Long = bytes, endsWithNewline: Boolean = true) =
        JournalScanPolicy.of("imu.csv", bytes, newlines, scannedBytes, endsWithNewline)

    /**
     * The header line is not a row, and forgetting to subtract it is the
     * off-by-one this whole count is exposed to: the writer opens every stream
     * file with its canonical header before the first sample line.
     */
    @Test
    fun `rows are the newline count less the header line`() {
        assertEquals(0L, scan(bytes = 80, newlines = 1).rows, "a header row alone is not a sample")
        assertEquals(1L, scan(bytes = 180, newlines = 2).rows)
        assertEquals(2_999_999L, scan(bytes = 60_000_000, newlines = 3_000_000).rows)
    }

    /** Nothing on disk is nothing counted, and it does not go negative. */
    @Test
    fun `an empty file is zero rows`() {
        assertEquals(0L, scan(bytes = 0, newlines = 0, endsWithNewline = false).rows)
    }

    /**
     * A process killed mid-append leaves a final line with no newline after
     * it, and it counts.
     *
     * The bytes are in the directory and travel verbatim into the zip, so a
     * listing that omitted the row would understate the capture at exactly the
     * moment the lifter is ruling on whether to keep it. `ImuCsv.decode`
     * refuses that same line, so a decode of the file yields one sample fewer
     * -- a deliberate and stated difference, not a drift.
     */
    @Test
    fun `a truncated last row counts as a row`() {
        assertEquals(2L, scan(bytes = 200, newlines = 2, endsWithNewline = false).rows)
    }

    /**
     * A header row with no trailing newline is still not a sample. The same
     * rule as above, at the boundary where it could easily invent one.
     */
    @Test
    fun `a header with no trailing newline is still zero rows`() {
        assertEquals(0L, scan(bytes = 78, newlines = 0, endsWithNewline = false).rows)
    }

    /**
     * Past the cap the scan stops, so the row count is a lower bound and the
     * entry says so rather than publishing a smaller number as if it were the
     * answer.
     */
    @Test
    fun `a file past the scan cap is oversize and its rows are a lower bound`() {
        val big =
            scan(
                bytes = JournalScanPolicy.SCAN_CAP_BYTES + 1L,
                newlines = 700_000,
                scannedBytes = JournalScanPolicy.SCAN_CAP_BYTES,
            )
        assertTrue(big.oversize)
        assertFalse(big.counted, "an oversize count was published as exact")
        assertEquals(JournalScanPolicy.SCAN_CAP_BYTES + 1L, big.bytes, "the real size was not reported")
    }

    /** Exactly at the cap is not past it. */
    @Test
    fun `a file exactly at the scan cap is not oversize`() {
        val edge = scan(bytes = JournalScanPolicy.SCAN_CAP_BYTES, newlines = 10)
        assertFalse(edge.oversize)
        assertTrue(edge.counted)
    }

    /**
     * #271'S FILE, AS A RULE. Hundreds of megabytes and not one newline: no
     * CSV this format writes can look like that, because every stream file
     * opens with a header row that is flushed before the first sample line.
     * The scan stops after one buffer and the entry is listed by its size.
     */
    @Test
    fun `a file with no newline past the first buffer is malformed`() {
        val runaway =
            scan(
                bytes = 300L * 1024L * 1024L,
                newlines = 0,
                scannedBytes = JournalScanPolicy.BUFFER_BYTES.toLong(),
                endsWithNewline = false,
            )
        assertTrue(runaway.malformed)
        assertFalse(runaway.counted)
        assertEquals(0L, runaway.rows)
        assertEquals(300L * 1024L * 1024L, runaway.bytes)
    }

    /**
     * A short newline-free file is not malformed. A stream file that never got
     * past its own header row is a real and ordinary outcome of a process
     * killed early, and calling it damaged would put a warning on the card for
     * the commonest interruption there is.
     */
    @Test
    fun `a short file with no newline is not malformed`() {
        val stub = scan(bytes = 78, newlines = 0, endsWithNewline = false)
        assertFalse(stub.malformed)
        assertTrue(stub.counted)
    }

    // ---- what the card says -------------------------------------------------

    private fun header(imuConnected: Boolean = true, secondaryImuConnected: Boolean = false) = SetJournalHeader(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        sessionId = null,
        sessionStartedAtMs = 900L,
        startedAtMs = 1_000L,
        orderIdx = 0,
        imuConnected = imuConnected,
        secondaryImuConnected = secondaryImuConnected,
    )

    private fun stream(
        name: String,
        rows: Long,
        bytes: Long = rows * 90L,
        oversize: Boolean = false,
        malformed: Boolean = false,
    ) = JournalStreamScan(name, bytes, rows, oversize, malformed)

    private fun orphan(
        imu: JournalStreamScan? = null,
        secondaryImu: JournalStreamScan? = null,
        repMarks: JournalStreamScan? = null,
        imuConnected: Boolean = true,
        secondaryImuConnected: Boolean = false,
    ) = OrphanedSet(
        header = header(imuConnected, secondaryImuConnected),
        directory = java.io.File("/nowhere"),
        imu = imu,
        secondaryImu = secondaryImu,
        repMarks = repMarks,
    )

    @Test
    fun `the card names the armed row count and the size on disk`() {
        val text =
            InterruptedSetSummary.lines(
                orphan(imu = stream("imu.csv", rows = 412, bytes = 40_000)),
            ).joinToString(" · ")
        assertEquals("412 from the armed sensor · 40.0 KB on disk", text)
    }

    /**
     * A DAMAGED STREAM IS NEVER A COUNT. #271's capture would otherwise draw
     * "0 from the armed sensor" beside a DISCARD button, which is absence
     * rendered as a value on the one screen where the lifter decides whether
     * the capture is worth keeping.
     */
    @Test
    fun `a malformed armed stream is reported in words and by size, never as zero`() {
        val text =
            InterruptedSetSummary.lines(
                orphan(
                    imu = stream("imu.csv", rows = 0, bytes = 314_572_800L, malformed = true),
                ),
            ).joinToString(" · ")
        assertFalse("0 from the armed sensor" in text, "a stream nobody can read was counted: $text")
        assertTrue("could not be read" in text, text)
        assertTrue("314.6 MB on disk" in text, text)
        assertTrue("unreadable: imu.csv" in text, text)
    }

    /** An oversize stream is a lower bound, and is flagged the same way. */
    @Test
    fun `an oversize armed stream is not reported as an exact count`() {
        val text =
            InterruptedSetSummary.lines(
                orphan(imu = stream("imu.csv", rows = 700_000, bytes = 70_000_000L, oversize = true)),
            ).joinToString(" · ")
        assertFalse("700000 from the armed sensor" in text, text)
        assertTrue("unreadable: imu.csv" in text, text)
    }

    /**
     * No sensor connected is not zero samples. The header carries the
     * observed connection precisely so the card can tell "nothing to measure"
     * from "measured nothing".
     */
    @Test
    fun `a sensorless capture says so rather than printing a count`() {
        val text = InterruptedSetSummary.lines(orphan(imuConnected = false)).joinToString(" · ")
        assertTrue(text.startsWith("no sensor connected"), text)
    }

    /**
     * A second unit that was connected and delivered nothing is named, issue
     * #156: the armed unit is the one that can be flat while the second one
     * captured the whole set.
     */
    @Test
    fun `a second link that was connected and delivered nothing is named`() {
        val text =
            InterruptedSetSummary.lines(
                orphan(imu = stream("imu.csv", 120), secondaryImuConnected = true),
            ).joinToString(" · ")
        assertTrue("none from the second sensor" in text, text)
    }

    @Test
    fun `both streams are counted when both captured`() {
        val text =
            InterruptedSetSummary.lines(
                orphan(
                    imu = stream("imu.csv", 120),
                    secondaryImu = stream("imu-b.csv", 340),
                    secondaryImuConnected = true,
                ),
            ).joinToString(" · ")
        assertTrue("120 from the armed sensor, 340 from the second sensor" in text, text)
    }

    /** Marks the lifter made are counted; none is silence, not "0 reps". */
    @Test
    fun `rep marks are counted only when there are any`() {
        val counted =
            InterruptedSetSummary.lines(
                orphan(imu = stream("imu.csv", 120), repMarks = stream("reps.csv", 5)),
            )
        assertTrue("5 reps counted" in counted, counted.toString())
        val none = InterruptedSetSummary.lines(orphan(imu = stream("imu.csv", 120)))
        assertTrue(none.none { "reps counted" in it }, none.toString())
    }

    /** The size a card shows is the streams', summed; a null stream is not a zero-byte one. */
    @Test
    fun `the reported size is the sum of the stream files present`() {
        val set =
            orphan(
                imu = stream("imu.csv", rows = 10, bytes = 1_000),
                secondaryImu = stream("imu-b.csv", rows = 10, bytes = 2_000),
                repMarks = stream("reps.csv", rows = 2, bytes = 30),
            )
        assertEquals(3_030L, set.bytes)
        assertEquals(listOf("imu.csv", "imu-b.csv", "reps.csv"), set.streams.map { it.name })
    }

    /** A role the header declared and no file to show for it is zero frames, not a crash. */
    @Test
    fun `an orphan with no streams at all reports nothing and does not throw`() {
        val set = orphan()
        assertEquals(0L, set.bytes)
        assertEquals(emptyList(), set.streams)
        assertEquals(null, set.analysedRole)
        assertFalse(set.analysedFellBack)
        assertTrue(InterruptedSetSummary.lines(set).isNotEmpty())
    }
}
