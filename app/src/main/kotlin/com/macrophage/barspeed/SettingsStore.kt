package com.macrophage.barspeed

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val weightUnitKey = stringPreferencesKey("weight_unit")
    private val audioCuesKey = booleanPreferencesKey("audio_cues")
    private val bodyWeightKgKey = doublePreferencesKey("body_weight_kg")
    private val bodyWeightSetAtKey = longPreferencesKey("body_weight_set_at_ms")

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

    /**
     * When [bodyWeightKg] was last written, epoch millis, or null when the
     * app does not know (issue #181).
     *
     * Null is a REAL state and not an error: every build up to 0.1.44 stored
     * the weight and no date, so an upgrading lifter has a number whose age is
     * unknowable. `BodyWeightPromptPolicy.stateOf` gives that its own case;
     * nothing here guesses an age for it.
     *
     * Not filtered on the way out beyond nullability. A zero or a negative
     * cannot be written by [setBodyWeightKg], and the policy treats either as
     * the absence of a time rather than as a moment in 1970.
     */
    val bodyWeightSetAtMs: Flow<Long?> =
        context.settingsDataStore.data.map { prefs -> prefs[bodyWeightSetAtKey] }

    /**
     * Everything the app says that the lifter did not prescribe: the tempo
     * count-up, the rep announcements, a hold or a carry's whole voice -- its
     * prep included -- and the rest countdown. On by default.
     *
     * It does NOT silence a voice-guided cadence or the prep before one.
     * Prescribing a tempo is asking to be paced; see `LeadInPolicy.speaks`,
     * which is where that split is decided.
     */
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

    /**
     * Which accelerometer is which, by device address, issue #156.
     *
     * Here rather than in `DeviceRegistry` for two concrete reasons, both
     * about that class rather than about this one: its `keyFor` is a binary
     * `if` that maps any role other than IMU onto the heart-rate strap's
     * preferred key, and `KnownDevice` puts the role enum on the wire, so a
     * build meeting a value it has never seen throws inside a decode whose
     * catch returns an EMPTY list. Keeping the label out of that document
     * leaves the paired-device list byte-compatible in both directions.
     *
     * Keyed by MAC and not by position. `DeviceRegistry.pair` makes every
     * newly paired device its role's preferred address, so anything derived
     * from "which one is preferred" changes meaning the next time either unit
     * is re-paired -- and every capture either side of that moment would be
     * labelled consistently and wrongly. An address is the only thing about a
     * WT901 that survives a power cycle.
     *
     * One flow over the whole preference map, and validated on the way OUT,
     * both for [prepOverrides]' reasons: the key set changes whenever a device
     * is paired or forgotten, and a value written by a later build has been
     * seen by no validator here. An unrecognised role is DROPPED rather than
     * defaulted -- `SensorCapturePolicy.roleFromWire` answers null, and a
     * default would relabel the other unit's stream.
     */
    val sensorRoles: Flow<Map<String, SensorRole>> =
        context.settingsDataStore.data.map { prefs ->
            prefs.asMap()
                .mapNotNull { (key, value) ->
                    val address = key.name.removePrefix(SENSOR_ROLE_KEY_PREFIX)
                    if (address == key.name || value !is String) {
                        null
                    } else {
                        SensorCapturePolicy.roleFromWire(value)?.let { address to it }
                    }
                }
                .toMap()
        }

    /**
     * How many accelerometers the lifter has chosen for an exercise, where
     * they have chosen anything.
     *
     * The plan DECLARES the count and this is the adjustment on top of it, the
     * same two-layer shape prep uses; [SensorCapturePolicy.resolve] is where
     * the precedence lives and this map is only its second argument. Absent
     * means no adjustment, which is a different fact from a stored 1 -- a
     * stored 1 on an exercise a plan declares as 2 is the lifter turning dual
     * off, and the export publishes both figures.
     *
     * Keyed by exercise id, so an ad-hoc set of a planned exercise picks up the
     * same choice for free, exactly as [prepOverrides] does.
     */
    val sensorCounts: Flow<Map<String, Int>> =
        context.settingsDataStore.data.map { prefs ->
            prefs.asMap()
                .mapNotNull { (key, value) ->
                    val id = key.name.removePrefix(SENSOR_COUNT_KEY_PREFIX)
                    if (id == key.name || value !is Int) null else id to SensorCapturePolicy.clamp(value)
                }
                .toMap()
        }

    /** Label a device, or clear its label when [role] is null. */
    suspend fun setSensorRole(address: String, role: SensorRole?) {
        val key = stringPreferencesKey("$SENSOR_ROLE_KEY_PREFIX$address")
        context.settingsDataStore.edit { prefs ->
            if (role == null) prefs.remove(key) else prefs[key] = SensorCapturePolicy.wireOf(role)
        }
    }

    suspend fun setSensorCount(exerciseId: String, count: Int) {
        context.settingsDataStore.edit {
            it[intPreferencesKey("$SENSOR_COUNT_KEY_PREFIX$exerciseId")] = SensorCapturePolicy.clamp(count)
        }
    }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.settingsDataStore.edit { it[weightUnitKey] = unit.name }
    }

    suspend fun setAudioCues(enabled: Boolean) {
        context.settingsDataStore.edit { it[audioCuesKey] = enabled }
    }

    /**
     * Write the body weight AND the moment it was written, in one edit.
     *
     * The clock is read here rather than taken as a parameter, and that is the
     * whole design of #181's freshness rule: there are two write paths -- the
     * body-weight dialog and #161's plan import -- and neither can produce a
     * dated-fresh value without also producing a date, because neither is
     * given the chance to omit one. A parameter, even a defaulted one, is a
     * way for a third write path added later to store a value the staleness
     * rule then reads as UNKNOWN_AGE forever.
     *
     * One `edit` block, so the two keys move together. Two edits could be
     * interrupted between them and leave a new weight carrying the old date,
     * which is the one combination that would silence the prompt wrongly.
     */
    suspend fun setBodyWeightKg(kg: Double) {
        val nowMs = System.currentTimeMillis()
        context.settingsDataStore.edit {
            it[bodyWeightKgKey] = kg
            it[bodyWeightSetAtKey] = nowMs
        }
    }

    private companion object {
        /**
         * `weight_unit`, `audio_cues`, `body_weight_kg` and
         * `body_weight_set_at_ms` are the other keys in this store and none of
         * them begins with [PREP_KEY_PREFIX].
         */
        const val PREP_KEY_PREFIX = "prep_s_"

        /**
         * Neither of these is a prefix of the other, nor of [PREP_KEY_PREFIX],
         * nor of any scalar key in this store -- which is what the two
         * prefix-scanning flows above rely on. `sensor_role_` holds a role per
         * device ADDRESS and `sensors_` a count per EXERCISE ID; a shared
         * prefix would have each flow reading the other's rows.
         */
        const val SENSOR_ROLE_KEY_PREFIX = "sensor_role_"
        const val SENSOR_COUNT_KEY_PREFIX = "sensors_"
    }
}
