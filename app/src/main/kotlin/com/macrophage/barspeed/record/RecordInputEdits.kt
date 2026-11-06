package com.macrophage.barspeed.record

/*
 * The state a keystroke in a next-set box writes. Moved verbatim out of
 * RecordViewModel's updateLoadInput, updateRepsInput and updateDurationInput,
 * for detekt's `LargeClass` (600 counted lines) on that class; the rules are
 * unchanged. The text and its statement are written together here and nowhere
 * else, and a box that does not parse states nothing rather than zero.
 */

internal fun RecordState.withLoadInput(text: String): RecordState =
    copy(loadInput = text, statedLoadKg = weightUnit.parseToKg(text))

internal fun RecordState.withRepsInput(text: String): RecordState =
    copy(repsInput = text, statedReps = text.trim().toIntOrNull())

internal fun RecordState.withDurationInput(text: String): RecordState =
    copy(durationInput = text, statedDurationS = text.trim().toIntOrNull())
