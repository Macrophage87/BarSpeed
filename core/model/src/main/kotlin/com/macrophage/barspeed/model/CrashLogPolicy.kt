package com.macrophage.barspeed.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Which crash files a phone keeps, and what each one is called. Issue #272.
 *
 * A pure decision over a list of NAMES, deliberately not over `File`s: the
 * caller in :app hands this `listFiles()` names and acts on what comes back,
 * so the rule that decides what gets deleted is pinned by a test on the CI
 * path rather than being a loop inside an Android class nothing can run.
 *
 * NOTHING HERE READS A CRASH FILE, and that is load-bearing rather than
 * tidiness. #271 was the app dying at launch because a directory LISTING
 * decoded the files it was listing -- `SetJournalStore.orphans()` replayed a
 * 300 MB stream file through `BufferedReader.readLine` on every launch until
 * the heap was gone. The date, the ordering and the pruning decision all come
 * out of the file NAME here, so listing ten crash reports costs one directory
 * read whatever is in them.
 *
 * THE NAME IS THE CLOCK, so sorting by name descending is sorting newest
 * first. That holds only because [fileName] emits a fixed-width,
 * zero-padded, UTC timestamp: any variable-width or local-time format would
 * make lexical order disagree with chronological order somewhere, and this
 * object would be sorting by a string that merely looks like a date.
 */
object CrashLogPolicy {
    /** How many crash reports a phone keeps. #272 asks for ten. */
    const val KEEP = 10

    /** The directory, under the app's own `filesDir`. */
    const val DIR = "crashes"

    private const val PREFIX = "crash-"

    private const val EXTENSION = ".txt"

    /** Fixed width, zero-padded, UTC: the three properties the ordering rests on. */
    private val NAME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'-'HHmmss'-'SSS'Z'").withZone(ZoneOffset.UTC)

    /** How a parsed name reads back on a card: date, time, and the Z that says it is UTC. */
    private val LABEL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /**
     * e.g. 1789254897123 -> "crash-20260912-211457-123Z.txt".
     *
     * No colon, and that is not cosmetic: an ISO-8601 instant contains them,
     * they are legal on Android's filesystem, and they are illegal in a
     * filename on Windows -- so a crash file mailed off the phone would fail
     * to save on the machine it is being read on. Millisecond precision
     * because a crash storm is real: #271's phone produced eight crashes in
     * 28 minutes and two inside the same second is not excluded.
     */
    fun fileName(atMs: Long): String = PREFIX + NAME_FORMAT.format(Instant.ofEpochMilli(atMs)) + EXTENSION

    /**
     * The instant [fileName] encoded, or null when [name] is not one of ours.
     *
     * Null rather than a fallback: this is the single test for "did this app
     * write that file", and [toDelete] leans on it so a file the app did not
     * write can never be deleted by the pruner.
     */
    fun instantOf(name: String): Instant? {
        if (!name.startsWith(PREFIX) || !name.endsWith(EXTENSION)) return null
        val stamp = name.substring(PREFIX.length, name.length - EXTENSION.length)
        return try {
            LocalDateTime.parse(stamp, NAME_FORMAT).toInstant(ZoneOffset.UTC)
        } catch (parseFailure: DateTimeParseException) {
            null
        }
    }

    /** True exactly when [instantOf] can read [name]. */
    fun isCrashFile(name: String): Boolean = instantOf(name) != null

    /**
     * What a card shows for one crash file, without opening it: e.g.
     * "2026-09-12 21:14:57Z". Null when the name is not ours.
     *
     * Seconds, not milliseconds. The millisecond in the NAME exists to keep
     * two crashes in one second apart on disk; a lifter reading a card does
     * not need it, and it would make three cards of near-identical strings
     * harder to tell apart rather than easier.
     */
    fun labelFor(name: String): String? = instantOf(name)?.let { LABEL_FORMAT.format(it) }

    /**
     * Our crash files among [names], newest first, capped at [keep].
     *
     * Anything [isCrashFile] rejects is absent from the result: it is neither
     * kept nor deleted, only left alone.
     */
    fun toKeep(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter(::isCrashFile).sortedDescending().take(keep.coerceAtLeast(0))

    /**
     * Our crash files that [keep] pushes off the end, oldest first.
     *
     * THE ONLY LIST THE APP DELETES FROM, and the reason it is expressed as a
     * subtraction from [toKeep] rather than as its own sort: a pruner that
     * computed its own ordering could disagree with the list on screen, and
     * the file it disagreed about would be the one that went. A file with a
     * name this object does not recognise is never in here, whatever it is.
     */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> {
        val kept = toKeep(names, keep).toSet()
        return names.filter { isCrashFile(it) && it !in kept }.sorted()
    }
}
