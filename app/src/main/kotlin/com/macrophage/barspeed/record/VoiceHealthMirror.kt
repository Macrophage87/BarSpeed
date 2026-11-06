package com.macrophage.barspeed.record

import com.macrophage.barspeed.VoiceCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mirror the voice's health onto the Record state (UX review 2026-11-06,
 * voice F1 and F3). A free function, as the other mirrors in
 * RecordViewModel.kt are, so it adds no lines to the class detekt's
 * `LargeClass` counts. Compile- and lint-gated only: no test reaches a
 * `VoiceCounter`.
 */
internal fun CoroutineScope.mirrorVoiceHealth(voice: VoiceCounter?, state: MutableStateFlow<RecordState>) = launch {
    if (voice == null) return@launch
    voice.status.combine(voice.mediaVolumeZero) { status, zero -> status to zero }
        .collect { (status, zero) -> state.value = state.value.copy(voiceStatus = status, mediaVolumeZero = zero) }
}
