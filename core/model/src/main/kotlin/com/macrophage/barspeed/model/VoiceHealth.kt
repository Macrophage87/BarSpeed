package com.macrophage.barspeed.model

import java.util.Locale

/**
 * Whether the speech engine behind the voice can speak at all.
 *
 * UX review 2026-11-06, voice F1: a missing, broken or language-less engine
 * dropped every word silently, so a tempo set ran on the screen ring alone
 * and the lifter found out under the bar when "Brace" never came.
 */
enum class VoiceStatus {
    /** The engine is binding; nothing is known yet. */
    STARTING,

    /** Bound, with a language it can speak. */
    READY,

    /** No text-to-speech engine is installed or enabled on the phone. */
    NO_ENGINE,

    /** An engine exists but would not start, or stopped and would not come back. */
    FAILED,

    /** The engine runs but has no voice for the phone's language or for US English. */
    NO_LANGUAGE,
}

/** One line for the Record screen, and whether tapping it should open the phone's speech settings. */
data class VoiceWarning(val text: String, val opensSpeechSettings: Boolean)

/**
 * The decisions behind [VoiceStatus] and the warning drawn from it, kept here
 * so they are pinned by `VoiceHealthTest`. The engine calls themselves live in
 * `VoiceCounter` in :app, which no test reaches.
 *
 * The platform integers are copied, not imported, because :core:model has no
 * Android: `TextToSpeech.LANG_*` results are 0, 1 and 2 when usable and -1
 * (`LANG_MISSING_DATA`) or -2 (`LANG_NOT_SUPPORTED`) when not, and
 * `TextToSpeech.ERROR_SERVICE` is -4 and `ERROR_NOT_INSTALLED_YET` -9.
 */
object VoiceHealth {
    /** Init retries after a failed start or a dead engine. One, so a broken engine cannot loop. */
    const val MAX_RETRIES = 1

    private const val ERROR_SERVICE = -4
    private const val ERROR_NOT_INSTALLED_YET = -9

    /** Whether a `setLanguage` / `isLanguageAvailable` result means the engine can speak it. */
    fun languageUsable(result: Int): Boolean = result >= 0

    /**
     * The status once the engine has answered its init.
     *
     * [hasEngine] only decides between NO_ENGINE and FAILED when init has
     * already failed: a successful init proves an engine whatever a package
     * query said. The language is the phone's own first, then US English,
     * each tried once; [setLanguage] is the engine's call and leaves the
     * engine on the last language it accepted.
     */
    fun afterInit(
        initSucceeded: Boolean,
        hasEngine: Boolean,
        phoneLocale: Locale,
        setLanguage: (Locale) -> Int,
    ): VoiceStatus = when {
        !initSucceeded && !hasEngine -> VoiceStatus.NO_ENGINE
        !initSucceeded -> VoiceStatus.FAILED
        listOf(phoneLocale, Locale.US).distinct().any { languageUsable(setLanguage(it)) } -> VoiceStatus.READY
        else -> VoiceStatus.NO_LANGUAGE
    }

    /** Whether a failed or dead engine is worth one more start. */
    fun mayRetry(status: VoiceStatus, retriesUsed: Int): Boolean =
        status == VoiceStatus.FAILED && retriesUsed < MAX_RETRIES

    /** Whether an utterance error code says the engine itself has gone, rather than one word failing. */
    fun engineDied(errorCode: Int): Boolean = errorCode == ERROR_SERVICE || errorCode == ERROR_NOT_INSTALLED_YET

    /**
     * The one line the Record screen shows, or null for none.
     *
     * [voiceWanted] is whether anything would speak: the cues are on, or a
     * tempo guide runs, which speaks whatever the toggle says. With nothing
     * wanting the voice, a silent engine costs the lifter nothing and is not
     * worth a banner. An engine that cannot speak outranks the volume, since
     * raising the volume would not help it.
     */
    fun warning(status: VoiceStatus, mediaVolumeZero: Boolean, voiceWanted: Boolean): VoiceWarning? = when {
        !voiceWanted -> null
        status == VoiceStatus.NO_ENGINE -> VoiceWarning("Voice is off: no speech engine", opensSpeechSettings = true)
        status == VoiceStatus.FAILED -> VoiceWarning("Voice is off: speech engine failed", opensSpeechSettings = true)
        status == VoiceStatus.NO_LANGUAGE ->
            VoiceWarning("Voice is off: no voice for this language", opensSpeechSettings = true)
        mediaVolumeZero -> VoiceWarning("Voice is silent: media volume is 0", opensSpeechSettings = false)
        else -> null
    }
}
