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
