package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** UX review 2026-11-06, reliability F7: the subject and one-line text a shared crash report carries. */
class CrashShareTextTest {
    private val report =
        """
        BARSPEED CRASH REPORT
        at: 2026-11-06T10:41:07.123Z
        version: 0.2.1 (62)
        android: 16 (API 36)
        device: Google Pixel 8
        database: 18
        screen: record
        thread: main
        heap: free 1 B, total 2 B, max 3 B
        --- STACK ---
        java.lang.IllegalStateException: boom
        ${'\t'}at com.macrophage.barspeed.Foo.bar(Foo.kt:10)
        --- LOG (none) ---
        """.trimIndent() + "\n"

    @Test
    fun `the subject names the version the crash was written by`() {
        assertEquals("BarSpeed crash 0.2.1", CrashShareText.subject(report, fallbackVersion = "9.9.9"))
    }

    @Test
    fun `a file with no version line takes the running version`() {
        assertEquals("BarSpeed crash 9.9.9", CrashShareText.subject("garbage\n", fallbackVersion = "9.9.9"))
    }

    @Test
    fun `the summary is one line with the version, time, screen, exception and phone`() {
        assertEquals(
            "BarSpeed 0.2.1 (62) crashed at 2026-11-06T10:41:07.123Z on record: " +
                "java.lang.IllegalStateException: boom. Android 16 (API 36), Google Pixel 8.",
            CrashShareText.summary(report),
        )
    }

    @Test
    fun `an older file without the phone lines still summarises`() {
        val old =
            report.lines()
                .filterNot { it.startsWith("android: ") || it.startsWith("device: ") }
                .joinToString("\n")
        assertEquals(
            "BarSpeed 0.2.1 (62) crashed at 2026-11-06T10:41:07.123Z on record: java.lang.IllegalStateException: boom.",
            CrashShareText.summary(old),
        )
    }

    @Test
    fun `an unreadable file still gives a line, never a blank`() {
        assertEquals("BarSpeed crashed.", CrashShareText.summary(""))
    }

    @Test
    fun `a long exception message is cut so the summary stays one short line`() {
        val long = report.replace("boom", "x".repeat(500))
        val summary = CrashShareText.summary(long)
        assertFalse(summary.contains("\n"))
        assertFalse(summary.contains("x".repeat(CrashShareText.MAX_EXCEPTION_CHARS + 1)))
    }
}
