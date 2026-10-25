package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.SpokenRepCall
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SpokenRepCall] against the utterances `:core:dsp` actually produces (#339).
 *
 * The guided cadence's script, the lead-in and the timed voice are where the
 * words come from, so the marking is pinned against them rather than against
 * a copy of their spelling: a rep call spelled differently here would move
 * the constants and leave a test of literals green.
 */
class RepCallSpeechTest {
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)
    private val legPress = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )
    private val facePull = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    /** Calls at the start of the rep, at the end of the drive, and on both planes. */
    private val corpus = listOf(
        Triple("2011", benchPress, 12),
        Triple("3010", benchPress, 10),
        Triple("1030", legCurl, 12),
        Triple("2011", facePull, 12),
        Triple("1010", legPress, 6),
        Triple("2010", legPress, 1),
    )

    private fun utterances(): List<String> = corpus.flatMap { (tempo, direction, reps) ->
        CadenceVoice.script(CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction)), reps).map { it.utterance }
    }

    private fun isRepCall(utterance: String) =
        utterance.startsWith(CadencePlan.REP_CALL_PREFIX) || utterance == CadencePlan.LAST_REP

    /**
     * Every rep call the guide speaks across the corpus: `Rep N` marks the word
     * over the prefix and N as a cardinal, `Last rep` marks the word alone.
     * Read off [CadencePlan.REP_CALL_PREFIX] and [CadencePlan.LAST_REP], so a
     * respelling of either moves this test with it.
     */
    @Test
    fun `every rep call the guide speaks is marked as one`() {
        val calls = utterances().filter(::isRepCall)
        assertTrue(calls.any { it == CadencePlan.LAST_REP })
        assertTrue(calls.any { it.startsWith(CadencePlan.REP_CALL_PREFIX) })
        val wordEnd = CadencePlan.REP_CALL_PREFIX.trimEnd().length
        for (call in calls.distinct()) {
            val expected = if (call == CadencePlan.LAST_REP) {
                val at = call.length - SpokenRepCall.WORD.length
                listOf(SpokenRepCall.Span(at, call.length, SpokenRepCall.Reading.WORD, SpokenRepCall.WORD))
            } else {
                val numberAt = CadencePlan.REP_CALL_PREFIX.length
                listOf(
                    SpokenRepCall.Span(0, wordEnd, SpokenRepCall.Reading.WORD, SpokenRepCall.WORD),
                    SpokenRepCall.Span(numberAt, call.length, SpokenRepCall.Reading.CARDINAL, call.substring(numberAt)),
                )
            }
            assertEquals(expected, SpokenRepCall.spansOf(call), call)
        }
    }

    @Test
    fun `nothing the guide, the lead-in or the timed voice says besides a rep call is marked`() {
        val others = utterances().filterNot(::isRepCall) +
            (0..20).flatMap { LeadInPlan.of(it).beats.mapNotNull { beat -> beat.spoken } } +
            (0..120).mapNotNull { TimedSetVoice.cueFor(it) } +
            listOf(CadenceVoice.DONE, SetEnd.STOPPED, TimedSetVoice.TIME_UP)
        assertTrue(others.isNotEmpty())
        for (text in others.distinct()) assertEquals(emptyList(), SpokenRepCall.spansOf(text), text)
    }
}
