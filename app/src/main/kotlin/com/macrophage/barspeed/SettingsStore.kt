package com.macrophage.barspeed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrophage.barspeed.model.LeadInPolicy
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

    /**
     * The lifter's prep adjustments, exercise id to seconds, as one map rather
     * than a flow per exercise.
     *
     * DataStore rather than a Room column because `romanian_deadlift` is a
     * SEEDED exercise with no `custom_exercises` row, so a column would cover
     * only the ids the lifter had invented -- which is the wrong half. Keyed by
     * exercise id, which is what makes an ad-hoc set of a planned exercise pick
     * up the same adjustment for free.
     *
     * One flow over the whole preference map, not one keyed lookup per exercise:
     * the key changes with every set, and a flow whose key changes is a
     * subscription that has to be torn down and rebuilt at exactly the moments
     * the record flow is busiest.
     *
     * Clamped on the way OUT as well as on the way in. A value written by
     * another build, or by a control added later, has been seen by no validator,
     * and `LeadInPlan.of` throws on a negative from inside `:app` where nothing
     * catches it.
     */
    val prepOverrides: Flow<Map<String, Int>> =
        context.settingsDataStore.data.map { prefs ->
            prefs.asMap()
                .mapNotNull { (key, value) ->
                    val id = key.name.removePrefix(PREP_KEY_PREFIX)
                    if (id == key.name || value !is Int) null else id to LeadInPolicy.clamp(value)
                }
                .toMap()
        }

    suspend fun setPrepS(exerciseId: String, seconds: Int) {
        context.settingsDataStore.edit {
            it[intPreferencesKey("$PREP_KEY_PREFIX$exerciseId")] = LeadInPolicy.clamp(seconds)
        }
    }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.settingsDataStore.edit { it[weightUnitKey] = unit.name }
    }

    suspend fun setAudioCues(enabled: Boolean) {
        context.settingsDataStore.edit { it[audioCuesKey] = enabled }
    }

    suspend fun setBodyWeightKg(kg: Double) {
        context.settingsDataStore.edit { it[bodyWeightKgKey] = kg }
    }

    private companion object {
        /**
         * `weight_unit`, `audio_cues` and `body_weight_kg` are the other keys in
         * this store and none of them begins with it.
         */
        const val PREP_KEY_PREFIX = "prep_s_"
    }
}
