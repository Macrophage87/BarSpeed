package com.macrophage.barspeed.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.VoiceHealth
import com.macrophage.barspeed.model.VoiceStatus
import com.macrophage.barspeed.ui.BarColors

/**
 * One amber line when the voice cannot be heard (UX review 2026-11-06, voice
 * F1 and F3). Draws nothing when [VoiceHealth.warning] says nothing, so the
 * Record screen can call it unconditionally. A broken engine opens the phone's
 * text-to-speech settings on tap; a muted media stream does nothing on tap,
 * since the volume keys are the fix.
 *
 * [voiceWanted] is whether anything would speak: the cues are on, or a tempo
 * guide runs, which speaks whatever the toggle says.
 */
@Composable
fun VoiceWarningBanner(status: VoiceStatus, mediaVolumeZero: Boolean, voiceWanted: Boolean) {
    val warning = VoiceHealth.warning(status, mediaVolumeZero, voiceWanted) ?: return
    val context = LocalContext.current
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(enabled = warning.opensSpeechSettings) { openSpeechSettings(context) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(warning.text, style = MaterialTheme.typography.titleSmall, color = BarColors.Amber)
            if (warning.opensSpeechSettings) {
                Text("Tap to open speech settings", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
        }
    }
}

/**
 * The text-to-speech settings page. Its action is not public API, so the
 * phone's main settings are the fallback where an OEM build lacks it.
 */
private fun openSpeechSettings(context: Context) {
    val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tts)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
