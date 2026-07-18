package com.macrophage.barspeed

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Thin wrapper around the platform TTS engine for tempo counting. Counts are
 * spoken with QUEUE_FLUSH so a late utterance never lags behind the bar.
 */
class VoiceCounter(context: Context) {
    private var ready = false
    private val tts =
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }

    fun speak(text: String) {
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "barspeed-count")
        }
    }

    fun shutdown() {
        ready = false
        tts.shutdown()
    }
}
