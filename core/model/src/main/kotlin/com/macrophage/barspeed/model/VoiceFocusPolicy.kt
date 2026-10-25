package com.macrophage.barspeed.model

/**
 * What the voice's audio-focus bookkeeping knows: whether a set is under way,
 * whether anything has been said in it, and which utterance is playing.
 *
 * [speaking] is the id of the utterance last handed to the engine and not yet
 * reported finished, or null. One id rather than a count, because the voice
 * speaks with QUEUE_FLUSH: a new utterance cancels the one before it, so at
 * most one is ever playing and a stale "finished" for the cancelled one must
 * not be read as the end of the one that replaced it.
 */
data class VoiceFocusState(
    val inSet: Boolean = false,
    val spokenThisSet: Boolean = false,
    val speaking: String? = null,
)

/** What to do with audio focus to move from what is held to what is wanted. */
enum class VoiceFocusStep { REQUEST, ABANDON, KEEP }

/**
 * When the voice holds audio focus, so music ducks under it rather than
 * playing at the same level (#339).
 *
 * The owner, 2026-10-25: *"is there a way to make this louder than music, so
 * both can be played?"* `VoiceCounter` in `:app` acts on [step]'s answer with
 * a transient, may-duck focus request; this decides only WHEN focus is held.
 *
 * ## Held for the set, not taken per word
 *
 * Focus is wanted while a word is playing, and -- once a set has said
 * anything -- for the rest of that set. Per-word focus was the alternative
 * and is rejected: a guided set speaks on every second, so releasing after
 * each word would raise the music in every gap and duck it again at the next
 * word, a pumping the lifter would hear for the whole set. Held, the music
 * ducks at the set's first word and comes back after its last.
 *
 * - **Not before the first word.** A set recorded with the voice off, or one
 *   no voice counts, says nothing and must not duck the lifter's music for
 *   its length; [VoiceFocusState.spokenThisSet] is what tells them apart.
 * - **Released at the set's end**, which `RecordViewModel.endSet` reports,
 *   once the word playing then -- `Set ended`, spoken at the tap -- finishes.
 *   The rest that follows is outside any set.
 * - **Outside a set, per word.** The rest countdown's digits and `Rest over`
 *   take focus for each word and give it back when it finishes; they are
 *   four words a second apart, and the music comes back between them.
 * - **A voice going away** starts again from `VoiceFocusState()`: no set,
 *   nothing playing, so whatever is held is abandoned. `VoiceCounter.shutdown`
 *   does that; it is reached from `RecordViewModel.onCleared`, which is also
 *   the path a set abandoned by leaving the screen takes.
 *
 * WHAT IS NOT DECIDED HERE: what kind of focus is asked for, which is the
 * caller's (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, never `AUDIOFOCUS_GAIN`,
 * which asks music to stop), and whether the platform granted it -- [step]
 * takes what is held as given. What a music app does when asked to duck is
 * the app's, and is `[Field]`.
 */
object VoiceFocusPolicy {
    /** Whether focus is wanted in [state]: a word is playing, or a set under way has spoken. */
    fun holds(state: VoiceFocusState): Boolean = state.speaking != null || (state.inSet && state.spokenThisSet)

    /** The move from [held] to [wanted]. */
    fun step(held: Boolean, wanted: Boolean): VoiceFocusStep = when {
        wanted && !held -> VoiceFocusStep.REQUEST
        held && !wanted -> VoiceFocusStep.ABANDON
        else -> VoiceFocusStep.KEEP
    }

    /** A set has begun: nothing has been said in it yet. */
    fun setStarted(state: VoiceFocusState): VoiceFocusState = state.copy(inSet = true, spokenThisSet = false)

    /** The set is over, whether it ended or was stopped. */
    fun setEnded(state: VoiceFocusState): VoiceFocusState = state.copy(inSet = false, spokenThisSet = false)

    /** Utterance [id] was handed to the engine, cancelling any before it. */
    fun spoke(state: VoiceFocusState, id: String): VoiceFocusState =
        state.copy(speaking = id, spokenThisSet = state.spokenThisSet || state.inSet)

    /** The engine reported utterance [id] done, stopped or failed. */
    fun finished(state: VoiceFocusState, id: String?): VoiceFocusState =
        if (id != null && id == state.speaking) state.copy(speaking = null) else state
}
