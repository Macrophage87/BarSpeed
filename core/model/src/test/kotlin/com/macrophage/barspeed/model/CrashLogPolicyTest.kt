package com.macrophage.barspeed.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a crash file is called, and which ones a phone keeps. Issue #272.
 *
 * The pruning rule is the one piece of this feature that DELETES something,
 * so the pins that matter most are the negative ones: a file this app did not
 * write is never in the delete list, and a directory holding fewer than the
 * cap loses nothing. Everything else here exists to hold up the claim the
 * ordering rests on -- that lexical order over these names is chronological
 * order -- which is true only for as long as the name stays fixed-width, UTC
 * and zero-padded, and is therefore asserted rather than assumed.
 */
class CrashLogPolicyTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `a name is the prefix, the UTC instant to the millisecond, and txt`() {
        assertEquals("crash-20260912-211457-123Z.txt", CrashLogPolicy.fileName(ms("2026-09-12T21:14:57.123Z")))
    }

    @Test
    fun `a name carries no colon, which is illegal in a filename on the machine it will be read on`() {
        assertFalse(CrashLogPolicy.fileName(ms("2026-09-12T21:14:57.123Z")).contains(":"))
    }

    @Test
    fun `every field is zero-padded, so every name is the same width`() {
        assertEquals("crash-20260102-030405-006Z.txt", CrashLogPolicy.fileName(ms("2026-01-02T03:04:05.006Z")))
        assertEquals(
            CrashLogPolicy.fileName(ms("2026-01-02T03:04:05.006Z")).length,
            CrashLogPolicy.fileName(ms("2026-12-31T23:59:59.999Z")).length,
        )
    }

    @Test
    fun `the instant is UTC, not the machine's zone`() {
        assertEquals("crash-20260101-000000-000Z.txt", CrashLogPolicy.fileName(ms("2026-01-01T00:00:00Z")))
    }

    @Test
    fun `sorting names lexically is sorting them by time`() {
        val early = CrashLogPolicy.fileName(ms("2026-09-12T21:14:57.123Z"))
        val oneMillisecondLater = CrashLogPolicy.fileName(ms("2026-09-12T21:14:57.124Z"))
        val nextYear = CrashLogPolicy.fileName(ms("2027-01-01T00:00:00.000Z"))
        val shuffled = listOf(nextYear, early, oneMillisecondLater)
        assertEquals(listOf(early, oneMillisecondLater, nextYear), shuffled.sorted())
    }

    @Test
    fun `instantOf reads back exactly what fileName wrote`() {
        val at = ms("2026-09-12T21:14:57.123Z")
        assertEquals(Instant.ofEpochMilli(at), CrashLogPolicy.instantOf(CrashLogPolicy.fileName(at)))
    }

    @Test
    fun `a name this app did not write is not ours`() {
        for (name in listOf(
            "session.json",
            "notes.txt",
            "crash.txt",
            "crash-.txt",
            "crash-20260912.txt",
            "crash-20260912-211457-123Z.log",
            "crash-20260912-211457-123Z.txt.bak",
            "20260912-211457-123Z.txt",
            "crash-20261312-211457-123Z.txt",
            "crash-not-a-time-here-Z.txt",
            "",
        )) {
            assertNull(CrashLogPolicy.instantOf(name), name)
            assertFalse(CrashLogPolicy.isCrashFile(name), name)
        }
    }

    @Test
    fun `isCrashFile agrees with instantOf on one we did write`() {
        assertTrue(CrashLogPolicy.isCrashFile(CrashLogPolicy.fileName(ms("2026-09-12T21:14:57.123Z"))))
    }

    @Test
    fun `a card label reads as a date and drops the millisecond`() {
        assertEquals("2026-09-12 21:14:57Z", CrashLogPolicy.labelFor("crash-20260912-211457-123Z.txt"))
    }

    @Test
    fun `a foreign name has no label rather than a made-up one`() {
        assertNull(CrashLogPolicy.labelFor("session.json"))
    }

    @Test
    fun `toKeep puts the most recent crash at the top`() {
        val names = listOf(
            "crash-20260910-080000-000Z.txt",
            "crash-20260912-211457-123Z.txt",
            "crash-20260911-235959-999Z.txt",
        )
        assertEquals(
            listOf(
                "crash-20260912-211457-123Z.txt",
                "crash-20260911-235959-999Z.txt",
                "crash-20260910-080000-000Z.txt",
            ),
            CrashLogPolicy.toKeep(names),
        )
    }

    @Test
    fun `toKeep drops names this app did not write`() {
        val names = listOf("session.json", "crash-20260912-211457-123Z.txt", "inflight")
        assertEquals(listOf("crash-20260912-211457-123Z.txt"), CrashLogPolicy.toKeep(names))
    }

    @Test
    fun `the phone keeps ten`() {
        assertEquals(10, CrashLogPolicy.KEEP)
        assertEquals("crashes", CrashLogPolicy.DIR)
    }

    @Test
    fun `nothing is deleted below the cap`() {
        val names = (1..9).map { CrashLogPolicy.fileName(ms("2026-09-0${it}T00:00:00Z")) }
        assertEquals(emptyList(), CrashLogPolicy.toDelete(names))
        assertEquals(9, CrashLogPolicy.toKeep(names).size)
    }

    @Test
    fun `nothing is deleted at exactly the cap`() {
        val names = (1..10).map { CrashLogPolicy.fileName(ms("2026-09-%02dT00:00:00Z".format(it))) }
        assertEquals(emptyList(), CrashLogPolicy.toDelete(names))
    }

    @Test
    fun `the eleventh crash pushes the oldest off, oldest first in the delete list`() {
        val names = (1..12).map { CrashLogPolicy.fileName(ms("2026-09-%02dT00:00:00Z".format(it))) }
        assertEquals(
            listOf(
                CrashLogPolicy.fileName(ms("2026-09-01T00:00:00Z")),
                CrashLogPolicy.fileName(ms("2026-09-02T00:00:00Z")),
            ),
            CrashLogPolicy.toDelete(names),
        )
        assertEquals(10, CrashLogPolicy.toKeep(names).size)
        assertEquals(CrashLogPolicy.fileName(ms("2026-09-12T00:00:00Z")), CrashLogPolicy.toKeep(names).first())
    }

    @Test
    fun `a file this app did not write is never deleted, however full the directory is`() {
        val ours = (1..12).map { CrashLogPolicy.fileName(ms("2026-09-%02dT00:00:00Z".format(it))) }
        val foreign = listOf("session.json", "inflight", "rescued", "crash-notes.txt")
        val deleted = CrashLogPolicy.toDelete(ours + foreign)
        for (name in foreign) assertFalse(name in deleted, name)
        assertEquals(2, deleted.size)
    }

    @Test
    fun `keep and delete never name the same file, and together they are all of ours`() {
        val ours = (1..15).map { CrashLogPolicy.fileName(ms("2026-09-%02dT00:00:00Z".format(it))) }
        val names = ours + "session.json"
        val kept = CrashLogPolicy.toKeep(names).toSet()
        val deleted = CrashLogPolicy.toDelete(names).toSet()
        assertTrue(kept.intersect(deleted).isEmpty())
        assertEquals(ours.toSet(), kept + deleted)
    }

    @Test
    fun `an empty directory decides nothing`() {
        assertEquals(emptyList(), CrashLogPolicy.toKeep(emptyList()))
        assertEquals(emptyList(), CrashLogPolicy.toDelete(emptyList()))
    }
}
