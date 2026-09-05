package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the prep countdown says about where the coming rep starts (#241),
 * pinned as a contract: these are strings a lifter reads from the bar, so the
 * exact words are the thing under test and not an implementation detail.
 *
 * Four movement cases -- vertical crossed with top/bottom, horizontal crossed
 * with which stroke opens -- plus the provenance marker, which is a separate
 * axis: any of the four can be declared, seeded or guessed.
 *
 * The vertical/horizontal split is not cosmetic. `TempoSchedule` reads a tempo
 * POSITIONALLY on vertical work and BY PHASE on horizontal work for the same
 * reason this says "TOP" on one and refuses to name a position on the other:
 * there is no top of a seated row. The word this returns is pinned equal to the
 * word the voice opens with in `:core:dsp`'s `StartCueVoiceContractTest`, which
 * is the module that can see both.
 */
class StartCuePolicyTest {
    private fun cue(
        startsWith: StartPhase,
        concentricUp: Boolean = true,
        horizontal: Boolean = false,
        source: GeometrySource = GeometrySource.DECLARED,
    ) = StartCuePolicy.of(startsWith, concentricUp, horizontal, source)

    @Test
    fun `a bench press lowered first starts at the top and goes down`() {
        val c = cue(StartPhase.ECCENTRIC, concentricUp = true)
        assertEquals("Start at the TOP, first movement DOWN", c.phrase)
        assertEquals("DOWN", c.word)
    }

    @Test
    fun `a deadlift driven first starts at the bottom and goes up`() {
        val c = cue(StartPhase.CONCENTRIC, concentricUp = true)
        assertEquals("Start at the BOTTOM, first movement UP", c.phrase)
        assertEquals("UP", c.word)
    }

    @Test
    fun `a pulldown driven first starts at the top though its drive goes down`() {
        val c = cue(StartPhase.CONCENTRIC, concentricUp = false)
        assertEquals("Start at the TOP, first movement DOWN", c.phrase)
        assertEquals("DOWN", c.word)
    }

    @Test
    fun `a leg curl lowered first starts at the bottom and returns up`() {
        val c = cue(StartPhase.ECCENTRIC, concentricUp = false)
        assertEquals("Start at the BOTTOM, first movement UP", c.phrase)
        assertEquals("UP", c.word)
    }

    @Test
    fun `horizontal work driven first opens on the drive and names no position`() {
        val c = cue(StartPhase.CONCENTRIC, horizontal = true)
        assertEquals("First movement DRIVE", c.phrase)
        assertEquals("DRIVE", c.word)
    }

    @Test
    fun `horizontal work lowered first opens on the return and names no position`() {
        val c = cue(StartPhase.ECCENTRIC, horizontal = true)
        assertEquals("First movement RETURN", c.phrase)
        assertEquals("RETURN", c.word)
    }

    @Test
    fun `horizontal work ignores the drive direction entirely`() {
        assertEquals(
            cue(StartPhase.CONCENTRIC, concentricUp = true, horizontal = true),
            cue(StartPhase.CONCENTRIC, concentricUp = false, horizontal = true),
        )
    }

    @Test
    fun `a guessed start is marked so the lifter checks the plan`() {
        assertEquals("Guessed from the name", cue(StartPhase.ECCENTRIC, source = GeometrySource.INFERRED).marker)
    }

    @Test
    fun `a declared start carries no marker`() {
        assertNull(cue(StartPhase.ECCENTRIC, source = GeometrySource.DECLARED).marker)
    }

    @Test
    fun `a seeded start carries no marker`() {
        assertNull(cue(StartPhase.ECCENTRIC, source = GeometrySource.SEEDED).marker)
    }

    @Test
    fun `a start nothing decided is not called a guess from the name`() {
        assertEquals("Not declared", cue(StartPhase.ECCENTRIC, source = GeometrySource.DEFAULT).marker)
    }

    @Test
    fun `the marker never changes the words the lifter moves on`() {
        val declared = cue(StartPhase.ECCENTRIC, source = GeometrySource.DECLARED)
        for (source in GeometrySource.entries) {
            val other = cue(StartPhase.ECCENTRIC, source = source)
            assertEquals(declared.phrase, other.phrase, "phrase moved for $source")
            assertEquals(declared.word, other.word, "word moved for $source")
        }
    }

    @Test
    fun `the vertical position follows startsAtTop and nothing else`() {
        for (phase in StartPhase.entries) {
            for (up in listOf(true, false)) {
                val expected = if (ExerciseDef.startsAtTop(phase, up)) "TOP" else "BOTTOM"
                val phrase = cue(phase, concentricUp = up).phrase
                assertEquals(true, phrase.contains(expected), "$phase / concentricUp=$up said: $phrase")
            }
        }
    }

    @Test
    fun `an ad-hoc set against an id the app does not ship is marked as a guess`() {
        val used = ExerciseDef.resolvedById("cable_face_pull_thing")
        val source = SetGeometryPolicy.describe(used, declared = null).sources.startsWith
        assertEquals(GeometrySource.INFERRED, source)
        assertEquals(
            "Guessed from the name",
            StartCuePolicy.of(used.startsWith, used.concentricUp, used.horizontal, source).marker,
        )
    }

    @Test
    fun `an ad-hoc set against a seeded id is not marked`() {
        val used = ExerciseDef.resolvedById("back_squat")
        val source = SetGeometryPolicy.describe(used, declared = null).sources.startsWith
        assertEquals(GeometrySource.SEEDED, source)
        assertNull(StartCuePolicy.of(used.startsWith, used.concentricUp, used.horizontal, source).marker)
    }
}
