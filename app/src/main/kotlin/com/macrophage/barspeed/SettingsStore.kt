package com.macrophage.barspeed

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val weightUnitKey = stringPreferencesKey("weight_unit")

    val weightUnit: Flow<WeightUnit> =
        context.settingsDataStore.data.map { prefs ->
            prefs[weightUnitKey]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KG
        }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.settingsDataStore.edit { it[weightUnitKey] = unit.name }
    }
}
