package com.macrophage.barspeed.model

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * How the text of a plan is taken in before [PlanImport.parse] sees it, from
 * any of the ways a plan reaches the phone: the file picker, "Open with" on a
 * file, or the share sheet with a file or with text (UX review 2026-11-06,
 * plans F1).
 *
 * Here rather than beside its Android callers so the size cap has a test: a
 * shared file is chosen by whatever app sent it, not by the lifter browsing
 * for a .json, so nothing else bounds how much of it would be read into
 * memory and handed to the parser. A plan is a few kilobytes; [MAX_BYTES] is
 * far above any real one and far below what would hurt.
 *
 * Nothing here approves or stages anything. A [Outcome.Text] goes to the same
 * staging call the picker uses, and the approval gate is shown as for any
 * other import.
 */
object PlanTextIntake {
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    const val TOO_LARGE: String = "This file is over 2 MB, too big to be a plan. Nothing was imported."

    /** The picker's existing wording, kept so all entry points say the same thing. */
    const val UNREADABLE: String = "Could not read the selected file."

    sealed interface Outcome {
        data class Text(val text: String) : Outcome

        data class Refused(val message: String) : Outcome
    }

    /**
     * Reads at most one byte past [MAX_BYTES], so an oversized stream is
     * refused without being read whole. The stream is closed. A null stream
     * (the resolver found nothing) and a read that fails are [UNREADABLE].
     */
    fun read(input: InputStream?): Outcome {
        if (input == null) return Outcome.Refused(UNREADABLE)
        val bytes =
            try {
                input.use { readAtMost(it, MAX_BYTES + 1) }
            } catch (e: IOException) {
                return Outcome.Refused(UNREADABLE)
            }
        if (bytes.size > MAX_BYTES) return Outcome.Refused(TOO_LARGE)
        return ofText(String(bytes, Charsets.UTF_8))
    }

    /**
     * A hand loop, not `InputStream.readNBytes`: that is Java 11 API, and on
     * Android it exists only from API 33, so it would compile and test green
     * here and fail on the lifter's phone.
     */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val BUFFER_BYTES = 8 * 1024

    /** Text that arrived as text (a share of the plan itself), held to the same cap. */
    fun ofText(text: String?): Outcome = when {
        text.isNullOrBlank() -> Outcome.Refused(UNREADABLE)
        text.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Outcome.Refused(TOO_LARGE)
        else -> Outcome.Text(text)
    }
}
