package com.macrophage.barspeed

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import com.macrophage.barspeed.model.SpokenRepCall
import com.macrophage.barspeed.model.VoiceFocusPolicy
import com.macrophage.barspeed.model.VoiceFocusState
import com.macrophage.barspeed.model.VoiceFocusStep

/**
 * Thin wrapper around the platform TTS engine for tempo counting. Counts are
 * spoken with QUEUE_FLUSH so a late utterance never lags behind the bar.
 *
 * Two things #339 added, both compile- and lint-gated only: no test reaches
 * this class, and none can say what the engine or the platform did with what
 * it is handed.
 *
 * - **How a rep call is read.** [speak] hands the engine the text it was
 *   given, unchanged, with a `TtsSpan` over each span [SpokenRepCall] marks:
 *   TYPE_TEXT reading `rep` over the word, TYPE_CARDINAL over its number. The
 *   cue track never sees this; the caller recorded its text before calling.
 * - **Ducking music.** Speech plays with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
 *   and CONTENT_TYPE_SPEECH, and AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK is asked
 *   for with the same attributes over the span [VoiceFocusPolicy] decides --
 *   the whole of a set once it has spoken, and each word outside one. Never
 *   AUDIOFOCUS_GAIN, which asks music to stop rather than duck.
 *
 * The engine reports each utterance on a thread of its own, so the focus
 * state is read and written under [lock].
 */
class VoiceCounter(context: Context) {
    private var ready = false
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val lock = Any()
    private var focus = VoiceFocusState()
    private var focusHeld = false
    private var utterances = 0L

    // A loss means another app took focus outright: nothing is held any more,
    // and the next word that wants focus asks for it again.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(lock) { focusHeld = false }
        }
        .build()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    private val tts =
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }.apply {
            setAudioAttributes(attributes)
            setOnUtteranceProgressListener(progress)
        }

    fun speak(text: String) {
        if (!ready) return
        val id = synchronized(lock) {
            utterances += 1
            "barspeed-count-$utterances".also { move(VoiceFocusPolicy.spoke(focus, it)) }
        }
        if (tts.speak(marked(text), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    /** A set has begun; see [VoiceFocusPolicy] for what that changes. */
    fun setStarted() = synchronized(lock) { move(VoiceFocusPolicy.setStarted(focus)) }

    /** The set is over; focus goes once the word playing now has finished. */
    fun setEnded() = synchronized(lock) { move(VoiceFocusPolicy.setEnded(focus)) }

    fun shutdown() {
        ready = false
        synchronized(lock) { move(VoiceFocusState()) }
        tts.shutdown()
    }

    private fun finished(id: String?) = synchronized(lock) { move(VoiceFocusPolicy.finished(focus, id)) }

    /** Caller holds [lock]. */
    private fun move(next: VoiceFocusState) {
        focus = next
        when (VoiceFocusPolicy.step(focusHeld, VoiceFocusPolicy.holds(next))) {
            VoiceFocusStep.REQUEST -> {
                focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            VoiceFocusStep.ABANDON -> {
                audio.abandonAudioFocusRequest(focusRequest)
                focusHeld = false
            }
            VoiceFocusStep.KEEP -> Unit
        }
    }

    private fun marked(text: String): CharSequence {
        val spans = SpokenRepCall.spansOf(text)
        if (spans.isEmpty()) return text
        return SpannableString(text).apply {
            for (span in spans) {
                val reading = when (span.reading) {
                    SpokenRepCall.Reading.WORD -> TtsSpan.TextBuilder(span.text).build()
                    SpokenRepCall.Reading.CARDINAL -> TtsSpan.CardinalBuilder(span.text).build()
                }
                setSpan(reading, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
