package com.macrophage.barspeed.model

/**
 * What the voice says about the next set when the rest runs out (UX review
 * 2026-11-06, voice F4).
 *
 * Until this, the rest ended on "3, 2, 1, Rest over" and nothing else, so on
 * an exercise or load change the lifter had to walk back to the phone and read
 * "Up next" before loading the bar. The line is built from the same figures the
 * "Up next" card draws -- the slot's values with the lifter's standing
 * statements laid over them -- and kept short: the exercise, the arm when the
 * set works one, the load in the lifter's unit, then the reps or the seconds.
 *
 *  - "Next: Bench press, 100 kilos, 5"
 *  - "Next: Pull-up, body weight plus 10 kilos, 6"
 *  - "Next: Single-arm row, left, 20 kilos, 8"
 *  - "Next: Plank, body weight, 60 seconds"
 *
 * Spoken outside any set, as part of the same utterance as "Rest over" (the
 * voice speaks with QUEUE_FLUSH, so a second utterance would cut the first
 * off), and never written to a cue track.
 */
object NextSetCall {
    /** The word the rest has always ended on. */
    const val REST_OVER = "Rest over"

    /**
     * The next-set line, or null when [exerciseName] is blank.
     *
     * [loadKg] is the ADDED load on body-weight work ([bodyweight] true), as
     * everywhere else; null or a rendered zero there is "body weight". On
     * loaded work a missing or non-positive load says nothing about the load,
     * except on a timed set, which the lifter's own body loads ("body
     * weight", the card's rule). [timed] picks [durationS] over [reps].
     */
    @Suppress("LongParameterList")
    fun phrase(
        exerciseName: String,
        bodyweight: Boolean,
        timed: Boolean,
        unit: WeightUnit,
        loadKg: Double?,
        reps: Int?,
        durationS: Int?,
        side: String?,
    ): String? {
        if (exerciseName.isBlank()) return null
        val load = when {
            bodyweight -> bodyweightLoad(loadKg, unit)
            loadKg != null && loadKg > 0 && amount(loadKg, unit) != "0" -> amountWithUnit(loadKg, unit)
            timed -> BODY_WEIGHT
            else -> null
        }
        val work = if (timed) durationS?.let { "$it seconds" } else reps?.toString()
        val parts = listOfNotNull(exerciseName.trim(), side?.takeIf { it.isNotBlank() }?.lowercase(), load, work)
        return "Next: " + parts.joinToString(", ")
    }

    /** What the countdown says at zero: [REST_OVER], then [next] when there is a next set. */
    fun restOver(next: String?): String = if (next == null) REST_OVER else "$REST_OVER. $next"

    private const val BODY_WEIGHT = "body weight"

    private fun bodyweightLoad(addedKg: Double?, unit: WeightUnit): String {
        val kg = addedKg ?: 0.0
        if (amount(Math.abs(kg), unit) == "0") return BODY_WEIGHT
        val sign = if (kg > 0) "plus" else "minus"
        return "$BODY_WEIGHT $sign ${amountWithUnit(Math.abs(kg), unit)}"
    }

    /** The figure as the load box shows it: a tenth of the unit, no trailing ".0". */
    private fun amount(kg: Double, unit: WeightUnit): String = unit.inputValue(kg)

    private fun amountWithUnit(kg: Double, unit: WeightUnit): String {
        val figure = amount(kg, unit)
        val word = when (unit) {
            WeightUnit.KG -> if (figure == "1") "kilo" else "kilos"
            WeightUnit.LB -> if (figure == "1") "pound" else "pounds"
        }
        return "$figure $word"
    }
}
