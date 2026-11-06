package com.macrophage.barspeed.model

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size cap and read failures for a plan arriving by "Open with", the
 * share sheet or the file picker (UX review 2026-11-06, plans F1).
 */
class PlanTextIntakeTest {
    private val plan = """{"schemaVersion":"1.4","planName":"P","sessions":[]}"""

    @Test
    fun `a plan file is read as text`() {
        assertEquals(
            PlanTextIntake.Outcome.Text(plan),
            PlanTextIntake.read(ByteArrayInputStream(plan.toByteArray())),
        )
    }

    @Test
    fun `a file of exactly the cap is read`() {
        val bytes = ByteArray(PlanTextIntake.MAX_BYTES) { 'a'.code.toByte() }
        val outcome = PlanTextIntake.read(ByteArrayInputStream(bytes))
        assertTrue(outcome is PlanTextIntake.Outcome.Text)
        assertEquals(PlanTextIntake.MAX_BYTES, outcome.text.length)
    }

    @Test
    fun `a file one byte over the cap is refused with the size message`() {
        val bytes = ByteArray(PlanTextIntake.MAX_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(
            PlanTextIntake.Outcome.Refused(PlanTextIntake.TOO_LARGE),
            PlanTextIntake.read(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun `an endless stream is refused without being read to its end`() {
        var served = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    served++
                    return 'a'.code
                }
            }
        assertEquals(PlanTextIntake.Outcome.Refused(PlanTextIntake.TOO_LARGE), PlanTextIntake.read(endless))
        assertEquals(PlanTextIntake.MAX_BYTES + 1L, served)
    }

    @Test
    fun `no stream, an empty file and a failing read are all unreadable`() {
        val unreadable = PlanTextIntake.Outcome.Refused(PlanTextIntake.UNREADABLE)
        assertEquals(unreadable, PlanTextIntake.read(null))
        assertEquals(unreadable, PlanTextIntake.read(ByteArrayInputStream(ByteArray(0))))
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("gone")
            }
        assertEquals(unreadable, PlanTextIntake.read(failing))
    }

    @Test
    fun `shared text is held to the same cap`() {
        assertEquals(PlanTextIntake.Outcome.Text(plan), PlanTextIntake.ofText(plan))
        assertEquals(
            PlanTextIntake.Outcome.Refused(PlanTextIntake.TOO_LARGE),
            PlanTextIntake.ofText("a".repeat(PlanTextIntake.MAX_BYTES + 1)),
        )
        assertEquals(PlanTextIntake.Outcome.Refused(PlanTextIntake.UNREADABLE), PlanTextIntake.ofText(null))
        assertEquals(PlanTextIntake.Outcome.Refused(PlanTextIntake.UNREADABLE), PlanTextIntake.ofText("  \n"))
    }
}
