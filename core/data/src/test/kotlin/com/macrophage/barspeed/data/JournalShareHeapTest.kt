package com.macrophage.barspeed.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SEND IT TO ME on a capture larger than the heap. Issue #273.
 *
 * #271 bounded the LISTING: a journal directory is counted by newline bytes
 * through a fixed buffer and never decoded, so the recovery card draws for an
 * oversize capture instead of killing the app at launch. That made the second
 * half reachable for the first time -- the card draws, and the button on it
 * takes the capture off the phone. `SetJournalStore.zipTo` read each stream
 * with `file.readBytes()`, one whole stream in the heap, against a file #271
 * measured at 314.6 MB.
 *
 * THE HEAP BOUND IS THE TEST, the same way it is in [JournalScanHeapTest].
 * `core/data/build.gradle.kts` runs this module's unit tests at
 * `maxHeapSize = "256m"`, the growth limit the phone killed the app against,
 * and the fixture below is 300 MiB. No allocation of the whole stream can
 * succeed under that bound, so a pin that asserts the stream ARRIVES WHOLE
 * cannot pass unless the copy is streamed. Nothing here counts read sizes:
 * `File.inputStream()` is not injectable from a test, and a seam added only
 * so a test could watch it would be pinning the seam rather than the property.
 *
 * WHAT #273 PREDICTED AND WHAT THE CODE ACTUALLY DID ARE DIFFERENT, and the
 * difference is worse. The issue expected an `OutOfMemoryError` to take the
 * app down. The whole-file read sat inside `runCatching { }`, which catches
 * Throwable, so the error was SWALLOWED per file: the archive was written
 * without the capture's largest stream and handed to the share sheet as a
 * complete-looking zip. Silent data loss, not a crash -- this repository
 * ranks that first, and it is what this pin measures. Whether the error
 * instead escaped somewhere outside that `runCatching` on a phone is not
 * something a JVM test can settle; either way the stream did not arrive.
 *
 * The fixture is written a mebibyte at a time and is newline-free -- #271's
 * shape exactly -- so it costs one buffer to build and is listed as
 * unreadable rather than counted. A capture nobody can parse is precisely the
 * one worth getting off the phone intact: the bytes are the only copy.
 *
 * WHAT THIS CANNOT SAY. Nothing here is Android. It does not establish that
 * the share sheet accepts the file, that a mail client uploads it, or that
 * the phone's own heap survives -- SEND IT TO ME has never been pressed on a
 * real oversize capture, on the emulator or on the phone, and that stays a
 * [Field] item on #273.
 */
class JournalShareHeapTest {
    private val root: File = Files.createTempDirectory("journal-share").toFile()

    /** Outside [root], so nothing here is mistaken for a second capture. */
    private val outbox: File = Files.createTempDirectory("journal-share-out").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        outbox.deleteRecursively()
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

    /** SHA-256 over a stream, read through one fixed buffer and never held. */
    private fun digestOf(open: () -> InputStream): List<Byte> {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(JournalScanPolicy.BUFFER_BYTES)
        open().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toList()
    }

    /**
     * THE PIN. A 300 MiB stream the heap cannot hold arrives in the zip byte
     * for byte, and the published `header.json` arrives with it.
     *
     * Byte equality by digest rather than by comparing arrays, because
     * holding either copy in memory is the thing being ruled out.
     */
    @Test
    fun `an oversize capture reaches the zip whole on a heap that cannot hold it`() {
        val dir = directory()
        val stream = File(dir, SetJournal.IMU)
        appendChunks(stream, ByteArray(CHUNK_BYTES) { FILLER }, RUNAWAY_CHUNKS)

        val orphan = store().orphans().single()
        assertTrue(orphan.imu?.malformed == true, "the fixture is not the newline-free shape #271 measured")
        assertEquals(RUNAWAY_BYTES, orphan.imu?.bytes, "the fixture is not the size this pin is about")

        val archive = File(outbox, "capture.zip")
        store().zipTo(orphan, archive)

        val names = mutableSetOf<String>()
        var entryBytes = -1L
        var entryDigest = emptyList<Byte>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (entry.name != SetJournal.IMU) continue
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(JournalScanPolicy.BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                    total += read.toLong()
                }
                entryBytes = total
                entryDigest = md.digest().toList()
            }
        }

        assertTrue(SetJournal.IMU in names, "the archive was sent without the capture's only stream")
        assertTrue(SetJournalStore.HEADER_FILE in names, "the archive was sent without its header")
        assertEquals(RUNAWAY_BYTES, entryBytes, "the stream did not arrive whole")
        assertEquals(digestOf { stream.inputStream() }, entryDigest, "the bytes that arrived are not the bytes on disk")
    }

    private companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val FILLER: Byte = 0x78 // 'x' -- anything that is not a newline
        const val RUNAWAY_CHUNKS = 300
        const val RUNAWAY_BYTES = RUNAWAY_CHUNKS.toLong() * CHUNK_BYTES
    }
}
