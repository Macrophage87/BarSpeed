package com.macrophage.barspeed.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Everything a crash report carries, and the exact text it is written as.
 *
 * Issue #272. The owner is the only tester, and until this exists the only
 * route from "it crashed" to a stack trace is a cable and `adb`: #271 was
 * diagnosed from Android's own dropbox over wireless adb, which took a
 * pairing, eight dropbox records and an R8 mapping rebuild before the first
 * frame could be named. A file the app writes itself is shareable from the
 * phone with no cable at all.
 *
 * A data class plus a pure renderer, in :core:model, so the LAYOUT is pinned
 * by a test that runs on every push. The handler in :app that fills this in
 * and writes the file is compile- and lint-gated only -- no test in this
 * repository can install an uncaught-exception handler or observe a process
 * dying.
 *
 * [stack] IS ALREADY TEXT, not a `Throwable`. Rendering a throwable is
 * [stackText]'s job and is pinned separately, which keeps this class free of
 * anything the caller has to get right at crash time: by the time a record
 * exists, the only remaining work is string concatenation.
 *
 * NOT `Throwable.stackTraceToString()`, and that is why [stackText] exists at
 * all. Kotlin's own helper prints through a `PrintWriter`, which emits the
 * PLATFORM line separator -- `\r\n` on the Windows JVM these tests run on,
 * `\n` on the phone -- so a contract test pinning an exact layout would pass
 * on one and fail on the other. Everything here joins on `\n`.
 *
 * ## Why the frame format is not free
 *
 * The release APK is minified (`isMinifyEnabled = true`), so every frame the
 * phone can produce names an obfuscated class. #274 publishes the R8
 * `mapping.txt` beside each release APK precisely so `retrace` can turn those
 * back into source names -- and `retrace` finds frames by matching lines of
 * the shape `at <class>.<method>(<file>:<line>)`. [stackText] emits exactly
 * that shape, one tab then `at `, so a crash file shared from the phone is
 * readable by `retrace` with no editing. The header lines below do not match
 * that shape. Whether `retrace` passes a non-matching line through unchanged
 * is not verified here and nothing in this repository can verify it.
 */
data class CrashRecord(
    /** When the crash was caught, as epoch milliseconds UTC. */
    val atMs: Long,
    /** `BuildConfig.VERSION_NAME`, e.g. "0.1.52" -- the key to the right R8 mapping. */
    val versionName: String,
    /** `BuildConfig.VERSION_CODE`, e.g. 53. */
    val versionCode: Int,
    /** `DATABASE_VERSION` as the running process has it, so a migration crash dates itself. */
    val databaseVersion: Int,
    /**
     * The navigation route being drawn, or null when nothing had recorded one
     * yet -- a crash during `Application.onCreate` is the case that produces
     * null, and it is a real case: the handler is installed BEFORE the
     * dependency container, so a throw inside `AppDatabase.build` is recorded.
     *
     * Null is rendered as its own words, never as a route name and never as an
     * empty value. A crash file that said `screen: home` because nothing knew
     * would be a claim stronger than its evidence.
     */
    val screen: String?,
    /** `Thread.currentThread().name` of the thread that died. */
    val threadName: String,
    /** `Runtime.freeMemory()` at the moment the record was built. */
    val freeMemoryBytes: Long,
    /** `Runtime.totalMemory()` -- the heap COMMITTED so far, not the ceiling. */
    val totalMemoryBytes: Long,
    /**
     * `Runtime.maxMemory()` -- the growth limit, the number an
     * `OutOfMemoryError` is actually measured against.
     *
     * NOT IN THE ISSUE'S FIELD LIST, and added deliberately rather than
     * silently: #272 asks for "free memory", and #271 -- the crash this
     * feature exists for -- was eight `OutOfMemoryError`s reported by Android
     * as "<1% of heap free after GC" against a 256 MB GROWTH LIMIT. Free of
     * total cannot express that: a heap that has committed only 40 MB of a
     * 256 MB limit reads as 2 MB free of 40 MB, which looks identical to a
     * process about to die. Shipping a crash file that cannot answer the
     * question the motivating crash needed is the near-neighbour class this
     * repository keeps re-learning, so the third number is here.
     */
    val maxMemoryBytes: Long,
    /**
     * The app's own last log lines, oldest first, as [CrashLogRing] bounded
     * them. Empty is normal and is rendered as such.
     */
    val logLines: List<String>,
    /** The throwable already rendered by [stackText]. */
    val stack: String,
) {
    /**
     * The whole crash file, exactly.
     *
     * Fixed layout, pinned by `CrashRecordRenderTest`:
     *
     * ```
     * BARSPEED CRASH REPORT
     * at: 2026-09-12T21:14:57.123Z
     * version: 0.1.52 (53)
     * database: 18
     * screen: record
     * thread: main
     * heap: free 1234567 B, total 201326592 B, max 268435456 B
     * --- STACK ---
     * java.lang.IllegalStateException: boom
     * [one tab]at com.macrophage.barspeed.Foo.bar(Foo.kt:10)
     * --- LOG (1 line, oldest first) ---
     * something the app logged
     * ```
     *
     * Header lines first and in that order, then the stack VERBATIM as
     * [stackText] produced it, then the log. The stack comes before the log
     * because the stack is what the reader wants first, and a crash file is
     * read in a phone mail client as often as in an editor.
     *
     * Byte counts are raw, with no unit prefixes. `ByteSize.format` is what a
     * LIFTER reads on a card; this file is read by whoever is diagnosing, and
     * "12.3 MB" has thrown away the digits that distinguish a heap 1% free
     * from one 8% free.
     *
     * Ends with a trailing newline, so the file `cat`s cleanly and a later
     * appender could not join its first line onto the log's last.
     */
    fun render(): String {
        val logHeading =
            when (logLines.size) {
                0 -> "--- LOG (none) ---"
                1 -> "--- LOG (1 line, oldest first) ---"
                else -> "--- LOG (${logLines.size} lines, oldest first) ---"
            }
        val header =
            listOf(
                "BARSPEED CRASH REPORT",
                "at: ${formatInstant(atMs)}",
                "version: $versionName ($versionCode)",
                "database: $databaseVersion",
                "screen: ${screen ?: NOT_RECORDED}",
                "thread: $threadName",
                "heap: free $freeMemoryBytes B, total $totalMemoryBytes B, max $maxMemoryBytes B",
                STACK_HEADING,
                stack.ifBlank { NOT_RECORDED },
                logHeading,
            )
        return (header + logLines).joinToString(separator = "\n", postfix = "\n")
    }

    companion object {
        /** Millisecond-precision, fixed width, always UTC -- so the layout cannot vary with the clock. */
        private val INSTANT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Frames kept per throwable in the chain. */
        const val MAX_FRAMES = 80

        /** Links of the `cause` chain rendered, the top-level throwable included. */
        const val MAX_CAUSES = 8

        /** What a field the process could not supply reads as. Never a blank, never a plausible value. */
        private const val NOT_RECORDED = "(not recorded)"

        /** The one heading between the header block and the stack. */
        private const val STACK_HEADING = "--- STACK ---"

        /** `2026-09-12T21:14:57.123Z`, the same instant [CrashLogPolicy.fileName] names the file after. */
        fun formatInstant(atMs: Long): String = INSTANT_FORMAT.format(Instant.ofEpochMilli(atMs))

        /**
         * A throwable and its causes as text, `\n`-joined, in the frame shape
         * `retrace` reads. See this class's own KDoc for why this is not
         * `Throwable.stackTraceToString()`.
         *
         * Bounded in both directions, because this runs while the process is
         * dying and #271's crash was the heap being exhausted: at most
         * [maxFrames] frames per throwable and at most [maxCauses] links of
         * the chain. What is dropped is SAID, and not in the JVM's own
         * `... N more` words -- that phrase means "N frames identical to the
         * enclosing trace", which is a different fact from "N frames this
         * renderer refused to print".
         *
         * Suppressed exceptions are not rendered. Cyclic `cause` chains
         * terminate: a throwable already seen ends the walk rather than
         * looping, since `initCause` forbids self-reference but two throwables
         * naming each other is reachable.
         */
        fun stackText(throwable: Throwable, maxFrames: Int = MAX_FRAMES, maxCauses: Int = MAX_CAUSES): String {
            val frameCap = maxFrames.coerceAtLeast(0)
            val causeCap = maxCauses.coerceAtLeast(1)
            val out = mutableListOf<String>()
            val seen = mutableListOf<Throwable>()
            var current: Throwable? = throwable
            var links = 0
            while (current != null) {
                if (seen.any { it === current }) {
                    out += "... cause chain repeats a throwable already shown"
                    break
                }
                seen += current
                out += if (links == 0) current.toString() else "Caused by: $current"
                val frames = current.stackTrace ?: emptyArray()
                frames.take(frameCap).forEach { out += "\tat $it" }
                if (frames.size > frameCap) {
                    out += "\t... ${frames.size - frameCap} more frames dropped (cap $frameCap)"
                }
                links++
                val next = current.cause
                if (next != null && links >= causeCap) {
                    out += "... cause chain truncated after $causeCap of its links"
                    break
                }
                current = next
            }
            return out.joinToString("\n")
        }
    }
}
