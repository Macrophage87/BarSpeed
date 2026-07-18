package com.macrophage.accelerometerlifting.model

import kotlinx.serialization.Serializable

/**
 * Standard 4-digit tempo prescription: eccentric / bottom pause / concentric / top pause,
 * each in seconds. A null concentric means "X" (explosive: as fast as possible).
 *
 * Accepts `"4010"`, `"40X0"`, and dash-separated `"4-0-1-0"` forms.
 */
@Serializable
data class Tempo(
    val eccentricS: Double,
    val bottomPauseS: Double,
    val concentricS: Double?,
    val topPauseS: Double,
) {
    val isExplosiveConcentric: Boolean get() = concentricS == null

    fun notation(): String {
        val con = concentricS?.let { formatDigit(it) } ?: "X"
        return "${formatDigit(eccentricS)}${formatDigit(bottomPauseS)}$con${formatDigit(topPauseS)}"
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
            val ecc = parts[0].toDoubleOrNull() ?: error("Invalid eccentric component in '$text'")
            val bottom = parts[1].toDoubleOrNull() ?: error("Invalid bottom-pause component in '$text'")
            val con = if (parts[2] == "X") null else parts[2].toDoubleOrNull() ?: error("Invalid concentric in '$text'")
            val top = parts[3].toDoubleOrNull() ?: error("Invalid top-pause component in '$text'")
            return Tempo(ecc, bottom, con, top)
        }

        fun parseOrNull(text: String): Tempo? = runCatching { parse(text) }.getOrNull()
    }
}
