package com.macrophage.barspeed.model

/**
 * What the session detail screen says when an export does not happen. UX
 * review 2026-11-06, history-export F6.
 *
 * Before this, an exception from building an export escaped
 * `viewModelScope.launch` and crashed the app on the Export tap, and a session
 * that could not be found re-enabled the buttons with no word at all.
 *
 * Here rather than in `:app` for the reason [SaveToPhonePolicy] is: no test on
 * the CI path reaches a ViewModel.
 */
object ExportFailureCopy {
    /** The export's session row is gone, so there was nothing to build. */
    const val SESSION_MISSING = "Session not found; nothing exported."

    /** Longest reason shown, so a toast stays one or two lines. */
    const val MAX_REASON_CHARS = 80

    private const val ELLIPSIS = "…"

    /**
     * "Export failed: <reason>", where the reason is the first line of
     * [errorMessage], or the error's [errorClass] name when the message is
     * missing or blank, cut to [MAX_REASON_CHARS]. A stack trace's later lines
     * and a caused-by chain belong in the crash report, not on screen.
     */
    fun failed(errorClass: String?, errorMessage: String?): String {
        val firstLine = errorMessage?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val reason = firstLine ?: errorClass?.takeIf { it.isNotBlank() } ?: "unknown error"
        val short = if (reason.length > MAX_REASON_CHARS) reason.take(MAX_REASON_CHARS - 1) + ELLIPSIS else reason
        return "Export failed: $short"
    }
}
