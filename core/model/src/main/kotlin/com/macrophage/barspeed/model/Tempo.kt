package com.macrophage.barspeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import kotlin.math.abs

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
 * A null stroke means "X" (explosive: as fast as possible). [upS] has always
 * been able to hold one; [downS] is nullable from #258, so that a lift whose
 * drive moves DOWN -- a pushdown, a pulldown, a leg curl -- has a digit in
 * which to declare an explosive drive, and [parse] accepts an `X` there from
 * plan schema 1.15. The notation lets either stroke be `X` and no pause; which
 * stroke an `X` is honoured on is the lift's business, read by role in
 * `TempoAdjustPolicy`, `TempoSchedule` and [VelocityLossRegime].
 *
 * Accepts `"4010"`, `"40X0"`, `"X010"`, and dash-separated `"4-0-1-0"` forms.
 */
@Serializable
data class Tempo(
    /**
     * Digit 1 — the downward stroke; null for "X". Serialized under its
     * historical name.
     *
     * Nullable from #258. A stored analysis written before that carries a
     * number here, which decodes into a `Double?` unchanged, so no row
     * already on a device reads differently.
     */
    @SerialName("eccentricS") val downS: Double?,
    /** Digit 2 — pause at the bottom of the movement. */
    val bottomPauseS: Double,
    /** Digit 3 — the upward stroke; null for "X". Serialized under its historical name. */
    @SerialName("concentricS") val upS: Double?,
    /** Digit 4 — pause at the top of the movement. */
    val topPauseS: Double,
) {
    val isExplosiveUpStroke: Boolean get() = upS == null

    /**
     * Digit 1 is `X`. [isExplosiveUpStroke]'s sibling rather than a
     * generalisation of it, because every reader of that one asks about the
     * UP stroke specifically -- the prep countdown's word and the guide's
     * `Drive` call for a drive-up lift -- and a merged "either stroke is X"
     * would answer a different question at each of them. #258.
     */
    val isExplosiveDownStroke: Boolean get() = downS == null

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

    /**
     * The tempo as [parse] reads it back: the compact `"30X1"` when every
     * component is a whole 0-9 or X, otherwise the dash form `"3-0-1.5-0"`,
     * because the compact form reads one character per component. Exported
     * as `tempoCompliance.prescribed`, so it must round-trip (core-model
     * review 2026-11-06, F5; pinned over a grid in `TempoTest`).
     */
    fun notation(): String {
        val components = listOf(downS, bottomPauseS, upS, topPauseS)
        val compact = components.all { it == null || isSingleDigit(it) }
        val written = components.map { it?.let(::formatComponent) ?: "X" }
        return if (compact) written.joinToString("") else written.joinToString("-")
    }

    private fun isSingleDigit(value: Double): Boolean = value == Math.floor(value) && value in 0.0..9.0

    private fun formatComponent(value: Double): String = if (value == Math.floor(value) && abs(value) < 1e15) {
        value.toLong().toString()
    } else if (value.isFinite()) {
        // Plain decimal digits: Double.toString would write 1.0E-5, and
        // the dash in its exponent would split it in parse.
        BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    } else {
        value.toString()
    }

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
            val down =
                if (parts[0] == "X") null else parts[0].toDoubleOrNull() ?: error("Invalid down stroke in '$text'")
            val bottom = parts[1].toDoubleOrNull() ?: error("Invalid bottom-pause component in '$text'")
            val up = if (parts[2] == "X") null else parts[2].toDoubleOrNull() ?: error("Invalid up stroke in '$text'")
            val top = parts[3].toDoubleOrNull() ?: error("Invalid top-pause component in '$text'")
            return Tempo(down, bottom, up, top)
        }

        fun parseOrNull(text: String): Tempo? = runCatching { parse(text) }.getOrNull()
    }
}
