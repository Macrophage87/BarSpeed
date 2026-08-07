package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.Tempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice-guided cadence (e.g. tempo 4010): "Down, one, two, three, Up",
 * ~2 s breather, "Rep one", and around again — with a 5 s lead-in so
 * there's time to get positioned on the bar. Concentric-first lifts
 * (press, deadlift, row) run the cycle the way they're performed: "Up",
 * hold, counted "Down", breather at the rack/floor. The runner counts the
 * reps; the lifter just follows the voice.
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

    fun start(tempo: Tempo, plannedReps: Int?, concentricFirst: Boolean) {
        job =
            scope.launch {
                speak("Ready")
                countdownPhase("GET READY", GUIDED_LEAD_IN_S)
                var rep = 1
                while (true) {
                    if (concentricFirst) {
                        concentric(tempo)
                        hold(tempo.topPauseS.toInt())
                        countedEccentric(tempo)
                        countdownPhase("BREATHE", maxOf(tempo.bottomPauseS.toInt(), GUIDED_BREATHER_MIN_S))
                    } else {
                        countedEccentric(tempo)
                        hold(tempo.bottomPauseS.toInt())
                        concentric(tempo)
                        countdownPhase("BREATHE", maxOf(tempo.topPauseS.toInt(), GUIDED_BREATHER_MIN_S))
                    }
                    onRepCounted(rep)
                    when {
                        plannedReps != null && rep >= plannedReps -> {
                            speak("Done")
                            update("DONE", 0, 1)
                            return@launch
                        }
                        plannedReps != null && rep == plannedReps - 1 -> speak("Last rep")
                        else -> speak("Rep $rep")
                    }
                    delay(1_000)
                    rep++
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun hold(seconds: Int) {
        if (seconds <= 0) return
        speak("Hold")
        countdownPhase("HOLD", seconds)
    }

    private suspend fun countedEccentric(tempo: Tempo) {
        val eccS = tempo.eccentricS.toInt().coerceAtLeast(1)
        speak("Down")
        update("DOWN", eccS, eccS)
        for (second in 1..eccS) {
            delay(1_000)
            if (second < eccS) {
                speak("$second")
                update("DOWN", eccS - second, eccS)
            }
        }
    }

    private suspend fun concentric(tempo: Tempo) {
        speak("Up")
        countdownPhase("UP", (tempo.concentricS ?: 1.0).toInt().coerceAtLeast(1))
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
        const val GUIDED_BREATHER_MIN_S = 2
    }
}
