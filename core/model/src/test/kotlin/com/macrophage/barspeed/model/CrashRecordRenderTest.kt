package com.macrophage.barspeed.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exact text of a crash file, and the exact text of a rendered throwable.
 * Issue #272.
 *
 * A CONTRACT TEST, not a smoke test, and the distinction is the whole reason
 * this file exists. The crash report is read by whoever is diagnosing a field
 * crash, months later, possibly through `retrace` and #274's published R8
 * mapping -- so the thing that has to hold is the LAYOUT, byte for byte, not
 * "the version number appears somewhere". The first assertion below is a full
 * string equality for that reason.
 *
 * Every expected value is written out longhand rather than computed from the
 * same expression the implementation uses. A test that formats the instant by
 * calling the formatter under test would pass whatever that formatter did.
 */
class CrashRecordRenderTest {
    private fun frame(cls: String, method: String, file: String, line: Int) = StackTraceElement(cls, method, file, line)

    private fun thrown(message: String?, frames: List<StackTraceElement>, cause: Throwable? = null): Throwable =
        IllegalStateException(message, cause).also { it.stackTrace = frames.toTypedArray() }

    private val fooFrame = frame("com.macrophage.barspeed.Foo", "bar", "Foo.kt", 10)

    private fun record(
        screen: String? = "record",
        logLines: List<String> = listOf("first line", "second line"),
        stack: String = "java.lang.IllegalStateException: boom\n\tat com.macrophage.barspeed.Foo.bar(Foo.kt:10)",
    ) = CrashRecord(
        atMs = Instant.parse("2026-09-12T21:14:57.123Z").toEpochMilli(),
        versionName = "0.1.52",
        versionCode = 53,
        databaseVersion = 18,
        screen = screen,
        threadName = "main",
        freeMemoryBytes = 1_234_567L,
        totalMemoryBytes = 201_326_592L,
        maxMemoryBytes = 268_435_456L,
        logLines = logLines,
        stack = stack,
    )

    @Test
    fun `the whole crash file is exactly this, header order included`() {
        val expected =
            """
            BARSPEED CRASH REPORT
            at: 2026-09-12T21:14:57.123Z
            version: 0.1.52 (53)
            database: 18
            screen: record
            thread: main
            heap: free 1234567 B, total 201326592 B, max 268435456 B
            --- STACK ---
            java.lang.IllegalStateException: boom
            ${'\t'}at com.macrophage.barspeed.Foo.bar(Foo.kt:10)
            --- LOG (2 lines, oldest first) ---
            first line
            second line
            """.trimIndent() + "\n"
        assertEquals(expected, record().render())
    }

    @Test
    fun `the file ends with exactly one newline`() {
        val rendered = record().render()
        assertTrue(rendered.endsWith("\n"), "no trailing newline")
        assertFalse(rendered.endsWith("\n\n"), "more than one trailing newline")
    }

    @Test
    fun `no carriage return anywhere, whatever platform rendered it`() {
        assertFalse(record().render().contains("\r"))
    }

    @Test
    fun `an unknown screen is rendered as words, never as a route and never as blank`() {
        val line = record(screen = null).render().lines().first { it.startsWith("screen: ") }
        assertEquals("screen: (not recorded)", line)
    }

    @Test
    fun `an empty log is a stated absence, not an empty section`() {
        val rendered = record(logLines = emptyList()).render()
        assertTrue(rendered.contains("--- LOG (none) ---\n"), rendered)
        assertEquals("--- LOG (none) ---", rendered.trimEnd('\n').lines().last())
    }

    @Test
    fun `one log line is singular`() {
        assertTrue(record(logLines = listOf("only")).render().contains("--- LOG (1 line, oldest first) ---\n"))
    }

    @Test
    fun `a blank stack is a stated absence, not a blank line`() {
        val rendered = record(stack = "").render()
        assertTrue(rendered.contains("--- STACK ---\n(not recorded)\n"), rendered)
    }

    @Test
    fun `the stack is written verbatim, newlines and all`() {
        val stack = "a.B: one\n\tat x.Y.z(Y.kt:1)\nCaused by: c.D: two\n\tat p.Q.r(Q.kt:2)"
        assertTrue(record(stack = stack).render().contains("--- STACK ---\n$stack\n--- LOG"))
    }

    @Test
    fun `byte counts are raw digits, so a nearly-full heap is still readable`() {
        val heap = record().render().lines().first { it.startsWith("heap: ") }
        assertEquals("heap: free 1234567 B, total 201326592 B, max 268435456 B", heap)
        assertFalse(heap.contains("MB"), "a formatted unit has thrown away the digits that matter")
    }

    @Test
    fun `formatInstant is fixed width, millisecond precision, and always UTC`() {
        assertEquals(
            "2026-01-02T03:04:05.006Z",
            CrashRecord.formatInstant(Instant.parse("2026-01-02T03:04:05.006Z").toEpochMilli()),
        )
        assertEquals(
            "2026-12-31T23:59:59.000Z",
            CrashRecord.formatInstant(Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()),
        )
    }

    @Test
    fun `stackText writes the throwable then one tab-at frame per line`() {
        val text = CrashRecord.stackText(thrown("boom", listOf(fooFrame)))
        assertEquals(
            "java.lang.IllegalStateException: boom\n\tat com.macrophage.barspeed.Foo.bar(Foo.kt:10)",
            text,
        )
    }

    @Test
    fun `stackText leaves no trailing newline for render to double`() {
        assertFalse(CrashRecord.stackText(thrown("boom", listOf(fooFrame))).endsWith("\n"))
    }

    @Test
    fun `a throwable with no message renders as its class name alone`() {
        assertEquals(
            "java.lang.IllegalStateException\n\tat com.macrophage.barspeed.Foo.bar(Foo.kt:10)",
            CrashRecord.stackText(thrown(null, listOf(fooFrame))),
        )
    }

    @Test
    fun `a cause is chained with Caused by and its own frames`() {
        val cause = thrown("inner", listOf(frame("p.Q", "r", "Q.kt", 2)))
        val text = CrashRecord.stackText(thrown("outer", listOf(fooFrame), cause))
        assertEquals(
            "java.lang.IllegalStateException: outer\n" +
                "\tat com.macrophage.barspeed.Foo.bar(Foo.kt:10)\n" +
                "Caused by: java.lang.IllegalStateException: inner\n" +
                "\tat p.Q.r(Q.kt:2)",
            text,
        )
    }

    @Test
    fun `frames past the cap are dropped and the file says how many`() {
        val frames = (1..5).map { frame("p.Q", "r$it", "Q.kt", it) }
        val text = CrashRecord.stackText(thrown("boom", frames), maxFrames = 2)
        assertEquals(
            "java.lang.IllegalStateException: boom\n" +
                "\tat p.Q.r1(Q.kt:1)\n" +
                "\tat p.Q.r2(Q.kt:2)\n" +
                "\t... 3 more frames dropped (cap 2)",
            text,
        )
    }

    @Test
    fun `the dropped-frame line does not borrow the JVM's own N more wording`() {
        val frames = (1..5).map { frame("p.Q", "r$it", "Q.kt", it) }
        val text = CrashRecord.stackText(thrown("boom", frames), maxFrames = 2)
        assertFalse(
            Regex("""\.\.\. \d+ more$""", RegexOption.MULTILINE).containsMatchIn(text),
            "\"... N more\" means frames identical to the enclosing trace, which is a different fact",
        )
    }

    @Test
    fun `a cause chain longer than the cap stops and says so`() {
        val deepest = thrown("three", listOf(frame("p.Q", "r3", "Q.kt", 3)))
        val middle = thrown("two", listOf(frame("p.Q", "r2", "Q.kt", 2)), deepest)
        val text = CrashRecord.stackText(thrown("one", listOf(fooFrame), middle), maxCauses = 2)
        assertEquals(
            "java.lang.IllegalStateException: one\n" +
                "\tat com.macrophage.barspeed.Foo.bar(Foo.kt:10)\n" +
                "Caused by: java.lang.IllegalStateException: two\n" +
                "\tat p.Q.r2(Q.kt:2)\n" +
                "... cause chain truncated after 2 of its links",
            text,
        )
    }

    @Test
    fun `a cyclic cause chain terminates instead of looping`() {
        val first = thrown("first", listOf(frame("p.Q", "r1", "Q.kt", 1)))
        val second = thrown("second", listOf(frame("p.Q", "r2", "Q.kt", 2)), first)
        first.initCause(second)
        val text = CrashRecord.stackText(first)
        assertEquals(
            "java.lang.IllegalStateException: first\n" +
                "\tat p.Q.r1(Q.kt:1)\n" +
                "Caused by: java.lang.IllegalStateException: second\n" +
                "\tat p.Q.r2(Q.kt:2)\n" +
                "... cause chain repeats a throwable already shown",
            text,
        )
    }

    @Test
    fun `a throwable with no frames at all still names itself`() {
        assertEquals("java.lang.IllegalStateException: boom", CrashRecord.stackText(thrown("boom", emptyList())))
    }

    @Test
    fun `the frame and cause caps are the ones the handler runs with`() {
        assertEquals(80, CrashRecord.MAX_FRAMES)
        assertEquals(8, CrashRecord.MAX_CAUSES)
    }
}
