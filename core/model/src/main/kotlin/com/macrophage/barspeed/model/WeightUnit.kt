package com.macrophage.barspeed.model

import java.util.Locale

/**
 * Display unit for loads. Storage and the JSON schemas are always kilograms
 * (load_kg); conversion happens at the UI boundary only.
 */
enum class WeightUnit(val suffix: String) {
    KG("kg"),
    LB("lb"),
    ;

    fun fromKg(kg: Double): Double = if (this == KG) kg else kg * LB_PER_KG

    fun toKg(value: Double): Double = if (this == KG) value else value / LB_PER_KG

    /** e.g. 120.0 -> "120 kg" or "264.5 lb"; drops trailing .0 */
    fun format(kg: Double): String {
        val value = fromKg(kg)
        val rounded = Math.round(value * 10.0) / 10.0
        val text =
            if (rounded == Math.floor(rounded)) {
                rounded.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", rounded)
            }
        return "$text $suffix"
    }

    /** Parse user input given in this unit, returning kilograms. */
    fun parseToKg(text: String): Double? = text.trim().toDoubleOrNull()?.let { toKg(it) }

    /** Numeric value for pre-filling input fields, rounded to 0.1. */
    fun inputValue(kg: Double): String {
        val rounded = Math.round(fromKg(kg) * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }

    fun other(): WeightUnit = if (this == KG) LB else KG

    companion object {
        const val LB_PER_KG = 2.2046226218
    }
}
