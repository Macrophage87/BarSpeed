package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Who a set was, written before any of it.
 *
 * Persisted as `header.json`, flushed and closed before the first sample line
 * of any stream. A directory that exists therefore always has an identity: a
 * capture with no idea which exercise it belongs to is a gap nothing
 * downstream can represent, and it would have to be discarded on sight.
 *
 * [imuConnected] is the sensor state the caller observed at the moment the set
 * began, and it is here so that a stream of zero samples is never read as a
 * measurement. Zero samples with the sensor connected is a failure; zero
 * samples with no sensor is the correct outcome of a manually counted set.
 * Nothing else in the directory can tell those two apart.
 *
 * [sessionId] is nullable and the null is load-bearing. No session row exists
 * until the first set of a session has been durably written, so a journal
 * opened for that first set genuinely has no session to name.
 */
@Serializable
data class SetJournalHeader(
    val journalVersion: Int = SetJournalStore.JOURNAL_VERSION,
    val exerciseId: String,
    val exerciseName: String,
    val sessionId: Long?,
    val sessionStartedAtMs: Long,
    val startedAtMs: Long,
    val orderIdx: Int,
    val imuConnected: Boolean,
    val planName: String? = null,
    val planSessionName: String? = null,
)

/**
 * An interrupted set found on disk, and everything of it that survived.
 *
 * [repMarks] are the epoch-ms instants at which a rep was counted, by the
 * lifter thumbing the button or by the guided cadence runner. Marks rather
 * than a running total, because a total rewritten on every tap can be stale by
 * one while a mark is a fact with a clock on it -- and because the rep count
 * of a set nobody finished is recoverable from no stream at all: the sensor
 * records what the bar did, never what the lifter decided it was worth.
 */
data class OrphanedSet(
    val header: SetJournalHeader,
    val imuSamples: List<ImuSample>,
    val hrSamples: List<HrSample>,
    val cues: List<VoiceCue>,
    val repMarks: List<Long>,
    val directory: File,
)

/**
 * The durable tail of a set that is still being performed.
 *
 * Appends arrive from the sample collectors on the main thread and are handed
 * to a channel rather than written there; the pump drains it on whatever
 * dispatcher the owning [SetJournalStore] was built with. [appendImu] and its
 * siblings neither suspend nor touch a file descriptor, so a flush cannot land
 * on the frame that draws the live velocity readout.
 *
 * The channel is deliberately unbounded. A bounded one would have to drop, and
 * a drop would reintroduce the unrepresentable gap this mechanism exists to
 * prevent, inside the mechanism itself. The bound on its growth is the bound
 * the in-memory sample buffer already lives with: one set's length.
 */
class SetJournal internal constructor(
    val directory: File,
    scope: CoroutineScope,
) {
    private sealed interface Cmd

    private data class Row(val kind: String, val text: String) : Cmd

    private data class Sync(val ack: CompletableDeferred<Unit>) : Cmd

    private val channel = Channel<Cmd>(Channel.UNLIMITED)
    private val writers = mutableMapOf<String, BufferedWriter>()
    private val marks = mutableListOf<Long>()
    private var imuIndex = 0L
    private var sinceFlush = 0
    private var lastFlushMs = System.currentTimeMillis()

    private val pump: Job =
        scope.launch {
            for (cmd in channel) {
                when (cmd) {
                    is Row -> append(cmd)
                    is Sync -> {
                        flushAll()
                        cmd.ack.complete(Unit)
                    }
                }
            }
            flushAll()
            writers.values.forEach { w -> runCatching { w.close() } }
            writers.clear()
        }

    fun appendImu(sample: ImuSample) {
        val text =
            String.format(
                Locale.US,
                "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d",
                sample.timestampMs, sample.axG, sample.ayG, sample.azG,
                sample.wxDps, sample.wyDps, sample.wzDps,
                sample.rollDeg, sample.pitchDeg, sample.yawDeg, imuIndex++,
            )
        channel.trySend(Row(IMU, text))
    }

    fun appendHr(sample: HrSample) {
        val rr = sample.rrIntervalsMs.joinToString("|") { String.format(Locale.US, "%.1f", it) }
        channel.trySend(Row(HRM, "${sample.timestampMs},${sample.bpm},$rr"))
    }

    fun appendCue(cue: VoiceCue) {
        channel.trySend(Row(CUES, "${cue.timestampMs},${cue.cue.replace(",", " ")}"))
    }

    /**
     * The instants this journal has been told a rep was counted at, in order.
     *
     * Held here as well as written to `reps.csv` because the two answer
     * different questions and only one of them can be asked while the set is
     * still running: the file is what survives the process dying, and this is
     * what the set that LANDS is built from (#158). One writer feeds both, so
     * a recovered capture and a stored set cannot disagree about a mark.
     *
     * Read and written on the caller's thread, which for both production call
     * sites is the main thread -- unlike the streams, which are handed to the
     * pump. A copy, so a caller holding it cannot see the list grow under it.
     *
     * The marks are lost if no journal could be opened at all, which is the
     * same window in which the interrupted-set capture does not exist either.
     */
    val repMarks: List<Long> get() = marks.toList()

    /**
     * A rep was counted at [timestampMs], by the lifter or by the guide.
     *
     * Its own stream rather than a line in the cue track. A cue is something
     * the app SAID -- the guide speaks "Rep 1" on a schedule whether or not
     * anybody moved -- and a mark is something the lifter DID. Sharing a file
     * would make the two indistinguishable on read-back, and the second is the
     * only fact in a set that no reprocessing of any stream can rebuild.
     */
    fun appendRepMark(timestampMs: Long) {
        marks += timestampMs
        channel.trySend(Row(REPS, timestampMs.toString()))
    }

    private fun append(row: Row) {
        val writer =
            writers.getOrPut(row.kind) {
                val file = File(directory, row.kind)
                val fresh = !file.exists() || file.length() == 0L
                file.bufferedWriter(Charsets.UTF_8, BUFFER_BYTES).also { w ->
                    if (fresh) {
                        w.write(headerFor(row.kind))
                        w.write("\n")
                    }
                }
            }
        writer.write(row.text)
        writer.write("\n")
        sinceFlush++
        val now = System.currentTimeMillis()
        if (sinceFlush >= FLUSH_EVERY_LINES || now - lastFlushMs >= FLUSH_EVERY_MS) flushAll()
    }

    private fun flushAll() {
        writers.values.forEach { w -> runCatching { w.flush() } }
        sinceFlush = 0
        lastFlushMs = System.currentTimeMillis()
    }

    private fun headerFor(kind: String): String = when (kind) {
        IMU -> ImuCsv.HEADER
        HRM -> HrCsv.HEADER
        REPS -> REPS_HEADER
        else -> CueCsv.HEADER
    }

    /**
     * Push everything queued so far all the way to the filesystem, and stay
     * open.
     *
     * The flush constants approximate this on a cadence; this is the same
     * operation asked for explicitly, and it is what bounds how much of a set
     * lives only in this process at a given moment.
     */
    suspend fun sync() {
        val ack = CompletableDeferred<Unit>()
        if (channel.trySend(Sync(ack)).isSuccess) ack.await()
    }

    /**
     * The set is over: stop accepting lines, drain, and release the handles.
     *
     * The capture stays on disk. The set being OVER and the set being SAFE are
     * a whole durable write apart, and that gap is the window this file exists
     * to cover; deleting here would empty it exactly while the write meant to
     * replace it is still in flight. [discard] is what ends the capture, and
     * only the row landing calls it.
     */
    suspend fun close() {
        channel.close()
        pump.join()
    }

    /** The set is stored: the capture has done its job and can go. */
    fun discard() {
        directory.deleteRecursively()
    }

    companion object {
        const val IMU = "imu.csv"
        const val HRM = "hrm.csv"
        const val CUES = "cues.csv"
        const val REPS = "reps.csv"

        /**
         * One epoch-ms instant per line; the count is the number of lines.
         *
         * [RepMarkCsv.HEADER] rather than a second literal: the journal and
         * the stored stream are one format, and a header written in two
         * places is a header that drifts in one of them.
         */
        const val REPS_HEADER = RepMarkCsv.HEADER

        /**
         * How much sits in this process before a line reaches the filesystem.
         * [FLUSH_EVERY_LINES] and [FLUSH_EVERY_MS] are the bounds that matter;
         * this only decides how often a flush has nothing to do.
         */
        const val BUFFER_BYTES = 64 * 1024
        const val FLUSH_EVERY_LINES = 200
        const val FLUSH_EVERY_MS = 1_000L
    }
}

/**
 * Where interrupted sets are kept, and how they are found again.
 *
 * [root] is a directory in the app's own private storage. A plain file tree
 * rather than a database table, which is a ruling rather than a convenience;
 * the commit that introduced this file gives the reasons.
 */
class SetJournalStore(
    private val root: File,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Open a capture for a set about to begin, or null if the disk refuses. */
    fun open(header: SetJournalHeader): SetJournal? {
        val dir = File(root, "s${header.sessionStartedAtMs}/set${header.orderIdx}-${header.startedAtMs}")
        return runCatching {
            dir.mkdirs()
            // Written and closed before any stream exists. A directory without
            // this file cannot say what it is a recording of.
            File(dir, HEADER_FILE).writeText(json.encodeToString(SetJournalHeader.serializer(), header))
            SetJournal(dir, scope)
        }.getOrNull()
    }

    /** Every interrupted set on disk, oldest first. */
    fun orphans(): List<OrphanedSet> = root.walkTopDown()
        .maxDepth(WALK_DEPTH)
        .filter { it.isDirectory && it != root }
        .mapNotNull { dir -> read(dir) }
        .sortedBy { it.header.startedAtMs }
        .toList()

    fun discard(orphan: OrphanedSet) {
        orphan.directory.deleteRecursively()
    }

    /**
     * An interrupted capture as a zip the lifter can send themselves.
     *
     * The only way this data leaves the phone. App-private storage is not
     * browsable, so without this the capture is safe and permanently out of
     * reach -- which is a strange thing to offer somebody whose set it is.
     *
     * The files are copied in exactly as they lie, uncompressed and
     * unconverted. They are already the canonical CSV that
     * `core/dsp/src/test/resources/field-*.csv` fixtures are written in, so a
     * capture that exposes a defect converts into a regression fixture with no
     * transformation at all -- which is this repository's discharge ritual for
     * a hardware-found bug.
     *
     * A stream that will not read is skipped rather than failing the export.
     * Some of the capture is worth more than none of it, and the whole reason
     * this file exists is that the process was killed partway.
     */
    fun zip(orphan: OrphanedSet): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            orphan.directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                runCatching {
                    val bytes = file.readBytes()
                    zip.putNextEntry(ZipEntry(file.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * One interrupted set, or null when this directory cannot honestly be
     * offered as one.
     *
     * Null in three cases and none of them is softened into a value: no header,
     * a header that will not parse, and a header written by a version of this
     * format that did not exist when this code was compiled. The first two used
     * to be answered with an invented placeholder -- exercise "unknown", the
     * directory mtime standing in for when the set began -- which is this
     * repository's dominant defect wearing a helpful face. The card would have
     * named an exercise nobody performed at a time nobody lifted, and nothing
     * would have told the lifter that apart from a real find.
     *
     * The version gate refuses only a FUTURE format. Older ones stay readable,
     * which is the entire purpose of carrying a version; the direction that has
     * to be refused is the one where a layout this code has never seen is read
     * as data anyway. A file format has no compiler standing behind it.
     */
    private fun read(dir: File): OrphanedSet? {
        val header =
            runCatching {
                json.decodeFromString(SetJournalHeader.serializer(), File(dir, HEADER_FILE).readText())
            }.getOrNull() ?: return null
        if (header.journalVersion > JOURNAL_VERSION) return null
        return OrphanedSet(
            header = header,
            imuSamples = decode(dir, SetJournal.IMU) { ImuCsv.decode(it) },
            hrSamples = decode(dir, SetJournal.HRM) { HrCsv.decode(it) },
            cues = decode(dir, SetJournal.CUES) { CueCsv.decode(it) },
            repMarks = decode(dir, SetJournal.REPS) { line -> RepMarkCsv.decodeLine(line) },
            directory = dir,
        )
    }

    /**
     * Everything up to the first line that will not parse.
     *
     * A process killed mid-append leaves a final line with fewer fields than
     * the format has columns, and `ImuCsv.decode` does `require(f.size >= 10)`
     * -- it THROWS on precisely that line. Handing it the file whole therefore
     * discarded the entire capture over the forty bytes nobody finished
     * writing: two minutes of lifting lost, and lost silently, because the
     * throw was caught and the result was an empty list rather than a crash.
     *
     * So the file is parsed a line at a time and the first refusal ends the
     * read. The partial row is dropped rather than repaired or guessed at -- a
     * half-written row is not a measurement -- and everything before it is
     * kept, which also means a stream damaged in the middle costs only what
     * follows the damage instead of all of it.
     */
    private fun <T> decode(dir: File, name: String, parse: (String) -> List<T>): List<T> {
        val file = File(dir, name)
        if (!file.isFile) return emptyList()
        val out = mutableListOf<T>()
        file.useLines { lines ->
            for (line in lines) {
                out += runCatching { parse(line) }.getOrNull() ?: break
            }
        }
        return out
    }

    companion object {
        /** Bumped when the on-disk layout stops being readable by older code. */
        const val JOURNAL_VERSION = 1
        const val HEADER_FILE = "header.json"

        /** `root/s<sessionStart>/set<n>-<ms>` sits two levels down. */
        const val WALK_DEPTH = 2
    }
}
