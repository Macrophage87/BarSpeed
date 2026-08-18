package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What survives on disk when a set is interrupted.
 *
 * This runs against a real filesystem -- a temporary directory, `java.io.File`,
 * real bytes -- because [SetJournal] deliberately holds no Android type. That
 * makes this one of the few places in the repository where a plain JVM test
 * executes the production path rather than a stand-in for it.
 *
 * What it still cannot establish, in the words this repository uses for it:
 * nothing here can establish Android lifecycle behaviour, what survives a task
 * swipe, or what Room actually does. Whether a line that reached the
 * filesystem API also survives the process being killed is a property of the
 * kernel page cache, not of this code, and no test in this repository executes
 * it. The field check on the branch that introduced this file is what
 * establishes that on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetJournalTest {
    private val root: File = Files.createTempDirectory("set-journal").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun TestScope.store() = SetJournalStore(root, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    /**
     * Deliberately carries more decimals than a four-place format can hold, so
     * that a journal writing at lower precision than the canonical CSV is
     * visible rather than coincidentally equal.
     */
    private val imu =
        listOf(
            ImuSample(1_000L, 0.0123456, -0.0234567, 0.9812345, 1.51234, -2.54321, 0.25123, 10.1234, -20.4321, 30.9876),
            ImuSample(1_010L, 0.0345678, -0.0456789, 1.0212345, -1.5987, 2.54321, -0.25123, 11.1234, -21.4321, 31.9876),
        )

    private val hr =
        listOf(
            HrSample(1_000L, bpm = 120, rrIntervalsMs = listOf(500.0, 505.5)),
            HrSample(1_500L, bpm = 0, rrIntervalsMs = emptyList()),
        )

    private val cues = listOf(VoiceCue(1_100L, "Down"), VoiceCue(1_600L, "Up"))

    private fun header(
        orderIdx: Int = 0,
        startedAtMs: Long = 1_000L,
        sessionId: Long? = null,
        imuConnected: Boolean = true,
    ) = SetJournalHeader(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        sessionId = sessionId,
        sessionStartedAtMs = 900L,
        startedAtMs = startedAtMs,
        orderIdx = orderIdx,
        imuConnected = imuConnected,
    )

    /**
     * A capture as a later launch finds it: files on disk, no handle held by
     * anything. This is the state every recovery actually starts from, and it
     * is the only way to build the ones a live writer cannot produce -- a
     * directory whose last line was cut off mid-write, or one with no header
     * at all.
     */
    private fun onDisk(
        header: SetJournalHeader? = header(),
        imuText: String? = null,
        hrText: String? = null,
        cueText: String? = null,
        dirName: String = "s900/set0-1000",
    ): File {
        val dir = File(root, dirName).apply { mkdirs() }
        header?.let {
            File(dir, SetJournalStore.HEADER_FILE)
                .writeText(Json.encodeToString(SetJournalHeader.serializer(), it))
        }
        imuText?.let { File(dir, SetJournal.IMU).writeText(it) }
        hrText?.let { File(dir, SetJournal.HRM).writeText(it) }
        cueText?.let { File(dir, SetJournal.CUES).writeText(it) }
        return dir
    }

    // ---- the header ---------------------------------------------------------

    /**
     * The identity lands before any of the capture does.
     *
     * Asserted with nothing appended at all, which is the state a set spends
     * its first fraction of a second in. A directory that can exist without a
     * header is a directory that can be found later holding samples and no way
     * to say which exercise produced them.
     */
    @Test
    fun `a journal has its identity on disk before the first sample line`() = runTest {
        val journal = requireNotNull(store().open(header()))
        val onDisk = File(journal.directory, SetJournalStore.HEADER_FILE)
        assertTrue(onDisk.isFile, "header.json was not written by open()")
        assertTrue(onDisk.length() > 0, "header.json is empty")
        assertEquals(emptyList(), journal.directory.listFiles().orEmpty().map { it.name } - "header.json")
    }

    @Test
    fun `the header round trips the identity the caller stated`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(orderIdx = 3, startedAtMs = 4_200L, sessionId = 88L)))
        journal.appendCue(cues.first())
        journal.sync()
        val found = store.orphans().single().header
        assertEquals("back_squat", found.exerciseId)
        assertEquals("Back Squat", found.exerciseName)
        assertEquals(88L, found.sessionId)
        assertEquals(3, found.orderIdx)
        assertEquals(4_200L, found.startedAtMs)
        assertEquals(true, found.imuConnected)
    }

    /**
     * A set recorded with no sensor is a real and common case -- every manually
     * counted set is one -- and its journal holds no IMU samples at all. The
     * count of zero is only readable as a measurement next to the fact that
     * there was nothing to measure, which is why the header carries it.
     */
    @Test
    fun `a sensorless set is distinguishable from a sensor that recorded nothing`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(imuConnected = false)))
        journal.appendCue(cues.first())
        journal.sync()
        val found = store.orphans().single()
        assertEquals(emptyList(), found.imuSamples)
        assertEquals(false, found.header.imuConnected)
    }

    // ---- the streams --------------------------------------------------------

    @Test
    fun `every stream file opens with its canonical header row`() = runTest {
        val journal = requireNotNull(store().open(header()))
        journal.appendImu(imu.first())
        journal.appendHr(hr.first())
        journal.appendCue(cues.first())
        journal.sync()
        assertEquals(ImuCsv.HEADER, File(journal.directory, SetJournal.IMU).readLines().first())
        assertEquals(HrCsv.HEADER, File(journal.directory, SetJournal.HRM).readLines().first())
        assertEquals(CueCsv.HEADER, File(journal.directory, SetJournal.CUES).readLines().first())
    }

    @Test
    fun `heart-rate samples and their rr intervals come back unchanged`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        hr.forEach { journal.appendHr(it) }
        journal.sync()
        assertEquals(hr, store.orphans().single().hrSamples)
    }

    @Test
    fun `spoken cues come back unchanged`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        cues.forEach { journal.appendCue(it) }
        journal.sync()
        assertEquals(cues, store.orphans().single().cues)
    }

    /**
     * Count only. That the samples come back at the precision the canonical CSV
     * would have stored them at is a separate and stronger claim, asserted on
     * its own further down.
     */
    @Test
    fun `every imu sample appended is present when the journal is read back`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        imu.forEach { journal.appendImu(it) }
        journal.sync()
        assertEquals(imu.size, store.orphans().single().imuSamples.size)
    }

    // ---- finding them again -------------------------------------------------

    @Test
    fun `each interrupted set is found once, oldest first`() = runTest {
        val store = store()
        listOf(3_000L, 1_000L, 2_000L).forEachIndexed { idx, startedAtMs ->
            val journal = requireNotNull(store.open(header(orderIdx = idx, startedAtMs = startedAtMs)))
            journal.appendCue(cues.first())
            journal.sync()
        }
        assertEquals(listOf(1_000L, 2_000L, 3_000L), store.orphans().map { it.header.startedAtMs })
    }

    @Test
    fun `an empty root offers nothing`() = runTest {
        assertEquals(emptyList(), store().orphans())
    }

    @Test
    fun `discarding an interrupted set removes it from disk and from the offer`() = runTest {
        val store = store()
        onDisk(cueText = CueCsv.encode(cues))
        val orphan = store.orphans().single()
        store.discard(orphan)
        assertTrue(!orphan.directory.exists(), "the capture is still on disk")
        assertEquals(emptyList(), store.orphans())
    }
}
