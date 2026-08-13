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
