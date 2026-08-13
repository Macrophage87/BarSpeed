package com.macrophage.barspeed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
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
    private val bodyWeightKgKey = doublePreferencesKey("body_weight_kg")

    val weightUnit: Flow<WeightUnit> =
        context.settingsDataStore.data.map { prefs ->
            prefs[weightUnitKey]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KG
        }

    /**
     * Lifter body weight, kg. The base load for pull-ups, dips and other
     * bodyweight work, where the plan's load is what was ADDED (or, when
     * negative, assisted away). Null until set.
     */
    val bodyWeightKg: Flow<Double?> =
        context.settingsDataStore.data.map { prefs -> prefs[bodyWeightKgKey]?.takeIf { it > 0 } }

    /** Voice counting during sets (tempo count-up) and rest countdown. On by default. */
    val audioCues: Flow<Boolean> =
        context.settingsDataStore.data.map { prefs -> prefs[audioCuesKey] ?: true }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.settingsDataStore.edit { it[weightUnitKey] = unit.name }
    }

    suspend fun setAudioCues(enabled: Boolean) {
        context.settingsDataStore.edit { it[audioCuesKey] = enabled }
    }

    suspend fun setBodyWeightKg(kg: Double) {
        context.settingsDataStore.edit { it[bodyWeightKgKey] = kg }
    }
}
