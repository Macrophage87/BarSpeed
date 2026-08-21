package com.macrophage.barspeed.record

import com.macrophage.barspeed.dsp.SyntheticSets
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.Tempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Demo/replay mode (spec 5): synthesize a realistic set and feed it through the
 * live pipeline sample by sample, so everything downstream of the sensor — rep
 * segmentation, voice cues, the live readout — runs exactly as it does on real
 * hardware. Emits into [onSample] on a background dispatcher.
 */
fun CoroutineScope.launchDemoStream(
    reps: Int,
    tempo: Tempo?,
    eccentricFirst: Boolean,
    onSample: (ImuSample) -> Unit,
): Job = launch(Dispatchers.Default) {
    delay(DEMO_LEAD_IN_MS)
    // downS and upS are read POSITIONALLY here and that is correct, which is
    // worth saying because #127 changed three other sites that were not.
    // SyntheticSets.RepSpec.eccS is the phase it generates with a NEGATIVE
    // sign -- the down stroke -- whatever the lift's drive direction is.
    // Resolving the eccentric here would invert it on a leg curl.
    //
    // Which stroke comes FIRST is a separate matter and is NOT correct:
    // `eccentricFirst` in RecordViewModel.startDemoStream is passed
    // `startsWith == ECCENTRIC`, a phase fact, where SyntheticSets.generate
    // uses the flag to choose which SIGN goes first. `ExerciseDef.startsAtTop`
    // is the property that answers the question actually being asked. Left as
    // a remainder rather than folded in: it is demo-only and the wiring is in
    // this module, where no test can reach it.
    val spec =
        SyntheticSets.RepSpec(
            eccS = tempo?.downS?.coerceAtLeast(MIN_DEMO_ECC_S) ?: DEFAULT_DEMO_ECC_S,
            bottomPauseS = (tempo?.bottomPauseS ?: MIN_DEMO_BOTTOM_PAUSE_S).coerceAtLeast(MIN_DEMO_BOTTOM_PAUSE_S),
            conS = tempo?.upS ?: DEFAULT_DEMO_CON_S,
            topPauseS = (tempo?.topPauseS ?: MIN_DEMO_TOP_PAUSE_S).coerceAtLeast(MIN_DEMO_TOP_PAUSE_S),
            romM = DEMO_ROM_M,
        )
    val samples = SyntheticSets.generate(List(reps) { spec }, eccentricFirst = eccentricFirst)
    val epoch = System.currentTimeMillis()
    for (sample in samples) {
        onSample(sample.copy(timestampMs = epoch + sample.timestampMs))
        delay(DEMO_SAMPLE_INTERVAL_MS)
    }
}

private const val DEMO_LEAD_IN_MS = 1_500L
private const val DEMO_SAMPLE_INTERVAL_MS = 5L
private const val MIN_DEMO_ECC_S = 0.5
private const val DEFAULT_DEMO_ECC_S = 2.0
private const val MIN_DEMO_BOTTOM_PAUSE_S = 0.3
private const val DEFAULT_DEMO_CON_S = 1.0
private const val MIN_DEMO_TOP_PAUSE_S = 0.8
private const val DEMO_ROM_M = 0.55
