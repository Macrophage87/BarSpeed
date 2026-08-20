package com.macrophage.barspeed.record

import com.macrophage.barspeed.dsp.CadenceBeat
import com.macrophage.barspeed.dsp.CadencePlan
import com.macrophage.barspeed.dsp.GuidedCadence
import com.macrophage.barspeed.dsp.LeadInPlan
import com.macrophage.barspeed.dsp.TempoSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** On-screen label held for the whole prep, unchanged from before the lead-in had a voice. */
private const val LEAD_IN_LABEL = "GET READY"

/**
 * Voice-guided cadence. The runner plays the tempo prescription and counts the
 * reps; the lifter just follows the voice.
 *
 * What it plays comes from [CadencePlan], which is pure and lives in
 * `:core:dsp` where a test can reach it. This class is the player: it walks the
 * beats, sleeps a second at a time, pushes the label and countdown, and speaks.
 * It decides no timing of its own.
 *
 * That split is the point. This file has no test source set, so the arithmetic
 * that used to live here could not be asserted — and for every set the app ever
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
     * Two arguments, and the split is load-bearing. The first is the CUE — the
     * phase that was called, which is what gets written to the set's cue track
     * and is a persisted format every cue-track consumer parses. The second is
     * the UTTERANCE actually spoken, which may carry a rep announcement along
     * with the cue. Merging the announcement into the logged cue would rename
     * "Down" to "Down, Rep 1" in every capture made afterwards and break every
     * one of those consumers.
     *
     * A NULL cue means speak it and write nothing down. The lead-in needs that
     * third state — its countdown digits and its `"N seconds"` opener are
     * spoken and not recorded. [LeadInPlan.RECORDED] decides which lead-in
     * words reach the record, and nothing else may.
     */
    private val speak: (cue: String?, utterance: String) -> Unit,
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

    fun start(schedule: TempoSchedule, plannedReps: Int?) {
        job =
            scope.launch {
                playLeadIn(LeadInPlan.of(GuidedCadence.LEAD_IN_S))
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
                            speak("Done", "Done")
                            update("DONE", 0, 1)
                            onFinished()
                            return@launch
                        }
                        pending =
                            when {
                                plan.announceOnBeat == null -> null
                                plannedReps != null && rep == plannedReps - 1 -> "Last rep"
                                else -> "Rep $rep"
                            }
                        rep++
                    }
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Play one beat, optionally opening with a rep announcement.
     *
     * The announcement is merged into the beat's own call rather than spoken
     * separately: TTS runs with QUEUE_FLUSH, so a second utterance a moment
     * later would cancel the first. Strokes long enough to lose your place in
     * are counted out loud ("Down, one, two, three"); a one-second drive just
     * gets its call.
     */
    private suspend fun play(beat: CadenceBeat, announcement: String?) {
        // The announcement rides the utterance, never the cue.
        val cue = beat.spokenLabel
        if (cue != null) {
            speak(cue, if (announcement != null) "$cue, $announcement" else cue)
        } else if (announcement != null) {
            speak(announcement, announcement)
        }
        if (!beat.isStroke) {
            countdownPhase(beat.label, beat.seconds)
            return
        }
        update(beat.label, beat.seconds, beat.seconds)
        val countAloud = beat.seconds >= GuidedCadence.COUNT_ALOUD_FROM_S
        for (second in 1..beat.seconds) {
            delay(1_000)
            if (second < beat.seconds) {
                // Only give up the count when an announcement actually took
                // its place: rep 1 has none pending, and a one-rep set never
                // has one at all.
                val gaveUpCount = beat.suppressFirstCount && announcement != null && second == 1
                if (countAloud && !gaveUpCount) speak("$second", "$second")
                update(beat.label, beat.seconds - second, beat.seconds)
            }
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
    private suspend fun playLeadIn(plan: LeadInPlan) {
        val total = plan.prepS.coerceAtLeast(1)
        update(LEAD_IN_LABEL, plan.prepS, total)
        for ((index, beat) in plan.beats.withIndex()) {
            val spoken = beat.spoken
            if (spoken != null) speak(beat.cue, spoken)
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
