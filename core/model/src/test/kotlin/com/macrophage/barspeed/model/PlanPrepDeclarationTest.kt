package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differentials for declaring a prep in the plan.
 *
 * Every assertion but the last two fails at this commit: `prep_s` is not a key
 * [PlanExerciseDef] knows, so the import gate reports it as an unknown key that
 * was ignored, refuses nothing about its value, and warns about none of the ways
 * it can be declared uselessly.
 *
 * ## Everything here goes through [PlanImport.parse], on purpose
 *
 * These are written against the strings the LIFTER sees at the approval gate,
 * not against a property. A property assertion would need the new field to
 * compile, which would make this a compile failure rather than a test failure --
 * and a compile failure reds every test in the module, hiding the very
 * differential it was meant to show.
 *
 * It also pins the thing most likely to be wrong. The field is one line; what
 * takes the work is the JSON path built through nested arrays, and which of the
 * three tiers of bound refuses what. `PlanImport` derives its known-key set from
 * the serializer descriptor, so `prep_s` stops being reported as unknown by
 * virtue of existing -- which is exactly the near-neighbour a separate
 * hand-maintained list would have left behind.
 */
class PlanPrepDeclarationTest {
    private fun parse(vararg exercises: String): PlanImport.Result = PlanImport.parse(
        """
        {"schemaVersion":"1.4","planName":"P","sessions":[
          {"name":"Lower A","exercises":[${exercises.joinToString(",")}]}
        ]}
        """.trimIndent(),
    )

    private val path = "sessions[0].exercises[0]"

    // ---- the key is a plan key -----------------------------------------------

    /**
     * A declared prep is not reported as an unknown key.
     *
     * The first thing a lifter would see if the field were added anywhere but
     * the plan model: the plan works, and the approval gate says the app ignored
     * the very key that made it work.
     */
    @Test
    fun `a declared prep is a plan key rather than an unknown one`() {
        val result = parse("""{"exercise":"romanian_deadlift","prep_s":20,"sets":[{"reps":5,"tempo":"3010"}]}""")

        assertEquals(emptyList(), result.errors, "a declared prep is not a reason to refuse the plan")
        assertFalse(
            result.warnings.any { "\"prep_s\"" in it && "unknown key" in it },
            "prep_s was reported as an unknown key: ${result.warnings}",
        )
    }

    // ---- the bounds ----------------------------------------------------------

    /**
     * A negative prep is refused outright, naming the path.
     *
     * Not a warning. `LeadInPlan.of` builds its beats with `List(prepS)`, which
     * throws `IllegalArgumentException: Illegal Capacity: -1` on a negative --
     * inside `:app`, where nothing catches it, while a set is being started. The
     * device path is clamped as well, but a plan that says something impossible
     * should be told so at the gate rather than quietly corrected.
     */
    @Test
    fun `a negative prep is refused, naming the exercise path`() {
        val result = parse("""{"exercise":"back_squat","prep_s":-5,"sets":[{"reps":5,"tempo":"3010"}]}""")

        assertEquals(listOf("$path.prep_s must be between 0 and 120 seconds"), result.errors)
    }

    /**
     * And beyond the ceiling. Two minutes of standing still before a set is a
     * typo, not a prescription; a plan that meant 12 and wrote 120 gets told.
     */
    @Test
    fun `a prep beyond the ceiling is refused, naming the exercise path`() {
        val result = parse("""{"exercise":"back_squat","prep_s":121,"sets":[{"reps":5,"tempo":"3010"}]}""")

        assertEquals(listOf("$path.prep_s must be between 0 and 120 seconds"), result.errors)
    }

    @Test
    fun `the bounds themselves are accepted`() {
        listOf(0, 120).forEach { prep ->
            val result = parse("""{"exercise":"back_squat","prep_s":$prep,"sets":[{"reps":5,"tempo":"3010"}]}""")
            assertEquals(emptyList(), result.errors, "prep_s $prep should be inside the bounds")
        }
    }

    // ---- the two non-blocking cases -----------------------------------------

    /**
     * A prep declared on an exercise no set of which runs the voice guide.
     *
     * Not hypothetical: in the published `plan.example.json`,
     * `band_assisted_pull_up` and `hanging_leg_raise` are rep-based and carry
     * no tempo, and a cable machine -- the case that motivated this feature --
     * is the likeliest one to be given a prep and no tempo. The plan's author
     * asked for something and nothing happens; the gate is the only place that
     * can say so.
     *
     * The exercise here used to be `plank`, which is no longer an example of
     * anything: a hold runs a prep. What is left is the case the warning is
     * actually for, a set with reps and no tempo.
     */
    @Test
    fun `a prep declared where nothing runs the voice guide is warned about as inert`() {
        val result = parse("""{"exercise":"band_assisted_pull_up","prep_s":10,"sets":[{"reps":8}]}""")

        assertEquals(emptyList(), result.errors, "an inert declaration is not a reason to refuse the plan")
        assertTrue(
            result.warnings.any {
                it.startsWith("$path: \"prep_s\" is declared on band_assisted_pull_up") &&
                    "no prep is played" in it
            },
            "no inert-declaration warning: ${result.warnings}",
        )
    }

    /**
     * A prep declared on a hold or a carry is not inert, and the gate must stop
     * saying it is.
     *
     * This is the half of the change a plan author sees. Before it, a plan that
     * asked for ten seconds before a dead hang was told at the import gate that
     * the declaration had no effect -- which was true, and is what the lifter
     * asked to be changed.
     */
    @Test
    fun `a prep declared on a hold or a carry is not reported as inert`() {
        listOf(
            """{"exercise":"plank","prep_s":10,"sets":[{"duration_s":45}]}""",
            """{"exercise":"farmers_walk","prep_s":10,"sets":[{"duration_s":40}]}""",
        ).forEach { exercise ->
            val result = parse(exercise)

            assertEquals(emptyList(), result.errors, "a declared prep is not a reason to refuse the plan")
            // No prep warning AT ALL rather than the absence of one phrase.
            // The inert warning's wording changes in the same commit that makes
            // this pass, so matching on it would leave a check that cannot
            // fail: the phrase would be gone either way.
            assertFalse(
                result.warnings.any { "prep_s" in it },
                "a timed set was warned about its prep: ${result.warnings}",
            )
        }
    }

    /**
     * The inert warning explains itself, and its explanation stops being true.
     *
     * It told the plan author that a set "needs a tempo, and must be neither
     * timed nor an explosive lift". Half of that is now wrong, and it is the
     * half a plan author reads to decide where a prep is worth declaring.
     */
    @Test
    fun `the inert warning no longer says a prep needs a tempo`() {
        val result = parse("""{"exercise":"band_assisted_pull_up","prep_s":10,"sets":[{"reps":8}]}""")
        val warning = result.warnings.single { "prep_s" in it }

        assertFalse("a set needs a tempo" in warning, "the inert warning still says a prep needs a tempo: $warning")
        assertTrue("timed hold or carry" in warning, "the inert warning never names the timed case: $warning")
    }

    /**
     * And the warning that DOES apply to a hold now applies to it: a prep too
     * short for the launch phrase drops `Ready` on a hold exactly as on a
     * tempo'd lift, because both walk the same `LeadInPlan`.
     *
     * Whole sentence, not a prefix. The prefix is true either way; the TAIL is
     * the claim, and the tail this warning carried named a first movement that
     * a hold does not have. A prefix match on a string whose tail is the claim
     * green-lights the half that is wrong.
     */
    @Test
    fun `a short prep on a hold is warned about for what it drops`() {
        val result = parse("""{"exercise":"plank","prep_s":1,"sets":[{"duration_s":45}]}""")

        assertEquals(emptyList(), result.errors, "a short prep is legal")
        assertEquals(
            listOf(
                "$path: \"prep_s\": 1 is shorter than the launch phrase - the prep says only " +
                    "\"Brace\", and the \"Ready\" beat two seconds before the set begins is dropped.",
            ),
            result.warnings.filter { "prep_s" in it },
        )
    }

    /**
     * An explosive lift carrying a tempo is the same case arriving a different
     * way, and the one a reader is least likely to predict: the set has a tempo,
     * so the declaration looks live, and no prep is played.
     *
     * The second assertion is the one with teeth. `PlanSetDef.validate` refuses
     * a tempo only alongside `duration_s`, so this document is legal and is what
     * a pasted plan actually meets. A warning whose explanation stops at "a prep
     * plays before a set carrying a tempo" contradicts itself on it, and
     * asserting only the prefix cannot see that.
     */
    @Test
    fun `a prep on an explosive lift is inert even though the set carries a tempo`() {
        val result =
            parse("""{"exercise":"power_clean","kind":"explosive","prep_s":10,"sets":[{"reps":3,"tempo":"3010"}]}""")
        val warning = result.warnings.single { "prep_s" in it }

        assertTrue("\"prep_s\" is declared on power_clean" in warning, "no inert warning: $warning")
        assertTrue("explosive" in warning, "the inert warning never names the explosive case: $warning")
    }

    /**
     * A prep of one second, which plays only half the launch phrase.
     *
     * The lifter hears "Brace" and nothing else -- no countdown, and no "Ready"
     * two seconds out. That is a legal thing to ask for and is not refused; what
     * the warning does is name what got dropped, so a plan author who wrote 1
     * meaning "quick" finds out that quick meant silent.
     */
    @Test
    fun `a prep too short for the launch phrase is warned about, naming what is dropped`() {
        val result = parse("""{"exercise":"cable_row","prep_s":1,"sets":[{"reps":10,"tempo":"2010"}]}""")

        assertEquals(emptyList(), result.errors, "a short prep is legal")
        assertTrue(
            result.warnings.any {
                it.startsWith("$path: \"prep_s\": 1 is shorter than the launch phrase") &&
                    "\"Brace\"" in it && "\"Ready\"" in it
            },
            "no clipped-phrase warning: ${result.warnings}",
        )
    }

    /**
     * A prep of zero, where the difference is worth a sentence of its own: not a
     * clipped phrase but no speech at all before the set begins.
     *
     * Whole sentence, not a prefix, for the reason the hold's short-prep
     * warning is: the tail is the claim, and this warning is reachable on a
     * hold now, which has no first movement for it to name.
     */
    @Test
    fun `a prep of zero is warned about as speaking nothing at all`() {
        val result = parse("""{"exercise":"cable_row","prep_s":0,"sets":[{"reps":10,"tempo":"2010"}]}""")

        assertEquals(emptyList(), result.errors, "a prep of zero is legal")
        assertEquals(
            listOf("$path: \"prep_s\": 0 means nothing at all is spoken before the set begins."),
            result.warnings.filter { "prep_s" in it },
        )
    }

    /**
     * The two cases do not both fire on one exercise. An inert declaration is
     * about a prep that never plays, so how short it would have been is not a
     * fact about anything; saying both would be two complaints about one
     * mistake and would bury the one that matters.
     */
    @Test
    fun `an inert declaration is not also warned about for being short`() {
        val result = parse("""{"exercise":"band_assisted_pull_up","prep_s":1,"sets":[{"reps":8}]}""")

        assertEquals(
            1,
            result.warnings.count { "prep_s" in it },
            "expected exactly one prep warning: ${result.warnings}",
        )
        assertTrue(result.warnings.single { "prep_s" in it }.contains("the declaration has no effect"))
    }

    // ---- the control ---------------------------------------------------------

    /**
     * GREEN AT THIS COMMIT AND GREEN AFTER, and named as a control rather than a
     * differential so the count above is not read as covering it.
     *
     * A plan that declares no prep is not warned about, is not refused, and
     * behaves as it always did. Its other half -- that the prep such a plan gets
     * is the five seconds every plan got before any of this -- is
     * `LeadInPolicyTest.a plan that declares nothing gets the prep every plan got
     * before`, which asserts the number. Neither half alone says it; together
     * they are the whole no-behaviour-change claim for existing plans.
     */
    @Test
    fun `a plan that declares no prep is neither warned about nor refused`() {
        val result = parse("""{"exercise":"back_squat","sets":[{"reps":5,"tempo":"3010"}]}""")

        assertEquals(emptyList(), result.errors)
        assertFalse(
            result.warnings.any { "prep" in it },
            "a plan that says nothing about prep was warned about it: ${result.warnings}",
        )
    }
}
