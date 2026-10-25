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
 * NOTHING READS THIS YET, and at this commit [holds] and [step] answer what
 * the voice does at the parent: it never holds focus and never asks for it.
 * The event functions are bookkeeping and change no answer while that holds.
 * A voice going away starts again from `VoiceFocusState()`: no set, nothing
 * playing.
 */
object VoiceFocusPolicy {
    /** Whether focus is wanted in [state]. */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun holds(state: VoiceFocusState): Boolean = false

    /** The move from [held] to [wanted]. */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun step(held: Boolean, wanted: Boolean): VoiceFocusStep = VoiceFocusStep.KEEP

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
