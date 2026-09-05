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
 * load. `PlansViewModel`'s unit does reach text a lifter is mid-typing:
 * `PlansScreen.kt:57` collects it and passes it at `:149` to
 * `BodyWeightDialog`, whose field is `remember`-seeded once from
 * `unit.inputValue` and saved back through `unit.parseToKg`
 * (`BodyWeightDialog.kt:39`, `:60`). `RecordScreen`'s own
 * `BodyWeightPromptDialog` does the same with `rememberSaveable`
 * (`RecordScreen.kt:472`, parsed back at `:503`). Both are modal and no unit
 * toggle is reachable while either is on screen, so neither is fixed here and
 * neither is claimed to be; that reachability was read from source, not
 * observed on a device. The other three format an already-recorded `loadKg`
 * or a derived volume. `RecordScreen`'s "Units:" chip and `HomeScreen`'s
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
 * the debug APK of `Convert the typed load when the kg/lb chip is tapped`:
 * typed 100 with the kg chip on the ad-hoc form, tapped the chip, and the
 * field re-rendered as `Load (lb)` 220.5. The set recorded 100.017117587213
 * in `set_records.loadKg` -- read with sqlite3 on the device -- against 45.36
 * before the fix, and the rest screen showed it back as "Load recorded 100
 * kg". Tapping the chip a second time returned the field to `Load (kg)` 100.
 * That is bench evidence at one commit, not a gate: nothing re-runs it.
 *
 * The SHA that produced that bench build IS NOT AN ANCESTOR OF THIS BRANCH --
 * it has been rebased twice since that run, most recently onto `Cut version
 * 0.1.50` -- so a SHA written here would already be dead. Naming the commit
 * by its subject instead survives the rebase: this file and
 * `SetLoadPolicy.kt` were byte-identical between the bench run and the commit
 * now on this branch titled `Convert the typed load when the kg/lb chip is
 * tapped`, measured by `git diff --stat` between the two SHAs over the two
 * paths, and empty. The same diff, restricted to `app/`, names five other
 * files -- `app/build.gradle.kts`, `GuideScreen`, `HomeViewModel`,
 * `SessionDetailScreen`, `SessionDetailViewModel` -- none on the load path.
 */
internal fun unitChangedState(s: RecordState, unit: WeightUnit): RecordState {
    val converted = SetLoadPolicy.convertedLoad(s.loadInput, s.weightUnit, unit)
    return s.copy(weightUnit = unit, loadInput = converted.text)
}
