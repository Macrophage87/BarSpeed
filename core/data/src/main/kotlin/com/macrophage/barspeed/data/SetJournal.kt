package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
 * [secondaryImuConnected] is that same fact for the second link, and it is
 * here for that same reading. A role is armed from pairing and labelling
 * alone -- `SensorCapturePolicy.roster` consults no `ConnectionState` -- so
 * [sensorRoles] cannot supply it, and an absent or empty `imu-b.csv` would
 * otherwise be indistinguishable from a second unit that was never connected.
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
    /**
     * The accelerometer roles this set was armed for, in order, or empty when
     * its streams carry no role (#156).
     *
     * Defaulted so that a header written by any earlier build still decodes --
     * `SetJournalStore`'s reader is `ignoreUnknownKeys`, which handles a key it
     * does not know but not a key it requires and cannot find.
     *
     * [SetJournalStore.JOURNAL_VERSION] is deliberately NOT bumped for this.
     * Its own contract is that it moves when the on-disk layout stops being
     * readable by older code, and the version gate refuses only a FUTURE
     * format; the armed stream keeps the filename `imu.csv`, so an older
     * build still recovers the header, the armed capture, heart rate, cues
     * and rep marks, and simply ignores one file it has never heard of.
     * Bumping would make that build return null for the whole directory and
     * DISCARD an orphaned set it could otherwise have offered back, which is
     * strictly worse than it missing a second stream.
     */
    val sensorRoles: List<SensorRole> = emptyList(),
    /**
     * Which role's capture is in `imu.csv`; null when the streams carry no
     * role.
     *
     * The role the set ARMED for analysis, which since #207 is not always the
     * role its figures would come from: this header is written and closed
     * before the first sample line, so it cannot know which units streamed.
     * `imu.csv` is genuinely the armed unit's capture however empty that turns
     * out to be, and that is the only thing this field says.
     *
     * IT WAS CALLED `analysedRole` AND THE NAME WAS WRONG (#211). Not the
     * value and not the code that read it -- the word, in the one artifact a
     * person opens before anything else does: a lifter unzipping a recovered
     * set whose armed unit went silent read `"analysedRole": "a"` beside an
     * `imu.csv` holding a header and no rows, while `imu-b.csv` held the whole
     * capture every figure would have come from. The Kotlin name is the honest
     * one now.
     *
     * THE ON-DISK KEY IS DELIBERATELY UNMOVED. [SerialName] keeps it
     * `analysedRole`, so every directory already sitting on the phone decodes
     * unchanged and a build that predates this still derives the second
     * stream's role from it -- the same compatibility reasoning
     * [sensorRoles] gives for not bumping [SetJournalStore.JOURNAL_VERSION].
     * What a person READS is corrected where the correction can be made
     * truthfully: [SetJournalStore.zip] publishes `armedRole` in its place,
     * beside an `analysedRole` derived from the streams that are actually in
     * the directory. Rewriting the recorded file was refused outright -- the
     * capture is not edited after the fact.
     */
    @SerialName(SetJournalStore.RECORDED_ROLE_KEY)
    val armedRole: SensorRole? = null,
    /**
     * Whether the SECOND link was connected at the moment the set began, as
     * the caller observed it (#156).
     *
     * False on every one-sensor set and on every header written before this
     * field existed. Defaulted for [sensorRoles]'s reason and one sharper
     * one: `SetJournalStore`'s reader decodes inside
     * `runCatching { }.getOrNull() ?: return null`, so a field an older
     * header cannot supply does not surface as an error -- the whole
     * directory is dropped and the capture is never offered back.
     */
    val secondaryImuConnected: Boolean = false,
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
    /**
     * The second accelerometer's capture, empty on every set that had one
     * sensor and on every capture written before the app could have two.
     *
     * [imuSamples] keeps its meaning -- the ARMED unit's stream, whatever it
     * turned out to hold -- so nothing that already reads this type sees a
     * different number. Defaulted last for the same reason: a positional
     * constructor call in a test or a screen keeps compiling and keeps meaning
     * what it meant.
     */
    val secondaryImuSamples: List<ImuSample> = emptyList(),
    /**
     * Which role's capture the figures would be computed from, decided from
     * the rows that are actually in this directory (#211).
     *
     * NOT [SetJournalHeader.armedRole], and that difference is the whole of
     * #211. The header is written and closed before the first sample line, so
     * it can only name the unit the set armed; on a capture whose armed unit
     * went silent, the rows are in the other file. This is derived on read by
     * [SensorCapturePolicy.analysedFrom] -- the same single writer the
     * recording path decides with -- so a recovered capture and a stored set
     * cannot disagree about which stream the analysis belongs to.
     *
     * NOTHING IS ANALYSED HERE. A recovered orphan is offered back as a zip or
     * discarded; this is a statement about the capture, published so that the
     * person holding the zip is not left to work it out from two file sizes.
     * It is also why deriving it costs nothing that matters -- the rows have
     * already been decoded by the time this is answered.
     */
    val analysedRole: SensorRole? = null,
    /**
     * True when [analysedRole] is not the role the set armed, for
     * `RecordedSensors.analysedFellBack`'s reason and by its rule.
     *
     * The comparison is not left to the reader. "The armed unit's capture" and
     * "the only capture there was" are different statements about what is in
     * the zip, and on a directory holding two non-empty streams they are not
     * separable by looking at [analysedRole] alone.
     */
    val analysedFellBack: Boolean = false,
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
    private var secondaryImuIndex = 0L
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
        channel.trySend(Row(IMU, imuRow(sample, imuIndex++)))
    }

    /**
     * A sample from the accelerometer that is not the ARMED one, into its own
     * file (#156).
     *
     * `imu-a.csv` or `imu-b.csv` beside `imu.csv`, never instead of it. The
     * armed stream keeps the name every earlier build knows, which is what
     * lets [SetJournalStore.JOURNAL_VERSION] stay where it is -- see
     * [SetJournalHeader.sensorRoles].
     *
     * The role is in the FILENAME rather than in a column of the shared
     * `imu.csv`, because [ImuCsv] is one format shared with the stored stream
     * and with every `field-*.csv` regression fixture; a role column would
     * either break that format or be a second format wearing its name. Two
     * files also mean a half-written line in one costs nothing in the other.
     *
     * Its own row counter, not a continuation of the armed stream's.
     * `ImuCsv`'s own contract for `sample_idx` is "THIS loop's own index,
     * 0..n-1 by construction", and the decoder that reads this file back reads
     * one file: a shared counter would publish a stream whose indices start at
     * whatever the other stream had reached. The two captures are aligned by
     * their arrival timestamps -- one host clock stamps both, since both
     * clients read `System.currentTimeMillis` per notification -- and never by
     * row number.
     */
    fun appendSecondaryImu(sample: ImuSample, role: SensorRole) {
        channel.trySend(Row(secondaryImuFile(role), imuRow(sample, secondaryImuIndex++)))
    }

    private fun imuRow(sample: ImuSample, index: Long): String = String.format(
        Locale.US,
        "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d",
        sample.timestampMs, sample.axG, sample.ayG, sample.azG,
        sample.wxDps, sample.wyDps, sample.wzDps,
        sample.rollDeg, sample.pitchDeg, sample.yawDeg, index,
    )

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
        // Matched by prefix rather than by equality because the secondary
        // capture's filename carries its role -- imu-a.csv, imu-b.csv -- and
        // there is no reason to enumerate two of them here. The prefix is safe
        // in a way `kind` prefixes are not: this is a fixed set of four
        // filenames chosen in this file, not a free-form column, and `hrm.csv`,
        // `cues.csv` and `reps.csv` cannot begin with `imu`.
        else -> if (kind.startsWith(IMU_PREFIX)) ImuCsv.HEADER else CueCsv.HEADER
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

        /** What [IMU] and every [secondaryImuFile] begin with, and nothing else here does. */
        const val IMU_PREFIX = "imu"

        /**
         * Where the capture from the accelerometer that is not the ARMED one
         * goes.
         *
         * Derived from the role rather than fixed, so the file says which unit
         * it came from without the header having to be read first -- the same
         * reasoning [RawStreamEntity.KIND_REST_BEFORE_HRM] gives for putting a
         * direction in a name. A recovered capture zipped and mailed to the
         * lifter is read by a person before it is read by anything else.
         */
        fun secondaryImuFile(role: SensorRole): String = "$IMU_PREFIX-${role.name.lowercase()}.csv"

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
     * The STREAMS are copied in exactly as they lie, uncompressed and
     * unconverted. They are already the canonical CSV that
     * `core/dsp/src/test/resources/field-*.csv` fixtures are written in, so a
     * capture that exposes a defect converts into a regression fixture with no
     * transformation at all -- which is this repository's discharge ritual for
     * a hardware-found bug.
     *
     * `header.json` is the exception and is published rather than copied, by
     * [publishedHeader] (#211). It is not a fixture and it is the one file in
     * the directory a person reads first, and the recorded key naming a role
     * says which unit was ARMED while its name said which was analysed.
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
                    val bytes =
                        if (file.name == HEADER_FILE) publishedHeader(orphan, file) else file.readBytes()
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
        val imuSamples = decode(dir, SetJournal.IMU) { ImuCsv.decode(it) }
        // The second stream's role comes from the header's own declaration
        // rather than from whichever imu-*.csv happens to be on disk. The
        // header is written and closed before the first sample line of any
        // stream, so it is the one thing in the directory that cannot be a
        // half-truth; a file scan would pick up a role this set was never
        // armed for -- a leftover from a build, or a directory the lifter
        // copied -- and present it as this capture's second sensor.
        val secondaryRole = header.sensorRoles.firstOrNull { it != header.armedRole }
        val secondarySamples =
            secondaryRole?.let { role -> decode(dir, SetJournal.secondaryImuFile(role)) { ImuCsv.decode(it) } }
                .orEmpty()
        return OrphanedSet(
            header = header,
            imuSamples = imuSamples,
            hrSamples = decode(dir, SetJournal.HRM) { HrCsv.decode(it) },
            cues = decode(dir, SetJournal.CUES) { CueCsv.decode(it) },
            repMarks = decode(dir, SetJournal.REPS) { line -> RepMarkCsv.decodeLine(line) },
            directory = dir,
            secondaryImuSamples = secondarySamples,
            analysedRole = header.armedRole,
            analysedFellBack = false,
        )
    }

    /**
     * The header as a person should read it, which is not byte-for-byte what
     * was recorded (#211).
     *
     * ONE KEY IS RENAMED AND TWO ARE ADDED, and nothing else is touched. The
     * recorded `analysedRole` is published as `armedRole`, which is what it
     * has always held -- the header is closed before the first sample line and
     * cannot know which units streamed. `analysedRole` is then published with
     * the answer derived from the rows that are in the directory, and
     * `analysedFellBack` beside it when the two differ. The lifter who opens a
     * recovered zip is trying to salvage a set; leaving them to infer which of
     * two CSVs the figures would have come from, from a key that names the
     * other one, is the worst moment to be told something untrue.
     *
     * THE FILE ON DISK IS NOT REWRITTEN. This is a publication step, on the
     * one path out of the phone, and the capture itself is never edited after
     * the fact. The CSV streams are copied exactly as they lie for that same
     * reason -- they are already the canonical format a `field-*.csv`
     * regression fixture is written in, and this file is not one.
     *
     * IT TRANSFORMS THE PARSED TEXT RATHER THAN RE-ENCODING THE DECODED
     * HEADER, so a key written by a build this one has never heard of survives
     * into the zip instead of being silently dropped by a decoder configured
     * to ignore it. Order is preserved for the same reason. A header that will
     * not parse at all is copied through untouched: some of the capture is
     * worth more than none of it.
     */
    private fun publishedHeader(orphan: OrphanedSet, file: File): ByteArray = runCatching {
        val recorded = json.parseToJsonElement(file.readText()).jsonObject
        val published =
            buildJsonObject {
                recorded.forEach { (key, value) ->
                    put(if (key == RECORDED_ROLE_KEY) ARMED_ROLE_KEY else key, value)
                }
                orphan.analysedRole?.let { put(RECORDED_ROLE_KEY, roleElement(it)) }
                if (orphan.analysedFellBack) put(FELL_BACK_KEY, JsonPrimitive(true))
            }
        json.encodeToString(JsonObject.serializer(), published).toByteArray(Charsets.UTF_8)
    }.getOrElse { file.readBytes() }

    /**
     * A role in the spelling the recorded header already uses.
     *
     * [SensorCapturePolicy.wireOf] is the EXPORT's vocabulary -- lowercase --
     * and this document is not that document. Writing the published key in a
     * spelling the neighbouring `armedRole` does not use would tell a reader
     * the two keys are different kinds of thing.
     *
     * [SensorRole] carries no `@Serializable`, so kotlinx encodes it by entry
     * name and this reproduces that rather than restating a choice. The pin
     * that holds the two together is a published document read back and
     * compared against the recorded one, not this line.
     */
    private fun roleElement(role: SensorRole) = JsonPrimitive(role.name)

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

        /**
         * The recorded key holding [SetJournalHeader.armedRole], and the
         * published key that says so (#211).
         *
         * [RECORDED_ROLE_KEY] is what is on disk and it does not move: every
         * directory already on the phone carries it, and a build older than
         * this one derives the second stream's role from it. [ARMED_ROLE_KEY]
         * is what [zip] publishes in its place, and [RECORDED_ROLE_KEY] is
         * then reused in the published document for the thing it names --
         * which stream the figures would come from -- with [FELL_BACK_KEY]
         * beside it when that is not the armed unit.
         */
        const val RECORDED_ROLE_KEY = "analysedRole"
        const val ARMED_ROLE_KEY = "armedRole"
        const val FELL_BACK_KEY = "analysedFellBack"

        /** `root/s<sessionStart>/set<n>-<ms>` sits two levels down. */
        const val WALK_DEPTH = 2
    }
}
