package com.macrophage.barspeed.model

/** What to put on each side of the bar for a given total load. */
data class PlateBreakdown(
    /** Bar weight in DISPLAY units (45 lb or 20 kg). */
    val barWeight: Double,
    /** Plates per side in display units, heaviest first. */
    val platesPerSide: List<Double>,
    /** Remainder per side that available plates can't cover (display units). */
    val leftoverPerSide: Double,
    /** Total is less than the bar itself. */
    val belowBar: Boolean,
)

/** Standard plate loading math for barbell lifts. */
object PlateMath {
    private const val LB_BAR = 45.0
    private val LB_PLATES = listOf(45.0, 25.0, 10.0, 5.0, 2.5)

    private const val KG_BAR = 20.0
    private val KG_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    private const val EPSILON = 0.05

    /** The bar this unit assumes when the plan declares none: 45 lb or 20 kg. */
    fun defaultBar(unit: WeightUnit): Double = if (unit == WeightUnit.KG) KG_BAR else LB_BAR

    /**
     * Break a total load (kg canonical) into per-side plates in the display
     * unit.
     *
     * [barKgOverride] is the plan's own bar for this exercise -- a 35 lb bar,
     * a trap bar, a fixed bar -- in kilograms, the canonical unit everything
     * else is stored in; null takes [defaultBar].
     *
     * ROUNDED to a hundredth of the display unit, because the bar is printed.
     * The case that needs it is a bar declared in one unit and READ in the
     * other, where there is no round trip to survive: `"bar_kg": 15` on a
     * pounds screen is 33.0693339327 lb, and that is the whole string beside
     * the plates without this. A bar declared in the unit it is read in
     * usually survives the trip through kilograms untouched -- but not always,
     * and the exceptions are ordinary weights: 49.25 lb returns
     * 49.25000000000001. An earlier version of this KDoc said a 35 lb bar
     * returns 34.99999999996; that was not measured and is not true.
     */
    fun perSide(totalKg: Double, unit: WeightUnit, barKgOverride: Double? = null): PlateBreakdown {
        val bar =
            barKgOverride
                ?.let { Math.round(unit.fromKg(it) * 100.0) / 100.0 }
                ?: defaultBar(unit)
        val plates = if (unit == WeightUnit.KG) KG_PLATES else LB_PLATES
        val total = unit.fromKg(totalKg)
        if (total < bar - EPSILON) {
            return PlateBreakdown(bar, emptyList(), 0.0, belowBar = true)
        }
        var perSide = (total - bar) / 2.0
        val chosen = mutableListOf<Double>()
        for (plate in plates) {
            while (perSide >= plate - EPSILON) {
                chosen += plate
                perSide -= plate
            }
        }
        val leftover = if (perSide < EPSILON) 0.0 else Math.round(perSide * 100.0) / 100.0
        return PlateBreakdown(bar, chosen, leftover, belowBar = false)
    }
}
