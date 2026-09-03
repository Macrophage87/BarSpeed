package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.WeightUnit

/**
 * What the record screen's state becomes when the display unit changes.
 *
 * A SEAM, extracted from `RecordViewModel`'s `container.settings.weightUnit`
 * collector, where the body was `copy(weightUnit = unit)` and nothing else.
 * That collector is the only place the new unit is observed and therefore the
 * only place that can convert the load field: `toggleWeightUnit` writes the
 * setting and returns, and the unit comes back through DataStore. It is also
 * the single point BOTH chips reach -- `RecordScreen`'s "Units:" chip and
 * `HomeScreen`'s "kg -> lb" button both call a `toggleWeightUnit` that writes
 * `container.settings`, and `HomeViewModel` has no load field of its own to
 * convert. Fixing the observer fixes both taps; fixing either toggle would fix
 * one (#77).
 *
 * Lifted out of the collector because nothing in `RecordViewModel`'s `init`
 * can be reached by a test. This function is a plain function over
 * [RecordState] in the same arrangement `appendedState` uses, so `:app`'s test
 * source set can name it.
 */
internal fun unitChangedState(s: RecordState, unit: WeightUnit): RecordState {
    val converted = SetLoadPolicy.convertedLoad(s.loadInput, s.weightUnit, unit)
    return s.copy(weightUnit = unit, loadInput = converted.text)
}
