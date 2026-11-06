package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.SetLoadPolicy

/**
 * Whether an ad-hoc set must not start on the load box as it stands (UX review
 * 2026-11-06, record-setup F4, core-model F3): [SetLoadPolicy.loadEntryAccepted]
 * over this state's own box, unit and movement. False on a plan set, whose box
 * is behind the change dialog and is refused there instead.
 *
 * One projection, asked by `RecordViewModel.beginSet` (the gate every START
 * reaches) and by the screen (START disabled, "Enter a load" under the box),
 * so the button and the gate cannot disagree. Its own file rather than inside
 * `RecordViewModel.kt`, for the `LargeClass` reason that file's free functions
 * give.
 */
internal fun RecordState.adHocLoadRefused(): Boolean =
    adHoc &&
        !SetLoadPolicy.loadEntryAccepted(
            adHoc = true,
            typedText = loadInput,
            unit = weightUnit,
            bodyweight = currentExercise.bodyweight,
        )
