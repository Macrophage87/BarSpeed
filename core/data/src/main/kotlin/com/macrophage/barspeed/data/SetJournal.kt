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
import java.io.File
import java.util.Locale

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
                "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d",
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

    /** A rep was counted at [timestampMs], by the lifter or by the guide. */
    fun appendRepMark(timestampMs: Long) {
        channel.trySend(Row(CUES, "$timestampMs,rep"))
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

    /** The set is over: stop accepting lines, drain, and release the handles. */
    suspend fun close() {
        channel.close()
        pump.join()
        directory.deleteRecursively()
    }

    /** The set is stored: the capture has done its job and can go. */
    fun discard() {
        directory.deleteRecursively()
    }

    companion object {
        const val IMU = "imu.csv"
        const val HRM = "hrm.csv"
        const val CUES = "cues.csv"

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

    private fun read(dir: File): OrphanedSet? {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return null
        val header =
            runCatching {
                json.decodeFromString(SetJournalHeader.serializer(), File(dir, HEADER_FILE).readText())
            }.getOrElse { fallbackHeader(dir) }
        return OrphanedSet(
            header = header,
            imuSamples = decode(dir, SetJournal.IMU) { ImuCsv.decode(it) },
            hrSamples = decode(dir, SetJournal.HRM) { HrCsv.decode(it) },
            cues = decode(dir, SetJournal.CUES) { CueCsv.decode(it) },
            repMarks = emptyList(),
            directory = dir,
        )
    }

    private fun <T> decode(dir: File, name: String, parse: (String) -> List<T>): List<T> {
        val file = File(dir, name)
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    private fun fallbackHeader(dir: File) = SetJournalHeader(
        exerciseId = "unknown",
        exerciseName = "Unknown",
        sessionId = null,
        sessionStartedAtMs = 0L,
        startedAtMs = dir.lastModified(),
        orderIdx = 0,
        imuConnected = false,
    )

    companion object {
        /** Bumped when the on-disk layout stops being readable by older code. */
        const val JOURNAL_VERSION = 1
        const val HEADER_FILE = "header.json"

        /** `root/s<sessionStart>/set<n>-<ms>` sits two levels down. */
        const val WALK_DEPTH = 2
    }
}
