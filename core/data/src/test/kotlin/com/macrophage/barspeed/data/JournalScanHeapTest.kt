package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #271, at the size that produced it, under the heap that could not hold it.
 *
 * The phone's dropbox held eight `OutOfMemoryError` crashes against a 256 MB
 * growth limit within 8 to 176 seconds of process start, and two driven
 * launches died 8 s after Home drew with the same stack both times:
 * `Arrays.copyOf` <- `AbstractStringBuilder.ensureCapacityInternal` <-
 * `BufferedReader.readLine` <- a Kotlin lines sequence <- a CSV line decoder in
 * `:core:data`, on a `Dispatchers.Main.immediate` scope. That is `readLine`
 * growing ONE line's `StringBuilder` until the heap is gone: a journal file
 * under `files/inflight` running for hundreds of megabytes with no newline in
 * it, replayed by `SetJournalStore.orphans()` on every launch through
 * `HomeViewModel`. The app could not start, and the lifter could not reach the
 * screen that would have let them delete the capture.
 *
 * THE HEAP BOUND IS THE TEST. `core/data/build.gradle.kts` sets
 * `maxHeapSize = "256m"` on this module's unit-test tasks -- the same growth
 * limit the phone killed the app against -- on the real `testDebugUnitTest`
 * and `testReleaseUnitTest` rather than on a task of its own, because
 * `./gradlew test` is CI's command and a bound that lives on a task CI never
 * invokes is not a bound. Gradle's default is 512 MB, which is enough to hold
 * the three million samples below and would let the second test pass while
 * blind.
 *
 * Both files are written a megabyte at a time and never held in memory here
 * either; a test that needed 300 MB to build its own fixture would fail for
 * its own reasons.
 *
 * WHAT THIS STILL CANNOT SAY: nothing here is Android, so this does not
 * establish that a launch survives -- only that the listing, which is the code
 * the phone's stack named, costs a fixed buffer.
 *
 * THE DEVICE LEG WAS RUN AND IT IS AN EMULATOR, NOT THE PHONE. On
 * `barspeed-api35` headless, with these same two files pushed under
 * `files/inflight/s1/set0-1` at the app's own uid, v0.1.51 (versionCode 52)
 * died 6 s after launch with
 * `FATAL EXCEPTION: main / java.lang.OutOfMemoryError: Failed to allocate a
 * 134250512 byte allocation ... growth limit 201326592` on
 * `Arrays.copyOf <- AbstractStringBuilder.ensureCapacityInternal <-
 * StringBuilder.append <- BufferedReader.readLine <-
 * com.macrophage.barspeed.data.a.a <- N1.n0.q` -- the phone's stack, obfuscated
 * frame names included. This branch's debug build, same files, drew the card
 * reading `the armed sensor's stream could not be read - 314.6 MB on disk -
 * unreadable: imu.csv` and did not crash; DISCARD removed the directory.
 * The emulator is not the lifter's Samsung SM-S948U and its heap limit is not
 * the phone's, so what this establishes is the mechanism, not the phone.
 */
class JournalScanHeapTest {
    private val root: File = Files.createTempDirectory("journal-heap").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun store() = SetJournalStore(root, CoroutineScope(Dispatchers.Unconfined))

    private val header =
        SetJournalHeader(
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            sessionId = null,
            sessionStartedAtMs = 900L,
            startedAtMs = 1_000L,
            orderIdx = 0,
            imuConnected = true,
        )

    private fun directory(): File = File(root, "s900/set0-1000").apply {
        mkdirs()
        File(this, SetJournalStore.HEADER_FILE)
            .writeText(Json.encodeToString(SetJournalHeader.serializer(), header))
    }

    /** A chunk at a time, so the fixture costs one buffer rather than the file. */
    private fun appendChunks(file: File, chunk: ByteArray, times: Int) {
        FileOutputStream(file, true).buffered(chunk.size).use { out ->
            repeat(times) { out.write(chunk) }
        }
    }

    /**
     * THE REPRODUCTION. 300 MB of a stream file with no newline anywhere in
     * it: the shape the phone's journal had, and the shape `readLine` cannot
     * survive because it must hold the whole line before it can return it.
     *
     * The listing must come back with the directory named, the file's real
     * size, and a flag saying the count is not a count -- never a decode, and
     * never a zero standing in for a file nobody can read.
     */
    @Test
    fun `a 300 MB stream with no newline is listed by its size and never read`() {
        val dir = directory()
        appendChunks(File(dir, SetJournal.IMU), ByteArray(CHUNK_BYTES) { FILLER }, RUNAWAY_CHUNKS)

        val orphan = store().orphans().single()
        assertEquals("Back Squat", orphan.header.exerciseName, "the capture lost its identity")
        assertEquals(RUNAWAY_BYTES, orphan.imu?.bytes, "the card cannot show a size it did not measure")
        assertTrue(orphan.imu?.malformed == true, "a 300 MB file with no newline was read as a CSV")
        assertTrue(orphan.imu?.oversize == true, "a file past the scan cap was counted whole")
        assertEquals(0L, orphan.imu?.rows, "rows were invented for a file that has none")
        assertTrue(
            "could not be read" in InterruptedSetSummary.lines(orphan).joinToString(" · "),
            "the card reported a stream nobody can read as an ordinary one",
        )
    }

    /**
     * A capture that IS well formed and merely long: three million rows, which
     * is roughly eight hours of a 100 Hz stream and about a thousand times a
     * real set.
     *
     * The count has to be exact -- it is under the scan cap -- and it has to be
     * arrived at without materialising three million samples, which is what
     * the 256 MB bound above is asserting. Twenty bytes per row is the
     * narrowest line `ImuCsv.decode` accepts, chosen so the file stays under
     * the cap while the DECODE it replaces would not fit in the heap.
     */
    @Test
    fun `three million rows are counted without holding a single sample`() {
        val dir = directory()
        val file = File(dir, SetJournal.IMU)
        file.writeText(ImuCsv.HEADER + "\n")
        val row = "1,0,0,1,0,0,0,0,0,0\n".toByteArray(Charsets.UTF_8)
        val chunk = ByteArray(row.size * ROWS_PER_CHUNK) { i -> row[i % row.size] }
        appendChunks(file, chunk, LONG_CAPTURE_ROWS / ROWS_PER_CHUNK)

        val orphan = store().orphans().single()
        assertEquals(LONG_CAPTURE_ROWS.toLong(), orphan.imu?.rows, "the row count is not the newline count")
        assertTrue(orphan.imu?.counted == true, "an exact count was published as a lower bound")
        assertTrue(orphan.imu?.malformed == false)
        assertTrue(orphan.imu?.oversize == false)
    }

    private companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val FILLER: Byte = 0x78 // 'x' -- anything that is not a newline
        const val RUNAWAY_CHUNKS = 300
        const val RUNAWAY_BYTES = RUNAWAY_CHUNKS.toLong() * CHUNK_BYTES
        const val LONG_CAPTURE_ROWS = 3_000_000
        const val ROWS_PER_CHUNK = 50_000
    }
}
