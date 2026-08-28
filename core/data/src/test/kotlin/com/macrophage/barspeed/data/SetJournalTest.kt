package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
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

    /**
     * A `header.json` as a build before issue #156 wrote one: the keys that
     * build had, and nothing for anything added since. Written as literal text
     * rather than encoded from the current class, which would re-acquire every
     * field added after the fact and pin nothing.
     */
    private val legacyHeaderJson =
        """
        {"journalVersion":1,"exerciseId":"back_squat","exerciseName":"Back Squat","sessionId":null,
        "sessionStartedAtMs":900,"startedAtMs":1000,"orderIdx":0,"imuConnected":true}
        """.trimIndent()

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

    /**
     * The header names the second link's observed state, as it names the
     * first's.
     *
     * Encoded with `encodeDefaults`, not through the production writer.
     * kotlinx.serialization omits a property equal to its default, so a real
     * `header.json` for a one-sensor set carries no such key and a check
     * against one would pass or fail on the wrong evidence.
     */
    @Test
    fun `the header carries the second link's observed connection`() {
        val text = Json { encodeDefaults = true }.encodeToString(SetJournalHeader.serializer(), header())
        assertTrue(
            "secondaryImuConnected" in text,
            "SetJournalHeader has no field for the second link's observed state: $text",
        )
    }

    /**
     * A header written by a build that had never heard of a second link is
     * still recovered whole.
     *
     * `SetJournalStore.read` decodes inside `runCatching { }.getOrNull() ?:
     * return null`, so a required field an older document cannot supply does
     * not surface as an error: the directory is dropped and the capture is
     * never offered back. The default on the field is what stands between an
     * added header fact and a discarded orphan.
     *
     * The VALUE is asserted too, not only the recovery: `secondaryImuConnected`
     * is what the interrupted-set card reads to say a second stream is missing,
     * and a legacy header never had one.
     */
    @Test
    fun `a header written before the second link existed still decodes`() = runTest {
        val dir = File(root, "s900/set0-1000").apply { mkdirs() }
        File(dir, SetJournalStore.HEADER_FILE).writeText(legacyHeaderJson)
        File(dir, SetJournal.CUES).writeText(CueCsv.encode(cues))
        val found = store().orphans().single()
        assertEquals("back_squat", found.header.exerciseId)
        assertEquals(0, found.header.orderIdx)
        assertEquals(true, found.header.imuConnected)
        assertEquals(false, found.header.secondaryImuConnected)
        assertEquals(cues, found.cues)
    }

    // ---- the second accelerometer, issue #156 -------------------------------

    /**
     * A dual capture puts the analysed stream where it has always been and the
     * other one beside it.
     *
     * `imu.csv` keeping its name is the whole reason `JOURNAL_VERSION` does not
     * move: the version gate refuses only a FUTURE format, so an older build
     * meeting this directory still recovers the header, the analysed capture,
     * heart rate, cues and marks, and ignores one file it has never heard of.
     * Bumping would make it return null for the directory and DISCARD a set it
     * could have offered back.
     */
    @Test
    fun `a dual capture writes the analysed stream to imu csv and the other beside it`() = runTest {
        val store = store()
        val journal =
            requireNotNull(
                store.open(
                    header().copy(
                        sensorRoles = listOf(SensorRole.A, SensorRole.B),
                        analysedRole = SensorRole.A,
                    ),
                ),
            )
        imu.forEach { journal.appendImu(it) }
        imu.forEach { journal.appendSecondaryImu(it, SensorRole.B) }
        journal.sync()

        assertEquals(
            setOf("header.json", "imu.csv", "imu-b.csv"),
            journal.directory.listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertEquals(
            ImuCsv.HEADER,
            File(journal.directory, "imu-b.csv").readLines().first(),
            "the second stream is not written in the canonical format",
        )
    }

    /**
     * Each file's `sample_idx` counts its own rows.
     *
     * `ImuCsv`'s contract for that column is "THIS loop's own index, 0..n-1 by
     * construction", and the reader that decodes this file reads one file. A
     * shared counter would publish a stream whose indices begin at whatever
     * the other stream had reached, which reads as a capture that dropped its
     * first rows.
     */
    @Test
    fun `each capture indexes its own rows from zero`() = runTest {
        val journal =
            requireNotNull(
                store().open(
                    header().copy(sensorRoles = listOf(SensorRole.A, SensorRole.B), analysedRole = SensorRole.A),
                ),
            )
        imu.forEach { journal.appendImu(it) }
        imu.forEach { journal.appendSecondaryImu(it, SensorRole.B) }
        journal.sync()

        fun indices(name: String) = File(journal.directory, name)
            .readLines().drop(1).filter { it.isNotBlank() }.map { it.substringAfterLast(',') }
        assertEquals(listOf("0", "1"), indices("imu.csv"))
        assertEquals(listOf("0", "1"), indices("imu-b.csv"))
    }

    /**
     * The second stream comes back off disk as the second stream, and the
     * analysed one is untouched by its presence.
     */
    @Test
    fun `a recovered dual capture carries both streams, kept apart`() = runTest {
        val store = store()
        val journal =
            requireNotNull(
                store.open(
                    header().copy(sensorRoles = listOf(SensorRole.A, SensorRole.B), analysedRole = SensorRole.A),
                ),
            )
        journal.appendImu(imu.first())
        imu.forEach { journal.appendSecondaryImu(it, SensorRole.B) }
        journal.sync()

        val found = store.orphans().single()
        assertEquals(1, found.imuSamples.size, "the analysed stream picked up the other one's rows")
        assertEquals(2, found.secondaryImuSamples.size)
        assertEquals(listOf(SensorRole.A, SensorRole.B), found.header.sensorRoles)
        assertEquals(SensorRole.A, found.header.analysedRole)
    }

    /**
     * A one-sensor capture reads back exactly as it always did.
     *
     * The header's two new fields default, so a directory written by any
     * earlier build decodes, and nothing about the recovered object moved for
     * a set that had one sensor.
     */
    @Test
    fun `a one-sensor capture recovers with no second stream and no roles`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        imu.forEach { journal.appendImu(it) }
        journal.sync()

        val found = store.orphans().single()
        assertEquals(imu.size, found.imuSamples.size)
        assertEquals(emptyList(), found.secondaryImuSamples)
        assertEquals(emptyList(), found.header.sensorRoles)
        assertEquals(null, found.header.analysedRole)
    }

    /**
     * A header written before these fields existed still decodes, and reads as
     * one sensor.
     *
     * The JSON is written by hand rather than by copying an old build's
     * output, because what is being asserted is the absence of the two keys:
     * `ignoreUnknownKeys` handles a key the reader does not know, and does
     * nothing at all for a key the reader requires and cannot find. That is
     * the failure mode that would silently drop every orphaned set on disk at
     * upgrade.
     */
    @Test
    fun `a header written before roles existed still recovers its set`() = runTest {
        val legacy =
            """
            {"journalVersion":1,"exerciseId":"back_squat","exerciseName":"Back Squat","sessionId":null,
             "sessionStartedAtMs":900,"startedAtMs":1000,"orderIdx":0,"imuConnected":true}
            """.trimIndent()
        val dir = File(root, "s900/set0-1000").apply { mkdirs() }
        File(dir, SetJournalStore.HEADER_FILE).writeText(legacy)
        File(dir, SetJournal.IMU).writeText(ImuCsv.encode(imu))

        val found = store().orphans().single()
        assertEquals(imu.size, found.imuSamples.size)
        assertEquals(emptyList(), found.header.sensorRoles)
        assertEquals(emptyList(), found.secondaryImuSamples)
    }

    /**
     * A stray `imu-b.csv` the header never declared is not read.
     *
     * The header is written and closed before the first sample line of any
     * stream, so it is the one thing in the directory that cannot be a
     * half-truth. Scanning for whichever role file happened to be present
     * would present a leftover -- from an interrupted build, or a directory
     * the lifter copied -- as this capture's second sensor.
     */
    @Test
    fun `an undeclared second stream on disk is not offered as this set's`() = runTest {
        val dir = File(root, "s900/set0-1000").apply { mkdirs() }
        File(dir, SetJournalStore.HEADER_FILE)
            .writeText(Json.encodeToString(SetJournalHeader.serializer(), header()))
        File(dir, SetJournal.IMU).writeText(ImuCsv.encode(imu))
        File(dir, "imu-b.csv").writeText(ImuCsv.encode(imu))

        assertEquals(emptyList(), store().orphans().single().secondaryImuSamples)
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

    // ---- what a killed process leaves behind --------------------------------

    /**
     * The sharpest hazard in the whole design.
     *
     * A process killed mid-append leaves a final line with fewer fields than
     * the format has columns. `ImuCsv.decode` does `require(f.size >= 10)` and
     * THROWS on exactly that line, so handing it the file whole discards the
     * entire capture over its last forty bytes -- two minutes of lifting lost
     * to the one line nobody finished writing.
     *
     * The complete samples are kept and the partial one is dropped. Not
     * repaired, not guessed at: a half-written row is not a measurement.
     */
    @Test
    fun `a capture cut off mid-line keeps every complete sample`() = runTest {
        onDisk(imuText = ImuCsv.encode(imu) + "1020,0.0345678,-0.04")
        assertEquals(imu.size, store().orphans().single().imuSamples.size)
    }

    @Test
    fun `a capture cut off mid-line still reports the rest of its streams`() = runTest {
        onDisk(imuText = ImuCsv.encode(imu) + "1020,0.03", cueText = CueCsv.encode(cues))
        val found = store().orphans().single()
        assertEquals(imu.size, found.imuSamples.size)
        assertEquals(cues, found.cues)
    }

    /**
     * A directory with no header is refused outright rather than given an
     * invented one.
     *
     * A placeholder identity is this repository's dominant defect wearing a
     * helpful face: the card would name an exercise nobody performed, at a time
     * nobody lifted, and the lifter would have no way to tell that from a real
     * find. Absence has to stay absence.
     */
    @Test
    fun `a capture with no header is not offered for recovery`() = runTest {
        onDisk(header = null, cueText = CueCsv.encode(cues), imuText = ImuCsv.encode(imu))
        assertEquals(emptyList(), store().orphans())
    }

    @Test
    fun `a capture whose header will not parse is not offered for recovery`() = runTest {
        val dir = onDisk(header = null, cueText = CueCsv.encode(cues))
        File(dir, SetJournalStore.HEADER_FILE).writeText("{ this is not json")
        assertEquals(emptyList(), store().orphans())
    }

    /**
     * A capture written by a future version of this app is refused rather than
     * read optimistically.
     *
     * A file format has no compiler behind it. The version is the only thing
     * that stops a layout change being read as data, and reading it
     * optimistically would publish samples that were never sampled.
     */
    @Test
    fun `a capture written by a newer journal format is refused, not parsed`() = runTest {
        onDisk(
            header = header().copy(journalVersion = SetJournalStore.JOURNAL_VERSION + 1),
            imuText = ImuCsv.encode(imu),
        )
        assertEquals(emptyList(), store().orphans())
    }

    // ---- fidelity -----------------------------------------------------------

    /**
     * The journal must not be lossier than the format the set would have been
     * stored in.
     *
     * Asserted as the bytes `recordSet` would gzip, because that is the artifact
     * that ships to an LLM and gets compared against a field fixture -- not as
     * sample equality, which passes on a re-encode that merely rounds
     * differently. The fixture carries seven decimals precisely so that a
     * writer using the four-place gyro format for the accelerometer columns is
     * visible here instead of coincidentally equal.
     */
    @Test
    fun `journalled samples re-encode to the bytes the live buffer would have stored`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        imu.forEach { journal.appendImu(it) }
        journal.sync()
        assertEquals(ImuCsv.encode(imu), ImuCsv.encode(store.orphans().single().imuSamples))
    }

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Closing is not storing.
     *
     * The set being over and the set being safe are different facts a whole
     * durable write apart, and the window between them is the one this branch
     * exists to close. A journal destroyed when the set ended would be gone
     * exactly while the write that replaces it is still in flight -- and if
     * that write is what failed, nothing anywhere would hold the capture.
     */
    @Test
    fun `a closed journal is still on disk, because the set is over and not yet stored`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        journal.appendCue(cues.first())
        journal.close()
        assertTrue(journal.directory.exists(), "closing the journal destroyed the capture")
        assertEquals(1, store.orphans().size, "the capture is on disk but no longer offered")
    }

    @Test
    fun `discarding a closed journal is what finally removes it`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        journal.appendCue(cues.first())
        journal.close()
        journal.discard()
        assertTrue(!journal.directory.exists(), "the stored set left its capture behind")
        assertEquals(emptyList(), store.orphans())
    }

    // ---- the count no stream can rebuild ------------------------------------

    /**
     * The rep count is the one thing in the window no reprocessing can recover.
     *
     * The sensor records what the bar did; it never records what the lifter
     * decided a rep was worth. A spoken cue is not evidence of one either --
     * the guide says "Rep 1" on a schedule, whether or not anybody moved.
     */
    @Test
    fun `a rep the lifter counted is recoverable as a mark with its own clock`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        journal.appendCue(VoiceCue(1_050L, "Rep 1"))
        journal.appendRepMark(1_100L)
        journal.appendRepMark(1_900L)
        journal.sync()
        val found = store.orphans().single()
        assertEquals(listOf(1_100L, 1_900L), found.repMarks)
        assertEquals(
            listOf(VoiceCue(1_050L, "Rep 1")),
            found.cues,
            "a cue the app spoke was counted as a rep the lifter performed",
        )
    }

    /**
     * The journal remembers, in memory, every mark it was handed -- in order,
     * and without waiting for the pump.
     *
     * This is what the set that LANDS is built from (#158). `reps.csv` is what
     * survives the process dying, and it cannot answer for a set that finished
     * normally: the capture is discarded the moment the row is stored, and the
     * write that stores it needs the marks BEFORE that. Reading the file back
     * at set end instead would make the stored set depend on how much of the
     * capture had reached the filesystem, which is the one property the pump
     * exists to keep the recording path from caring about.
     *
     * No `sync()` here, deliberately: this must hold before anything is
     * flushed, because the freeze that reads it happens on the main thread the
     * instant the set ends.
     */
    @Test
    fun `the journal remembers in memory every mark it was given`() = runTest {
        val journal = requireNotNull(store().open(header()))
        journal.appendRepMark(1_100L)
        journal.appendRepMark(4_350L)
        journal.appendRepMark(8_020L)
        assertEquals(listOf(1_100L, 4_350L, 8_020L), journal.repMarks)
        journal.close()
    }

    /** A set that counted nothing has no marks, and says so as an empty list. */
    @Test
    fun `a journal nobody counted on remembers no marks`() = runTest {
        val journal = requireNotNull(store().open(header()))
        journal.appendCue(cues.first())
        assertEquals(emptyList(), journal.repMarks)
        journal.close()
    }

    /**
     * The bytes of `reps.csv`, exactly: a header line, then one epoch-ms
     * instant per line and nothing else.
     *
     * The round trip above goes through this file's own reader, so the two
     * halves could drift together and stay green. This pins the ON-DISK form,
     * which is the half a reader outside this class sees -- the capture is
     * shared as a zip of these files verbatim, and it is also the form the
     * canonical stream codecs have to agree with if this column is ever to
     * mean the same thing in a journal and in an export.
     */
    @Test
    fun `the journal's rep marks are one epoch-ms instant per line under a header`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header()))
        journal.appendRepMark(1_100L)
        journal.appendRepMark(1_900L)
        journal.close()
        val text = File(store.orphans().single().directory, SetJournal.REPS).readText()
        assertEquals("timestamp_ms\n1100\n1900\n", text, "the on-disk rep-mark format moved")
    }

    // ---- getting it off the phone -------------------------------------------

    /**
     * The zip carries the capture verbatim, because that is what makes it
     * useful twice: once to the lifter who wants their set, and once to this
     * repository, where a capture that exposed a defect becomes a regression
     * fixture with no transformation. Byte equality, not "contains something
     * plausible".
     */
    @Test
    fun `an interrupted capture zips to its files, byte for byte`() = runTest {
        val store = store()
        onDisk(imuText = ImuCsv.encode(imu), cueText = CueCsv.encode(cues))
        val entries = unzip(store.zip(store.orphans().single()))
        assertEquals(
            listOf(SetJournalStore.HEADER_FILE, SetJournal.CUES, SetJournal.IMU).sorted(),
            entries.keys.sorted(),
        )
        assertEquals(ImuCsv.encode(imu), entries.getValue(SetJournal.IMU))
        assertEquals(CueCsv.encode(cues), entries.getValue(SetJournal.CUES))
    }

    /**
     * Some of a capture is worth more than none of it. The whole reason this
     * file exists is that the process was killed partway, so a stream cut off
     * mid-line must not be what stops the lifter getting the rest.
     */
    @Test
    fun `a capture cut off mid-line still zips everything it has`() = runTest {
        val store = store()
        val ragged = ImuCsv.encode(imu) + "1020,0.03"
        onDisk(imuText = ragged, cueText = CueCsv.encode(cues))
        val entries = unzip(store.zip(store.orphans().single()))
        assertEquals(ragged, entries.getValue(SetJournal.IMU), "the zip repaired or truncated the raw capture")
        assertEquals(CueCsv.encode(cues), entries.getValue(SetJournal.CUES))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }
}
