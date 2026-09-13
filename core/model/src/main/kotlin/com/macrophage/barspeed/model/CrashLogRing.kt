package com.macrophage.barspeed.model

/**
 * The last few lines the app itself logged, kept in memory so a crash report
 * can carry them. Issue #272.
 *
 * BOUNDED BY CONSTRUCTION, IN BOTH DIMENSIONS, and that is the #271 lesson
 * rather than a preference. #271 was an `OutOfMemoryError` at launch: the app
 * died because a diagnostic path read as much as it was given. A crash
 * handler that collected an unbounded log would be that defect again, at the
 * exact moment there is least heap to spare -- so the buffer holds at most
 * `capacity` lines, each line keeps at most `maxLineChars` characters of
 * content, and both caps are applied at [add] time, when the process is
 * healthy, never at crash time.
 *
 * The whole allocation is made in the constructor for the same reason: the
 * crash path copies out of an array that already exists.
 *
 * EMBEDDED NEWLINES ARE FLATTENED TO SPACES. A crash file is line-oriented
 * and `CrashRecord.render` states the line count in its own header, so a log
 * line containing `\n` would make the file disagree with itself about how
 * many lines it has.
 *
 * `synchronized` on both operations, because the app logs from whatever
 * thread it is on and the crash handler reads from the thread that died.
 * Nothing pins that: no test in this repository can schedule two threads
 * against each other and assert an outcome, so what is claimed here is the
 * lock, not an absence of interleavings.
 */
class CrashLogRing(
    capacity: Int = CAPACITY,
    maxLineChars: Int = MAX_LINE_CHARS,
) {
    private val lineCap = maxLineChars.coerceAtLeast(1)

    private val held = ArrayDeque<String>(capacity.coerceAtLeast(1))

    private val cap = capacity.coerceAtLeast(1)

    /**
     * Record one line, evicting the oldest once `capacity` is reached.
     *
     * Never throws and never grows: an over-long line is truncated and SAYS
     * it was, with the number of characters dropped, so a truncated line
     * cannot be misread as the whole of what was logged.
     */
    fun add(line: String) {
        val flat = line.replace('\r', ' ').replace('\n', ' ')
        val bounded =
            if (flat.length > lineCap) flat.take(lineCap) + " [+${flat.length - lineCap} chars]" else flat
        synchronized(held) {
            while (held.size >= cap) held.removeFirst()
            held.addLast(bounded)
        }
    }

    /** The lines held, oldest first -- the order `CrashRecord.render` writes them in. */
    fun lines(): List<String> = synchronized(held) { held.toList() }

    companion object {
        /**
         * #272 asks for "the last N app log lines" and fifty is that N.
         *
         * Fifty lines of up to [MAX_LINE_CHARS] characters is a bound of
         * roughly 30 KB of `char`, which is the whole point of stating both
         * caps together: either one alone bounds nothing.
         */
        const val CAPACITY = 50

        /** Characters of CONTENT kept per line; the stored line is this plus a fixed truncation marker. */
        const val MAX_LINE_CHARS = 300
    }
}
