package com.macrophage.barspeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standard 4-digit tempo prescription, each digit in seconds.
 *
 * **The digits are POSITIONAL, not phase-named**: digit 1 is the DOWN stroke,
 * digit 2 the pause at the bottom, digit 3 the UP stroke, digit 4 the pause at
 * the top. For most lifts down is the eccentric, but on a leg curl or a lat
 * pulldown the concentric moves down — see [ExerciseDef.concentricUp]. Which
 * digit is the eccentric therefore depends on the exercise, and the mapping
 * lives in [eccentricS] / [concentricS] rather than in the digits.
 *
 * A null up stroke means "X" (explosive: as fast as possible).
 *
 * Accepts `"4010"`, `"40X0"`, and dash-separated `"4-0-1-0"` forms.
 */
@Serializable
data class Tempo(
    /** Digit 1 — the downward stroke. Serialized under its historical name. */
    @SerialName("eccentricS") val downS: Double,
    /** Digit 2 — pause at the bottom of the movement. */
    val bottomPauseS: Double,
    /** Digit 3 — the upward stroke; null for "X". Serialized under its historical name. */
    @SerialName("concentricS") val upS: Double?,
    /** Digit 4 — pause at the top of the movement. */
    val topPauseS: Double,
) {
    val isExplosiveUpStroke: Boolean get() = upS == null

    /**
     * Either stroke is prescribed as no time at all.
     *
     * A stroke takes time. The two PAUSES may be 0 -- that is the pause where
     * the lifter does not stop, and the metronome plays it by emitting no beat
     * -- but a 0 stroke is not a tempo anyone can perform, and the app does not
     * even try: `CadencePlan.of` floors every stroke at one second because the
     * runner can only sleep in whole ones, while `SetAnalyzer.complianceFor`
     * goes on grading the lifter against the 0. So the voice plays a
     * one-second stroke and the score marks it late, on every rep, for a
     * prescription the lifter followed exactly.
     *
     * Read by [PlanSetDef.validate], which refuses such a plan by path under
     * plan schema 1.12 (#251). NOT read by [parse], deliberately: a set already
     * recorded with a zero stroke must still re-parse, or its export and its
     * history card lose their tempo entirely.
     *
     * An explosive stroke is not a zero one. `null` is "as fast as possible",
     * which is a real instruction; 0 is "in no time", which is not.
     */
    val hasZeroStroke: Boolean get() = downS == 0.0 || upS == 0.0

    fun notation(): String {
        val up = upS?.let { formatDigit(it) } ?: "X"
        return "${formatDigit(downS)}${formatDigit(bottomPauseS)}$up${formatDigit(topPauseS)}"
    }

    private fun formatDigit(value: Double): String =
        if (value == Math.floor(value) && value < 10) value.toInt().toString() else value.toString()

    companion object {
        fun parse(text: String): Tempo {
            val cleaned = text.trim().uppercase()
            val parts: List<String> =
                if (cleaned.contains('-')) {
                    cleaned.split('-')
                } else {
                    cleaned.map { it.toString() }
                }
            require(parts.size == 4) { "Tempo must have 4 components, got '$text'" }
            val down = parts[0].toDoubleOrNull() ?: error("Invalid down-stroke component in '$text'")
            val bottom = parts[1].toDoubleOrNull() ?: error("Invalid bottom-pause component in '$text'")
            val up = if (parts[2] == "X") null else parts[2].toDoubleOrNull() ?: error("Invalid up stroke in '$text'")
            val top = parts[3].toDoubleOrNull() ?: error("Invalid top-pause component in '$text'")
            return Tempo(down, bottom, up, top)
        }

        fun parseOrNull(text: String): Tempo? = runCatching { parse(text) }.getOrNull()
    }
}
