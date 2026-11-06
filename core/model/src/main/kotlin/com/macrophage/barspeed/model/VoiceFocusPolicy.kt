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
 * A focus change the platform reported for the voice's own request (#340),
 * named apart from `AudioManager`'s integers so the rule can be pinned here.
 * `VoiceCounter` maps the platform's constants onto these and ignores any
 * other value.
 */
enum class VoiceFocusChange { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

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
 * ducks at the set's first word and comes back when the set ends, not at
 * `Done`.
 *
 * - **Not before the first word.** A set recorded with the voice off, or one
 *   no voice counts, says nothing and must not duck the lifter's music for
 *   its length; [VoiceFocusState.spokenThisSet] is what tells them apart.
 * - **Released at the set's end**, which `RecordViewModel.endSet` reports,
 *   once the word playing then, if any, finishes. `Set ended` is spoken at
 *   the tap only where `SetEnd.terminalCall` asks for one; after a guided
 *   `Done`, and on an unguided rep set, nothing is, and focus goes at the
 *   tap. The rest that follows is outside any set.
 * - **Outside a set, per word.** The rest countdown's digits and `Rest over`
 *   take focus for each word and give it back when it finishes; they are
 *   four words a second apart, and the music comes back between them.
 * - **Marked spoken when a word STARTS, not when it is handed over** (#340).
 *   A word the engine refused, or failed before playing, never sounded, and
 *   must not hold the music down for the rest of the set: focus is asked
 *   for while the word is due, and let go when it is reported finished if
 *   it never started.
 * - **Cues switched off mid-set** (#340): with no cadence running nothing
 *   speaks any more, so the set stops counting as one that has spoken and
 *   focus goes once the word playing, if any, finishes. A cadence speaks
 *   whatever the toggle says, so under one the hold stands.
 * - **Platform changes** (#340): every change the platform sends the
 *   request except a permanent loss -- a transient loss such as a call, its
 *   can-duck form, or a gain -- says the request is still on the platform's
 *   stack, so it is still owed an abandon whatever was believed before.
 *   Only a permanent loss drops it. The voice keeps speaking through a
 *   transient loss; whether a call lets it be heard is the platform's. This
 *   is a stated rule, not a fix: see [heldAfter] for why it changes nothing
 *   the platform's documented sequence can reach. What DID change is the
 *   caller's: `VoiceCounter` moves to what is wanted after every change, so
 *   after a permanent loss mid-set it asks for focus again at once, where it
 *   used to wait for the set's next word.
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

    /**
     * Utterance [id] was handed to the engine, cancelling any before it.
     *
     * Focus is wanted from here, while the word is due, but the set is not
     * yet marked as having spoken: [started] does that, once the word plays
     * (#340).
     */
    fun spoke(state: VoiceFocusState, id: String): VoiceFocusState = state.copy(speaking = id)

    /** The engine reported utterance [id] done, stopped or failed. */
    fun finished(state: VoiceFocusState, id: String?): VoiceFocusState =
        if (id != null && id == state.speaking) state.copy(speaking = null) else state

    /**
     * The engine reported utterance [id] STARTED playing (#340): the one
     * event that marks a set as having spoken. A start for a word since
     * replaced, or outside a set, marks nothing.
     */
    fun started(state: VoiceFocusState, id: String?): VoiceFocusState =
        if (id != null && id == state.speaking && state.inSet) state.copy(spokenThisSet = true) else state

    /**
     * The lifter switched the audio cues off with a set under way (#340).
     *
     * [cadenceSpeaks] is whether a cadence guide is running, which speaks
     * whatever the toggle says (`GuidedCadenceRunner`'s own KDoc), so under
     * one nothing changes. Otherwise the set stops counting as one that has
     * spoken; a word playing at the toggle keeps focus until it finishes,
     * and a word spoken later -- the cues switched back on -- marks the set
     * again when it starts.
     */
    fun cuesSwitchedOff(state: VoiceFocusState, cadenceSpeaks: Boolean): VoiceFocusState =
        if (cadenceSpeaks) state else state.copy(spokenThisSet = false)

    /**
     * Whether the voice's request is still the platform's to answer for after
     * [change] arrived -- so whether [step] still owes it an ABANDON (#340).
     *
     * A permanent [VoiceFocusChange.LOSS] drops it. Every other change is the
     * platform addressing a request it still holds, so the answer is true
     * whatever [held] was. [held] is taken so the rule reads as a transition,
     * and the answer does not depend on it.
     *
     * Defensive: the listener as it stood could not be shown to reach a
     * request believed released while still stacked. On the platform's
     * documented sequence no gain or transient change reaches a request after
     * a permanent loss, an abandon or a refused request, and nothing here asks
     * for delayed focus, so this changes no observable behaviour; the rule
     * says what every change means so a later path cannot. During a call
     * (a transient loss, then a gain) the voice keeps its request and keeps
     * speaking, exactly as before; pausing or withholding speech during a
     * transient loss is not built.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun heldAfter(held: Boolean, change: VoiceFocusChange): Boolean = change != VoiceFocusChange.LOSS
}
