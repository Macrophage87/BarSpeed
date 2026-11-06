package com.macrophage.barspeed.model

/**
 * The subject and the one-line text a shared crash report carries beside its
 * attachment (UX review 2026-11-06, reliability F7).
 *
 * Before this the share sheet carried a bare file: email arrived with an
 * empty subject, and a chat app that takes text poorly took nothing. Both are
 * read off the crash file's own text, as [CrashRecord.render] wrote it, so
 * the subject names the version that CRASHED rather than the one installed
 * when the lifter got round to sending it. A field the file lacks -- an older
 * file has no `android:` or `device:` line -- is left out, never guessed.
 */
object CrashShareText {
    /** The exception line is cut here so the summary stays one short line. */
    const val MAX_EXCEPTION_CHARS = 160

    private const val STACK_HEADING = "--- STACK ---"

    /** `BarSpeed crash 0.2.1`, from the file's `version:` line, else [fallbackVersion]. */
    fun subject(text: String, fallbackVersion: String): String =
        "BarSpeed crash ${versionName(text) ?: fallbackVersion}"

    /**
     * One line: `BarSpeed 0.2.1 (62) crashed at <instant> on <screen>:
     * <exception>. Android 16 (API 36), Google Pixel 8.`
     */
    fun summary(text: String): String {
        val lines = text.lines()
        fun field(name: String) = lines.firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")?.trim()
        val version = field("version")
        val exception =
            lines.dropWhile { it != STACK_HEADING }.drop(1).firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("---") }
                ?.let { if (it.length > MAX_EXCEPTION_CHARS) it.take(MAX_EXCEPTION_CHARS) + "…" else it }
        val phone = listOfNotNull(field("android")?.let { "Android $it" }, field("device")).joinToString(", ")
        return buildString {
            append(if (version != null) "BarSpeed $version crashed" else "BarSpeed crashed")
            field("at")?.let { append(" at $it") }
            field("screen")?.let { append(" on $it") }
            exception?.let { append(": $it") }
            append(".")
            if (phone.isNotEmpty()) append(" $phone.")
        }
    }

    private fun versionName(text: String): String? =
        text.lines().firstOrNull { it.startsWith("version: ") }
            ?.removePrefix("version: ")?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }
}
