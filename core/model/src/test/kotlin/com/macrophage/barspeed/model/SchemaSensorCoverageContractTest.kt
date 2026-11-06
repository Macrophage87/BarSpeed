package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What #242 and #287 change about what the published export SAYS: the
 * analysis can now move off an armed unit that delivered enough frames, when
 * it stopped early beside its partner or never moved while its partner did.
 *
 * Pinned against the real document in `docs/schemas/`, as
 * [SchemaShortDeliveryContractTest] pins #209's widening of the same three
 * keys. That file's absences still hold and are not restated here; this one
 * asserts the two new causes are said where a reader looks for why a set's
 * analysis moved, and that the version log files them once under 1.26.
 */
class SchemaSensorCoverageContractTest {
    private fun schema() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/session-export.schema.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun sensorDescription(name: String) = schema()["\$defs"]!!.jsonObject["setSensors"]!!
        .jsonObject["properties"]!!.jsonObject[name]!!.jsonObject["description"]!!.jsonPrimitive.content

    private fun exportVersionLog() = schema()["properties"]!!
        .jsonObject["schemaVersion"]!!.jsonObject["description"]!!.jsonPrimitive.content

    private val shippedPrompt: String =
        checkNotNull(
            javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see the include filter in core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private val marker = "1.26 (#242, #287, a unit that cannot stand for the set beside its partner)"

    /**
     * Review round 1 (lens A 2, lens B B2): the rule compares SPANS, first
     * arrival to last, and never reads where a stream ended; the text said it
     * ended short. It now says what is computed, that a late start reads the
     * same, and that a mid-set gap is not caught.
     */
    @Test
    fun `analysedFellBack names all three causes`() {
        val d = sensorDescription("analysedFellBack")
        val facts =
            listOf("THREE CAUSES", "SPANNED LESS OF THE SET", "STARTED LATE", "NEVER MOVED", "0.01 g", "one second")
        for (fact in facts) {
            assertTrue(fact in d, "analysedFellBack does not state: $fact")
        }
        assertTrue("A GAP IS NOT CAUGHT" in d, "analysedFellBack does not name the mid-set dropout it misses")
        assertFalse("its stream ending more than one second" in d, "analysedFellBack still says the stream ENDED short")
    }

    /** Review round 1 (lens A 1): the opener said too few frames was the only cause. */
    @Test
    fun `analysedFellBack opens with the 1_26 causes`() {
        val opener = sensorDescription("analysedFellBack").substringBefore(" ABSENT means")
        assertTrue("FROM 1.26" in opener, "the analysedFellBack opener still names one cause: $opener")
    }

    /**
     * Review round 1 (lens A 4, lens B B3): a still or short-span unit is named
     * in `silent` whenever its link was not delivering as the set ended, so
     * "`silent` does not name it" was a false absolute; and the `silent`
     * opener and its "written only where" sentence said too few frames alone.
     */
    @Test
    fun `silent says when a unit moved off for the 1_26 causes is named`() {
        val fellBack = sensorDescription("analysedFellBack")
        assertFalse("so `silent` does not name it" in fellBack, "the false absolute about silent is still stated")
        assertTrue("named in `silent` only when its link was not delivering" in fellBack, "fellBack: when named")
        val silent = sensorDescription("silent")
        assertTrue("or, FROM 1.26 (#242, #287), were moved off" in silent.substringBefore(" keyed by role"))
        assertTrue("Until 1.26 the row was written only where" in silent, "silent: written-only-where unqualified")
        assertTrue("or one that never moved while its partner did" in silent, "silent: the still unit")
    }

    @Test
    fun `the fallback basis and silent say what they now cover`() {
        val basis = sensorDescription("analysedRoleBasis")
        assertTrue("started late or stopped early" in basis && "never moved" in basis, "fallback: the two causes")
        assertTrue("never the one a `stackSignature` names" in basis, "a still unit's signature is not ruled out")
        assertTrue("started late or stopped early beside its partner" in sensorDescription("silent"), "silent: span")
    }

    /**
     * Review round 2 (lens B BLK-1): the basis's own `fallback` sentence, at
     * its head, still defined the fact as too few frames, and its READ WITH
     * sentence said `analysedFellBack` means the armed unit "went quiet and
     * nothing else" -- false of a still unit that streamed to the end.
     */
    @Test
    fun `analysedRoleBasis says fallback is a unit that could not stand for the set`() {
        val basis = sensorDescription("analysedRoleBasis")
        assertFalse("went quiet and nothing else" in basis, "the basis still says the flag means going quiet")
        val fallback = basis.substringAfter("`fallback` means").substringBefore(" READ WITH")
        assertTrue("could not stand for the set" in fallback, "the fallback sentence: $fallback")
        assertTrue("from 1.26" in fallback, "the fallback sentence does not name the 1.26 causes: $fallback")
    }

    /**
     * Review round 2 (lens B BLK-2): `silent`'s ABSENT sentence said every
     * armed unit delivered a stream the analysis could run on. From 1.26 a
     * unit moved off for its span or for never moving is absent whenever its
     * link was still delivering as the set ended, and the ROLE sentence's
     * "`expected` minus the units the analysis could be run on" missed the
     * same drop.
     */
    @Test
    fun `silent's absence says a refused unit still delivering is not named`() {
        val silent = sensorDescription("silent")
        assertFalse(
            "ABSENT means every armed unit delivered a stream the analysis could run on" in silent,
            "silent's ABSENT sentence is still the pre-1.26 absolute",
        )
        val absent = silent.substringAfter("ABSENT means").substringBefore(" Absence has")
        assertTrue("from 1.26" in absent, "silent's ABSENT sentence: $absent")
        assertTrue("still delivering as the set ended" in absent, "silent's ABSENT sentence: $absent")
        assertTrue("`analysedFellBack`" in absent, "silent's ABSENT sentence does not point at the flag: $absent")
        val role = silent.substringAfter("The ROLE is a key").substringBefore("What is new is the WORD")
        assertTrue("delivering as the set ended" in role, "silent's ROLE sentence misses the drop: $role")
    }

    /**
     * Review round 2 (lens A N4): the gap sentence said nothing here reveals
     * the hole; `deliveredRate_hz` is the one key that reads low over it.
     */
    @Test
    fun `the gap sentence points at the delivered rate`() {
        val gap = sensorDescription("analysedFellBack").substringAfter("A GAP IS NOT CAUGHT")
            .substringBefore(" A unit moved off")
        assertTrue("deliveredRate_hz" in gap, "analysedFellBack's gap sentence: $gap")
        assertTrue(
            "came back before the end spans the set" in shippedPrompt &&
                "deliveredRate_hz" in shippedPrompt.substringAfter("came back before the end spans the set")
                    .take(600),
            "the prompt's gap sentence does not point at the delivered rate",
        )
    }

    @Test
    fun `the 1_26 log files the coverage change once`() {
        val log = exportVersionLog()
        assertEquals(1, log.split(marker).size - 1, "the #242/#287 entry is not filed exactly once")
        val entry = log.substringAfter(marker).substringBefore(" 1.26 (")
        for (fact in listOf("NOT RETROACTIVE", "DATABASE_VERSION does NOT move", "94 ms", "0.0374 g")) {
            assertTrue(fact in entry, "the #242/#287 entry does not state: $fact")
        }
        assertTrue("SPAN -- first arrival to last" in entry, "the #242 entry does not say it compares spans")
        assertTrue("is NOT caught" in entry, "the #242 entry does not name the mid-set dropout")
        assertFalse("stream ENDED more than one second" in entry, "the #242 entry still says the stream ENDED short")
    }

    @Test
    fun `the shipped prompt says the analysis also moves off an early stop or a still unit`() {
        assertTrue(
            "also off a unit whose stream spanned less of the set than its partner's -- it started late or " +
                "stopped early -- or never moved while its partner did" in shippedPrompt,
            "the coach is not told the two new causes",
        )
        assertTrue("came back before the end spans the set" in shippedPrompt, "the coach: the gap is not named")
    }

    /** Review round 1 (lens A 1): the prompt's own definitions of the two keys said too few frames alone. */
    @Test
    fun `the shipped prompt's definitions of analysedFellBack and silent carry the 1_26 causes`() {
        assertFalse(
            "true only when the unit the set ARMED delivered too few frames to analyse while" in shippedPrompt,
            "the prompt still says analysedFellBack is only too few frames",
        )
        assertTrue("could not stand for the set while another could" in shippedPrompt)
        assertTrue(
            "one the analysis moved off for a short span or for never moving, but only when its link was not" in
                shippedPrompt,
            "the prompt's silent definition still says too few frames alone",
        )
    }
}
