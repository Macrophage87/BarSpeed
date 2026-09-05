package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Premise pins on the PUBLISHED plan contract, not on any code path.
 *
 * `docs/schemas/` is what a plan-writing LLM is pointed at, and it is on this
 * module's test classpath because `core/model/build.gradle.kts` puts that
 * directory on the test resources source set. No Kotlin in the app reads the
 * example; its only other automated consumer is the ajv step at
 * `.github/workflows/ci.yml:42`.
 *
 * What is pinned here is that a set declaring no load at all is contract
 * rather than a malformed input, and that the shipped example runs a loaded
 * carry straight into such a set. These assert nothing about what the record
 * flow then does with it.
 */
class ShippedPlanExampleTest {
    private fun shippedExample(): PlanFile {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/examples/plan.example.json")) {
                "docs/schemas/examples/plan.example.json is not on the test classpath"
            }.bufferedReader().readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(PlanFile.serializer(), text)
    }

    /**
     * The shipped example declares an `implement` on every exercise whose card
     * has something to say, and says "other" out loud on the single-dumbbell
     * row (#253).
     *
     * The example is the only plan a generating model is shown in full, and
     * the single-dumbbell case is the one it will get wrong by default: the id
     * says dumbbell and the answer is "other", because the word means a PAIR.
     * A key demonstrated nowhere in the example is a key most generated plans
     * will omit -- and omitting this one costs the lifter the loading line
     * entirely.
     */
    @Test
    fun `the shipped example declares an implement on the lifts that have a loading`() {
        val plan = shippedExample()
        val byId = plan.sessions.flatMap { it.exercises }.associateBy { it.exercise }
        assertEquals(Implement.BARBELL, byId.getValue("back_squat").resolvedImplement)
        assertEquals(Implement.BARBELL, byId.getValue("bench_press").resolvedImplement)
        assertEquals(Implement.DUMBBELL, byId.getValue("dumbbell_bench_press").resolvedImplement)
        // One dumbbell is "other", and the example says so rather than leaving
        // the key off, which would be indistinguishable from not having
        // thought about it.
        assertEquals("other", byId.getValue("single_arm_dumbbell_row").implement)
    }

    /**
     * A declared dumbbell carries the pair without the example spelling out
     * `implementCount`, which is the whole of what the word buys an author.
     */
    @Test
    fun `the example's dumbbell press needs no count to be a pair`() {
        val press = shippedExample().sessions.flatMap { it.exercises }
            .first { it.exercise == "dumbbell_bench_press" }
        assertNull(press.implementCount, "the example still spells out a count the word already means")
        assertEquals(2, press.resolvedImplementCount)
    }

    @Test
    fun `shipped example runs a loaded carry straight into a loadless hold`() {
        val plan = shippedExample()
        val lowerA = plan.sessions.first { it.name == "Lower A" }
        val ids = lowerA.exercises.map { it.exercise }

        // Adjacency is the point: whatever the carry leaves behind is what the
        // hold is in a position to inherit.
        assertEquals(ids.indexOf("farmers_walk") + 1, ids.indexOf("plank"))

        val carry = lowerA.exercises.first { it.exercise == "farmers_walk" }
        assertEquals(listOf(48.0, 48.0), carry.sets.map { it.resolvedLoadKg })

        val hold = lowerA.exercises.first { it.exercise == "plank" }
        assertEquals(2, hold.sets.size)
        hold.sets.forEachIndexed { i, set ->
            assertNull(set.resolvedLoadKg, "plank set ${i + 1} resolves to no load")
            assertNull(set.loadKg, "plank set ${i + 1} declares no load_kg")
            assertNull(set.loadLb, "plank set ${i + 1} declares no load_lb")
        }
        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
    }

    /**
     * The shipped example exercises a prep on a hold, and the import gate is
     * quiet about it.
     *
     * Two things at once, and both are the point. A capability nothing
     * exercises is one an LLM writing a plan from this document will never use,
     * and `plank` is the case the widening was asked for. The warning check is
     * the same fact from the gate's side: before this change the example could
     * not have carried it without the app calling it inert.
     */
    @Test
    fun `the shipped example declares a prep on a hold, and the gate is quiet about it`() {
        val plan = shippedExample()
        val hold = plan.sessions.flatMap { it.exercises }.first { it.exercise == "plank" }

        assertEquals(10, hold.prepS, "the shipped example declares no prep on its hold")
        assertTrue(hold.sets.all { it.isTimed }, "the plank's sets are timed")
        assertTrue(hold.sets.all { it.tempo == null }, "a timed set cannot carry a tempo")
        assertTrue(
            plan.warnings().none { "prep_s" in it },
            "the shipped example warns about its own prep: ${plan.warnings()}",
        )
    }

    /**
     * Companion to the prep pin above, for the omission warning PlanFile
     * gained afterwards: `hanging_leg_raise`, `dumbbell_bench_press`,
     * `single_arm_dumbbell_row` and `band_assisted_pull_up` are none of them
     * built in, and every one of them prescribes reps rather than
     * duration_s, so an omitted "start" on any of them would reach
     * segmentation and draw the warning. All four declare it.
     */
    @Test
    fun `the shipped example declares start on its non-seed exercises, and the gate is quiet about it`() {
        val plan = shippedExample()
        val nonSeed = plan.sessions.flatMap { it.exercises }.filter { ExerciseDef.seedById(it.exercise) == null }

        assertTrue(nonSeed.isNotEmpty(), "expected at least one non-seed exercise in the shipped example")
        nonSeed.forEach { exercise ->
            assertTrue(
                exercise.start != null,
                "${exercise.exercise} is not built in and declares no start - it will draw the omission warning",
            )
        }
        assertTrue(
            plan.warnings().none { "does not declare \"start\"" in it },
            "the shipped example warns about its own start: ${plan.warnings()}",
        )
    }

    /**
     * DIFFERENTIAL, issue #198 round 1. The canonical 1.10 template declares
     * none of the key its own 1.10 contract tells authors to omit, and the
     * import gate is quiet about it.
     *
     * The third pin of the same shape as the two above, and the one that was
     * missing when 1.10 was minted. `back_squat` declared `"sensors": 2` and
     * its first set `"sensors": 1`, while `plan.schema.json`'s own description
     * of that key says "Leave it out of new plans" and `PLAN_PROMPT`, the copy
     * shipped to users, says "Leave the key out of new plans". Importing the
     * shipped example therefore fired `PlanFile.sensorsInert`: the canonical
     * template was a plan that warns at the app's own import gate, and the ajv
     * step -- already the weaker half of this contract -- was validating a
     * payload the app's own instructions tell an author not to write.
     *
     * The key stays ACCEPTED, so this is a statement about the example rather
     * than about the schema: a plan a coach wrote last month still imports, and
     * still draws the warning. What must not model it is the document an LLM is
     * pointed at.
     */
    @Test
    fun `the shipped example declares no sensor count, and the gate is quiet about it`() {
        val plan = shippedExample()
        val exercises = plan.sessions.flatMap { it.exercises }

        exercises.forEach { exercise ->
            assertNull(
                exercise.sensors,
                "${exercise.exercise} declares a sensor count the 1.10 contract tells authors to omit",
            )
            exercise.sets.forEachIndexed { i, set ->
                assertNull(
                    set.sensors,
                    "${exercise.exercise} set ${i + 1} declares a sensor count the contract tells authors to omit",
                )
            }
        }
        assertTrue(
            plan.warnings().none { "\"sensors\"" in it },
            "the shipped example warns about its own sensor count: ${plan.warnings()}",
        )
    }

    /**
     * The shipped example uses the split, and the gate is quiet about it.
     *
     * Same reasoning as the prep-on-a-hold pin above: a capability nothing in
     * the published example exercises is one an LLM writing a plan from that
     * document will never use. The example is also the only place a reader sees
     * how long a `description` is meant to be, so at least one has to be a real
     * cue rather than three words.
     */
    @Test
    fun `the shipped example splits a cue across the two keys, within the cap`() {
        val plan = shippedExample()
        val exercises = plan.sessions.flatMap { it.exercises }

        val described = exercises.filter { it.description != null }
        assertTrue(described.isNotEmpty(), "no exercise in the shipped example declares a description")
        described.forEach {
            val length = checkNotNull(it.description).length
            assertTrue(
                length <= PlanFile.DESCRIPTION_MAX_CHARS,
                "${it.exercise} publishes a description of $length characters, over the cap",
            )
        }
        assertTrue(
            exercises.any { it.additionalNotes != null },
            "no exercise in the shipped example declares additional_notes, so ajv never validates the key",
        )
        assertTrue(
            exercises.any { it.description != null && it.additionalNotes != null },
            "the shipped example never shows the two keys used together, which is the whole shape",
        )
        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
        assertTrue(
            plan.warnings().none { "description" in it || "additional_notes" in it },
            "the shipped example warns about its own cue: ${plan.warnings()}",
        )
    }

    @Test
    fun `the published schema permits a set with no load and says to omit both`() {
        val schema =
            Json.parseToJsonElement(
                javaClass.getResourceAsStream("/plan.schema.json")!!.readBytes().decodeToString(),
            ).jsonObject
        val set = schema["\$defs"]!!.jsonObject["set"]!!.jsonObject

        // The only `required` lists on a set are the reps/duration_s oneOf
        // branches; a load key appearing in any of them would make a bodyweight
        // set schema-invalid.
        assertFalse(set.containsKey("required"), "a set must not require any top-level key")
        val requiredKeys =
            set["oneOf"]!!.jsonArray
                .flatMap { it.jsonObject["required"]?.jsonArray.orEmpty() }
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("reps", "duration_s"), requiredKeys)

        val props = set["properties"]!!.jsonObject
        listOf("load_kg", "load_lb").forEach { key ->
            val description = props[key]!!.jsonObject["description"]!!.jsonPrimitive.content
            assertTrue(
                description.contains("omit both for bodyweight"),
                "$key description no longer tells the plan writer to omit both: $description",
            )
        }
    }

    /**
     * The other adjacency in the shipped example, and the one #148 is about:
     * a paced rep exercise runs straight into two that declare no tempo at
     * all.
     *
     * Premise only. This asserts what the published plan says and nothing
     * about what the record flow does with it; the consequence is the pin
     * below.
     */
    @Test
    fun `the shipped example runs a paced exercise straight into two that declare no tempo`() {
        val plan = shippedExample()
        val upperA = plan.sessions.first { it.name == "Upper A" }
        val ids = upperA.exercises.map { it.exercise }

        assertEquals(ids.indexOf("dumbbell_bench_press") + 1, ids.indexOf("single_arm_dumbbell_row"))
        assertEquals(ids.indexOf("single_arm_dumbbell_row") + 1, ids.indexOf("band_assisted_pull_up"))

        val paced = upperA.exercises.first { it.exercise == "dumbbell_bench_press" }
        assertEquals(listOf("3010", "3010"), paced.sets.map { it.tempo })

        listOf("single_arm_dumbbell_row", "band_assisted_pull_up").forEach { id ->
            val exercise = upperA.exercises.first { it.exercise == id }
            assertTrue(exercise.sets.isNotEmpty(), "$id declares no sets")
            exercise.sets.forEachIndexed { i, set ->
                assertNull(set.tempo, "$id set ${i + 1} declares a tempo")
                assertFalse(set.isTimed, "$id set ${i + 1} is timed, so no cadence could reach it anyway")
            }
        }
        assertTrue(plan.validate().isEmpty(), "expected clean validation: ${plan.validate()}")
    }

    /**
     * What a tempo reaching the row would turn on: the whole prep-and-cadence
     * branch, on an exercise whose author declared no tempo.
     *
     * [LeadInPolicy.prepCase] is asked here rather than restated, because it
     * is the same function `RecordViewModel.beginSet` reads to decide whether
     * a set is guided at all. A row that acquires a tempo string is paced by
     * the voice, counted by the guide instead of by the lifter, given a prep
     * it never declared, and graded for compliance against it.
     */
    @Test
    fun `a tempo reaching the row would turn on a prep and a cadence it never declared`() {
        val plan = shippedExample()
        val row =
            plan.sessions.first { it.name == "Upper A" }
                .exercises.first { it.exercise == "single_arm_dumbbell_row" }

        assertFalse(row.playsAnyPrep, "the row declares nothing that would play a prep")
        row.sets.forEach { set ->
            assertEquals(PrepCase.NONE, LeadInPolicy.prepCase(false, set.isTimed, row.effectiveKind))
            assertEquals(PrepCase.CUED, LeadInPolicy.prepCase(true, set.isTimed, row.effectiveKind))
        }
    }
}
