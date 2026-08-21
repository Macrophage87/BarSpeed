package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a load looked like on screen, and what a plan resolved it to, BEFORE
 * anything about implement counts existed.
 *
 * Written first so the dumbbell-display change has something to be measured
 * against rather than described against. Every value here is one the change
 * either divides, renders, or promises not to touch:
 *
 *  - 36.28738960080283 kg is 80 lb, the owner's own example ("2x40 lb
 *    dumbells, but track that as 80"), and 18.143694800401414 kg is its half.
 *  - 24.94758035055195 kg is 55 lb and 79.3786647517562 kg is 175 lb, the
 *    latter being the load the shipped example authors in pounds and the one
 *    #45 was found on.
 *  - 0.0 and -20.0 are the two loads that must go on rendering as nothing and
 *    as a negative: a set with no added load, and the band-assisted pull-up
 *    the shipped example carries.
 *
 * The expected strings were MEASURED against the real [WeightUnit.format], not
 * reasoned about: the rounding is `Math.round(value * 10) / 10` applied AFTER
 * the unit conversion, so which figures survive a halving is a property of the
 * display unit and not of the arithmetic.
 */
class LoadDisplayCharacterizationTest {
    private val eightyLbInKg = 80.0 / WeightUnit.LB_PER_KG
    private val fiftyFiveLbInKg = 55.0 / WeightUnit.LB_PER_KG
    private val oneSevenFiveLbInKg = 175.0 / WeightUnit.LB_PER_KG

    @Test
    fun `the owner's own example converts to the kilograms the app stores`() {
        assertEquals(36.28738960080283, eightyLbInKg)
        assertEquals(24.94758035055195, fiftyFiveLbInKg)
        assertEquals(79.3786647517562, oneSevenFiveLbInKg)
    }

    @Test
    fun `a total and its half render as these exact strings today`() {
        assertEquals("80 lb", WeightUnit.LB.format(eightyLbInKg))
        assertEquals("40 lb", WeightUnit.LB.format(eightyLbInKg / 2.0))
        assertEquals("36.3 kg", WeightUnit.KG.format(eightyLbInKg))
        assertEquals("18.1 kg", WeightUnit.KG.format(eightyLbInKg / 2.0))
    }

    /**
     * The artefact the change has to live with, recorded before it has one:
     * a half is rounded to a tenth of the DISPLAY unit after conversion, so
     * twice the rendered half need not read back as the rendered total.
     * "36.3 kg" halves to "18.1 kg", and 18.1 + 18.1 is 36.2.
     */
    @Test
    fun `rounding a half to a display tenth does not always double back`() {
        assertEquals("36.3 kg", WeightUnit.KG.format(eightyLbInKg))
        assertEquals("18.1 kg", WeightUnit.KG.format(eightyLbInKg / 2.0))
        assertEquals("81.5 lb", WeightUnit.LB.format(81.5 / WeightUnit.LB_PER_KG))
        assertEquals("40.8 lb", WeightUnit.LB.format(81.5 / WeightUnit.LB_PER_KG / 2.0))
    }

    /** Halving a double is exact, so dividing in kilograms loses nothing. */
    @Test
    fun `halving in kilograms is exact and order-free at two implements`() {
        assertEquals(eightyLbInKg, (eightyLbInKg / 2.0) * 2.0)
        assertEquals(eightyLbInKg / 2.0, 40.0 / WeightUnit.LB_PER_KG)
    }

    @Test
    fun `no load and assistance render as they do today`() {
        assertEquals("0 kg", WeightUnit.KG.format(0.0))
        assertEquals("-20 kg", WeightUnit.KG.format(-20.0))
        assertEquals("0 lb", WeightUnit.LB.format(0.0))
    }

    /**
     * All four sites that draw a load guard it with `takeIf { it > 0 }`, so a
     * loadless set and a band-assisted one are already invisible there. Stated
     * as the predicate rather than as prose, so a change to it is a diff.
     */
    @Test
    fun `the guard every load render site applies today admits only positives`() {
        listOf(0.0, -20.0).forEach {
            assertNull(it.takeIf { kg -> kg > 0 }, "$it must not reach a load render")
        }
        assertEquals(eightyLbInKg, eightyLbInKg.takeIf { it > 0 })
    }

    /**
     * The plan's own load arithmetic, pinned by EXACT double equality before
     * anything is added beside it. `resolvedLoadKg` takes no argument today,
     * and the whole safety case for implement counts is that it goes on taking
     * none.
     */
    @Test
    fun `a plan resolves the loads it declares, in pounds and in kilograms`() {
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString(
            PlanFile.serializer(),
            """
            {"schemaVersion":"1.4","planName":"P","sessions":[{"name":"S","exercises":[
              {"exercise":"dumbbell_bench_press","sets":[{"reps":10,"load_lb":80}]},
              {"exercise":"goblet_squat","sets":[{"reps":10,"load_kg":36.28738960080283}]},
              {"exercise":"push_up","sets":[{"reps":10}]}
            ]}]}
            """.trimIndent(),
        )
        val exercises = plan.sessions[0].exercises
        assertEquals(eightyLbInKg, exercises[0].sets[0].resolvedLoadKg)
        assertEquals(36.28738960080283, exercises[1].sets[0].resolvedLoadKg)
        assertNull(exercises[2].sets[0].resolvedLoadKg)
        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
    }
}
