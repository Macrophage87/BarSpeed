package com.macrophage.barspeed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val weightUnitKey = stringPreferencesKey("weight_unit")
    private val audioCuesKey = booleanPreferencesKey("audio_cues")

    val weightUnit: Flow<WeightUnit> =
        context.settingsDataStore.data.map { prefs ->
            prefs[weightUnitKey]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KG
        }

    /** Voice counting during sets (tempo count-up) and rest countdown. Off by default. */
    val audioCues: Flow<Boolean> =
        context.settingsDataStore.data.map { prefs -> prefs[audioCuesKey] ?: false }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.settingsDataStore.edit { it[weightUnitKey] = unit.name }
    }

    suspend fun setAudioCues(enabled: Boolean) {
        context.settingsDataStore.edit { it[audioCuesKey] = enabled }
    }
}
