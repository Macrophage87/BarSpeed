package com.macrophage.barspeed.record

import com.macrophage.barspeed.dsp.CadenceBeat
import com.macrophage.barspeed.dsp.CadencePlan
import com.macrophage.barspeed.dsp.CadenceVoice
import com.macrophage.barspeed.dsp.LeadInPlan
import com.macrophage.barspeed.dsp.TempoSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * On-screen label held for the whole prep, unchanged from before the lead-in
 * had a voice.
 *
 * `internal` rather than private so `RecordViewModel` can tell a lead-in push
 * from a cadence push by comparing against THIS constant rather than a second
 * copy of the string. Two copies of a label are two facts that can disagree,
 * and the way they disagree here is a set spending its whole cadence marked as
 * not yet started.
 */
internal const val LEAD_IN_LABEL = "GET READY"

/**
 * Voice-guided cadence. The runner plays the tempo prescription and counts the
 * reps; the lifter just follows the voice.
 *
 * Two entry points. [start] plays the prep and then the cadence. [startPrep]
 * plays the prep and hands control back, for a hold or a carry, which has one
 * movement lasting the whole set and so no cadence to follow.
 *
 * What it plays comes from [CadencePlan], which is pure and lives in
 * `:core:dsp` where a test can reach it. This class is the player: it walks the
 * beats, sleeps a second at a time, pushes the label and countdown, and speaks.
 * It decides no timing of its own.
 *
 * That split is the point. No test on the CI path reaches this file, so the
 * arithmetic that used to live here could not be asserted — and for every set the app ever
 * paced it added a second the prescription did not ask for, which is issue 106.
 * An earlier version allotted a flat 3 s to everything after the first stroke,
 * which made every tempo unachievable and scored the surplus as the athlete's
 * error.
 *
 * The order of the strokes, their prescribed seconds and the words used for
 * them all come from [TempoSchedule], which resolves the tempo digits against
 * the declared direction and plane — so a leg curl is called "Down" on its
 * drive and a seated row is called "Drive" and "Return", having no up or down.
 */
class GuidedCadenceRunner(
    private val scope: CoroutineScope,
    /**
     * Guided cadence is an audio feature: it speaks even with the count toggle
     * off.
     *
     * Two arguments, and the split is load-bearing. The first is the CUE ROWS —
     * the words that go on the set's cue track, which is a persisted format
     * every cue-track consumer parses. The second is the UTTERANCE actually
     * spoken. One utterance can carry two words the record wants kept apart:
     * a merged rep call is spoken as `"Down, Rep 1"` and recorded as `Down`
     * and `Rep 1`, because renaming the `Down` row would break every consumer
     * that matches it while dropping the call would lose what the app said.
     * All rows of one call share one instant, so the caller stamps them once.
     *
     * An EMPTY list means speak it and write nothing down. The lead-in needs
     * that third state — its countdown digits and its `"N seconds"` opener are
     * spoken and not recorded. [LeadInPlan.RECORDED] decides which lead-in
     * words reach the record, and nothing else may.
     */
    private val speak: (cues: List<String>, utterance: String) -> Unit,
    /** Pushes the on-screen phase label + countdown (label, remaining, total). */
    private val update: (String, Int, Int) -> Unit,
    /** Called each time a full rep cycle completes, with the running count. */
    private val onRepCounted: (Int) -> Unit,
    /**
     * Called once the prescription has been called all the way through. Not
     * called when the runner is cancelled — a set the lifter cut short did not
     * finish, and nothing downstream should think it did.
     */
    private val onFinished: () -> Unit = {},
) {
    private var job: Job? = null

    /**
     * [prepS] is the prep in whole seconds, decided by the caller rather than
     * read from a constant here.
     *
     * An argument because the value has to be RECORDED as well as played, and
     * the recorder is the caller. A constant read here and a second statement
     * of the same constant at the write site are two facts that can disagree,
     * and the way they disagree is one of them changing -- at which point every
     * capture claims a prep the lifter never heard.
     *
     * [onWorkStarted] runs when the lead-in ends and before the first stroke is
     * called, which is where the set stops being prep (#185). Called from
     * inside this coroutine after the lead-in's last sleep rather than launched
     * beside it -- a sequence point rather than an ordering between two
     * coroutines, which is the shape [startPrep] already uses for its own
     * `onStarted` and the shape of the dispatcher race this repository has
     * fixed once.
     *
     * No default. One caller passes it, and a default would let the next one
     * record a set whose window nothing ever closed.
     */
    fun start(schedule: TempoSchedule, plannedReps: Int?, prepS: Int, onWorkStarted: () -> Unit) {
        job =
            scope.launch {
                playLeadIn(LeadInPlan.of(prepS))
                onWorkStarted()
                val plan = CadencePlan.of(schedule)
                var rep = 1
                var pending: String? = null
                while (true) {
                    for ((index, beat) in plan.beats.withIndex()) {
                        val announcement = pending?.takeIf { index == plan.announceOnBeat }
                        if (announcement != null) pending = null
                        play(beat, announcement)
                        if (index != plan.repCompleteAfterBeat) continue
                        onRepCounted(rep)
                        if (plannedReps != null && rep >= plannedReps) {
                            speak(listOf(CadenceVoice.DONE), CadenceVoice.DONE)
                            update("DONE", 0, 1)
                            onFinished()
                            return@launch
                        }
                        pending = plan.announcementAfter(rep, plannedReps)
                        rep++
                    }
                }
            }
    }

    /**
     * Play the prep, speak the word the set opens on, and hand control back.
     *
     * For a set with no cadence to run into. [startWord] is `Hold` or `Carry`,
     * chosen by `LeadInPolicy.timedStartWord` in `:core:model` and not here --
     * the same division that keeps [TempoSchedule] the one thing that names a
     * stroke. It is RECORDED as well as spoken whenever the prep speaks, the way
     * [CadencePlan]'s beat 0 records the first stroke call, so the instant the
     * set began is readable from the cue track against the raw stream on the
     * same clock. With [speaks] false nothing reaches the cue track and that
     * instant is not recoverable from it.
     *
     * `Hold` is also what a tempo's isometric pause is called. The two are told
     * apart by the set they sit on: `endSet` writes no tempo on a timed set, so
     * a set carrying this cue and no `tempoPrescribed` is a hold beginning.
     *
     * [onStarted] runs immediately after that word, and is the instant the
     * set's own clock starts. It is called from inside this coroutine, after
     * the last beat's sleep, rather than launched beside the prep: a sequence
     * point rather than an ordering between two coroutines, which is the shape
     * of the dispatcher race this repository has already fixed once.
     *
     * [speaks] false runs the same seconds and pushes the same ring, saying
     * nothing and writing nothing to the cue track. The clock still starts when
     * the prep ends, so no figure the set records changes; what is lost is the
     * cue-track row marking the instant the set began. The INSTANT is no longer
     * lost with that row: since #185 a set that runs a prep records the
     * boundary as a prep window, spoken or silent, and the archive publishes
     * it beside the raw streams. Who decides whether the prep speaks is
     * `LeadInPolicy.speaks`, not this class.
     */
    fun startPrep(prepS: Int, startWord: String, speaks: Boolean, onStarted: () -> Unit) {
        job =
            scope.launch {
                playLeadIn(LeadInPlan.of(prepS), speaks)
                if (speaks) speak(listOf(startWord), startWord)
                onStarted()
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Play one beat, optionally opening with a rep announcement.
     *
     * What is said and what is written down are [CadenceVoice]'s decisions, in
     * `:core:dsp` where a test can reach them; this walks the seconds. The
     * announcement is merged into the beat's own utterance rather than spoken
     * separately, because TTS runs with QUEUE_FLUSH and a second utterance a
     * moment later would cancel the first.
     */
    private suspend fun play(beat: CadenceBeat, announcement: String?) {
        CadenceVoice.beatCall(beat, announcement)?.let { speak(it.recorded, it.utterance) }
        if (!beat.isStroke) {
            countdownPhase(beat.label, beat.seconds)
            return
        }
        update(beat.label, beat.seconds, beat.seconds)
        for (second in 1..beat.seconds) {
            delay(1_000)
            CadenceVoice.countCall(beat, announcement, second)?.let { speak(it.recorded, it.utterance) }
            if (second < beat.seconds) update(beat.label, beat.seconds - second, beat.seconds)
        }
    }

    /**
     * Walk the prep, one beat per second, then return with the first stroke due
     * immediately.
     *
     * The ring is pushed before each sleep and the beat's word is spoken while
     * that number is on screen. Both come from [LeadInPlan.secondsBeforeStart],
     * so they cannot drift apart.
     */
    private suspend fun playLeadIn(plan: LeadInPlan, speaks: Boolean = true) {
        val total = plan.prepS.coerceAtLeast(1)
        update(LEAD_IN_LABEL, plan.prepS, total)
        for ((index, beat) in plan.beats.withIndex()) {
            val spoken = beat.spoken
            if (spoken != null && speaks) speak(listOfNotNull(beat.cue), spoken)
            delay(1_000)
            update(LEAD_IN_LABEL, plan.secondsBeforeStart(index) - 1, total)
        }
    }

    private suspend fun countdownPhase(label: String, seconds: Int) {
        update(label, seconds, seconds.coerceAtLeast(1))
        repeat(seconds) { done ->
            delay(1_000)
            update(label, seconds - done - 1, seconds.coerceAtLeast(1))
        }
    }
}
