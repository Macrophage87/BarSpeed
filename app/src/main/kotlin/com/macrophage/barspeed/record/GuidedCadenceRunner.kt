package com.macrophage.barspeed.record

import com.macrophage.barspeed.dsp.TempoSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice-guided cadence. The runner plays the tempo prescription and counts the
 * reps; the lifter just follows the voice.
 *
 * **The metronome plays exactly what the tempo string asks for.** The digits are
 * positional — down, bottom pause, up, top pause — and each gets its prescribed
 * seconds and nothing more. The rep call is spoken at the START of the closing
 * phase rather than added after it, so it costs no time. Only when a
 * prescription leaves no gap at all between the last stroke and the next rep is
 * a one-second breath inserted, enough to say "Rep four" without the next call
 * cutting it off. An earlier version allotted a flat 3 s to everything after the
 * first stroke, which made every tempo unachievable and scored the surplus as
 * the athlete's error.
 *
 * The order of the strokes, their prescribed seconds and the words used for
 * them all come from [TempoSchedule], which resolves the tempo digits against
 * the declared direction and plane — so a leg curl is called "Down" on its
 * drive and a seated row is called "Drive" and "Return", having no up or down.
 */
class GuidedCadenceRunner(
    private val scope: CoroutineScope,
    /** Guided cadence is an audio feature: it speaks even with the count toggle off. */
    private val speak: (String) -> Unit,
    /** Pushes the on-screen phase label + countdown (label, remaining, total). */
    private val update: (String, Int, Int) -> Unit,
    /** Called each time a full rep cycle completes, with the running count. */
    private val onRepCounted: (Int) -> Unit,
) {
    private var job: Job? = null

    fun start(schedule: TempoSchedule, plannedReps: Int?) {
        job =
            scope.launch {
                speak("Ready")
                countdownPhase("GET READY", GUIDED_LEAD_IN_S)
                val firstS = (schedule.first.seconds ?: 1.0).toInt().coerceAtLeast(1)
                val secondS = (schedule.second.seconds ?: 1.0).toInt().coerceAtLeast(1)
                val firstPause = schedule.pauseAfterFirstS.toInt()
                // Something has to carry the rep call; borrow the closing pause
                // when the prescription provides one, otherwise add a single second.
                val closing = maxOf(schedule.pauseAfterSecondS.toInt(), GUIDED_REP_CALL_S)
                var rep = 1
                while (true) {
                    stroke(schedule.first.label, firstS)
                    pause(firstPause)
                    stroke(schedule.second.label, secondS)
                    onRepCounted(rep)
                    val done = plannedReps != null && rep >= plannedReps
                    when {
                        done -> speak("Done")
                        plannedReps != null && rep == plannedReps - 1 -> speak("Last rep")
                        else -> speak("Rep $rep")
                    }
                    if (done) {
                        update("DONE", 0, 1)
                        return@launch
                    }
                    countdownPhase("BREATHE", closing)
                    rep++
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun pause(seconds: Int) {
        if (seconds <= 0) return
        speak("Hold")
        countdownPhase("HOLD", seconds)
    }

    /**
     * One movement stroke. Strokes long enough to lose your place in are counted
     * out loud ("Down, one, two, three"); a one-second drive just gets its call.
     */
    private suspend fun stroke(label: String, seconds: Int) {
        speak(label.lowercase().replaceFirstChar { it.uppercase() })
        update(label, seconds, seconds)
        val countAloud = seconds >= COUNT_ALOUD_FROM_S
        for (second in 1..seconds) {
            delay(1_000)
            if (second < seconds) {
                if (countAloud) speak("$second")
                update(label, seconds - second, seconds)
            }
        }
    }

    private suspend fun countdownPhase(label: String, seconds: Int) {
        update(label, seconds, seconds.coerceAtLeast(1))
        repeat(seconds) { done ->
            delay(1_000)
            update(label, seconds - done - 1, seconds.coerceAtLeast(1))
        }
    }

    companion object {
        const val GUIDED_LEAD_IN_S = 5

        /** Seconds borrowed from (not added to) the closing pause for the rep call. */
        const val GUIDED_REP_CALL_S = 1

        /** Strokes at least this long get counted out loud second by second. */
        const val COUNT_ALOUD_FROM_S = 2
    }
}
