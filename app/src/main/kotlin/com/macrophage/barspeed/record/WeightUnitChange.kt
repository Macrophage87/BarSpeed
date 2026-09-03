package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.WeightUnit

/**
 * What the record screen's state becomes when the display unit changes.
 *
 * A SEAM, extracted from `RecordViewModel`'s `container.settings.weightUnit`
 * collector, where the body was `copy(weightUnit = unit)` and nothing else.
 * That collector is the only place a new unit reaches the record screen's
 * state, and therefore the only place the load field can be converted:
 * `toggleWeightUnit` writes the setting and returns, and the unit comes back
 * through DataStore. Four other view models also observe
 * `container.settings.weightUnit` -- `HomeViewModel`, `PlansViewModel`,
 * `PlanDetailViewModel` and `SessionDetailViewModel` -- and none holds a typed
 * load: they format an already-recorded `loadKg` or a derived volume, not text
 * a lifter is mid-typing. `RecordScreen`'s "Units:" chip and `HomeScreen`'s
 * "kg -> lb" button both call a `toggleWeightUnit` that writes
 * `container.settings`, and fixing this one observer fixes both taps; fixing
 * either toggle would fix only one (#77).
 *
 * Lifted out of the collector because nothing in `RecordViewModel`'s `init`
 * can be reached by a test. This function is a plain function over
 * [RecordState] in the same arrangement `appendedState` uses, so `:app`'s test
 * source set can name it.
 *
 * WHAT NO TEST HERE COVERS, and what was checked on a device instead: that the
 * Compose load field redraws from this state rather than from its own
 * remembered text. Checked on the headless `barspeed-api35` emulator against
 * the debug APK of `043e63d030facf7298af2b379a5097d7a7e21c0b`: typed 100 with
 * the kg chip on the ad-hoc form, tapped the chip, and the field re-rendered
 * as `Load (lb)` 220.5. The set recorded 100.017117587213 in `set_records`
 * .loadKg -- read with sqlite3 on the device -- against 45.36 before the fix,
 * and the rest screen showed it back as "Load recorded 100 kg". Tapping the
 * chip a second time returned the field to `Load (kg)` 100. That is bench
 * evidence at one SHA, not a gate: nothing re-runs it.
 *
 * `043e63d030facf7298af2b379a5097d7a7e21c0b` IS NOT AN ANCESTOR OF THIS
 * BRANCH -- the branch was rebased onto `e74d4e61` after this run, and
 * `git merge-base --is-ancestor 043e63d0 HEAD` fails, as it does for the red
 * CI run named in `SetLoadPolicy.kt`'s history too. The bench evidence still
 * applies: this file and `SetLoadPolicy.kt` are byte-identical across the
 * rebase (`git diff --stat 043e63d0 ce07d6a3 --
 * app/src/main/kotlin/com/macrophage/barspeed/record/WeightUnitChange.kt
 * core/model/src/main/kotlin/com/macrophage/barspeed/model/SetLoadPolicy.kt`
 * is empty), and `git diff --name-only 043e63d0 ce07d6a3` names four other
 * `app/` files that did change -- `GuideScreen`, `HomeViewModel`,
 * `SessionDetailScreen`, `SessionDetailViewModel` -- none on the load path.
 */
internal fun unitChangedState(s: RecordState, unit: WeightUnit): RecordState {
    val converted = SetLoadPolicy.convertedLoad(s.loadInput, s.weightUnit, unit)
    return s.copy(weightUnit = unit, loadInput = converted.text)
}
