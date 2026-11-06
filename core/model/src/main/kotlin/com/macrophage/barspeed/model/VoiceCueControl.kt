package com.macrophage.barspeed.model

/**
 * The words on, and the placement of, Record's one voice switch (UX review
 * 2026-11-06: settings-onboarding F1/F2, voice-audio F2, record-setup F9).
 *
 * The switch is the audio-cue setting. It silences rep calls, the rest
 * countdown, hold and carry voice and their prep, but NOT a voice-guided
 * cadence or the prep before one (`LeadInPolicy.speaks`, `PrepCase.CUED`).
 * It used to read "Voice count ON/off", which names one of those things, and
 * nothing on screen said the cadence stays on; [label] names the whole
 * family and [TEMPO_CAPTION] says what it leaves on. What it silences is not
 * changed here.
 *
 * It used to be drawn only on SETUP and READY. READY shows only before a
 * session's first set, so from set 2 on nothing could mute or unmute the
 * voice. [inTopBar] puts it in the top bar on the two stages that had no
 * switch, IN_SET and RESTING; SETUP and READY keep theirs inline, with the
 * caption.
 */
object VoiceCueControl {
    fun label(on: Boolean): String = if (on) "Voice cues on" else "Voice cues off"

    const val TEMPO_CAPTION = "Tempo sets still call the cadence."

    fun inTopBar(stage: Stage): Boolean = stage == Stage.IN_SET || stage == Stage.RESTING
}
