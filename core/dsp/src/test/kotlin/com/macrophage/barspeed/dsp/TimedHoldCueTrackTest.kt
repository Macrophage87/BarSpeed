package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a timed hold actually said in the gym, read from the archive.
 *
 * Characterization only: every assertion here describes the two committed
 * fixtures as they were recorded by app 0.1.48, defect included. Nothing here
 * says what the app SHOULD say.
 *
 * ## Provenance
 *
 * `field-37`, 2026-09-02, app `0.1.48`, sensor WitMotion WT901BLECL, from
 * `meta.json` in that session's export:
 *
 * - set 11, `rope_dead_hang`, `kind: "hold"`, `duration_s: 20`, `prep_s: 12`,
 *   `workStartedAt_ms: 1788342774422`. Committed as
 *   `field-ropedeadhang-hold20-s37-set11-cues.csv`, 18 rows plus the header,
 *   byte-for-byte `set11_rope_dead_hang_cues.csv`.
 * - set 12, same exercise and kind, `duration_s: 30`, `prep_s: 12`,
 *   `workStartedAt_ms: 1788342929396`. Committed as
 *   `field-ropedeadhang-hold30-s37-set12-cues.csv`, 17 rows plus the header,
 *   byte-for-byte `set12_rope_dead_hang_cues.csv`.
 *
 * Set 13 of the same session -- the hold abandoned during its prep, whose cue
 * track is five bare digits and nothing else -- is NOT committed here. It has
 * no `Hold` row to measure anything against, and #216 owns the abandoned set.
 *
 * ## What the tracks show, and what they do not
 *
 * Two streams on two grids. The hold cadence sits on the `x.00` grid locked to
 * `workStartedAt_ms`; a bare-digit stream sits 0.774-0.836 s off it. The
 * assertions below measure both grids from the fixtures. They do NOT identify
 * the producer of the second stream and do not claim the lifter heard the two
 * as separate -- nobody was listening with a stopwatch. Issue #217.
 */
class TimedHoldCueTrackTest {
    private companion object {
        const val SET11 = "field-ropedeadhang-hold20-s37-set11"
        const val SET12 = "field-ropedeadhang-hold30-s37-set12"

        /** `workStartedAt_ms` for each set, from field-37's `meta.json`. */
        const val SET11_WORK_STARTED_MS = 1788342774422L
        const val SET12_WORK_STARTED_MS = 1788342929396L
    }

    private fun labels(fixture: String) = CueTrack.read(fixture).map { it.label }

    private fun holdAt(fixture: String) = CueTrack.read(fixture).single { it.label == "Hold" }.timestampMs

    /**
     * The committed fixture is the file the session exported, row for row.
     *
     * The whole point of a fixture is that it was not edited to make a test
     * pass, so the shape it arrived in is pinned before anything is derived
     * from it.
     */
    @Test
    fun `both committed holds carry the full cadence the session recorded`() {
        assertEquals(
            listOf("Ready", "Brace", "1", "Hold", "2", "3", "15 seconds") +
                (10 downTo 1).map { it.toString() } + "Time",
            labels(SET11),
        )
        assertEquals(
            listOf("Ready", "Brace", "Hold", "1", "2", "15 seconds") +
                (10 downTo 1).map { it.toString() } + "Time",
            labels(SET12),
        )
    }

    /**
     * The `Hold` row is the instant `meta.json` calls the start of the work.
     *
     * This is the provenance assertion: it ties the committed CSV to the
     * session record the header comment cites, so a fixture swapped for
     * another set's track fails here rather than silently re-basing every
     * offset below.
     */
    @Test
    fun `the Hold row lands on the work start meta json records`() {
        assertEquals(-2L, holdAt(SET11) - SET11_WORK_STARTED_MS)
        assertEquals(-2L, holdAt(SET12) - SET12_WORK_STARTED_MS)
    }

    /**
     * The hold cadence is on the second grid of the work start.
     *
     * `Ready` and `Brace` at two and one seconds before it -- `LeadInPlan`
     * fixes the launch phrase to the end of the prep -- then the milestone and
     * the countdown at whole seconds after it, each within the 30 ms the
     * wall-clock `delay` loop drifts by over a 30 s hold.
     */
    @Test
    fun `the hold cadence sits on whole seconds of the work start`() {
        for ((fixture, work) in listOf(SET11 to SET11_WORK_STARTED_MS, SET12 to SET12_WORK_STARTED_MS)) {
            val stray = setOf("1", "2", "3")
            val cadence = CueTrack.read(fixture)
                .filter { it.label !in stray || it.timestampMs > work + 3_000 }
            for (cue in cadence) {
                val offMs = (cue.timestampMs - work).mod(1000L)
                val fromGrid = minOf(offMs, 1000L - offMs)
                assertTrue(fromGrid <= 30, "${cue.label} is ${fromGrid}ms off the work-start grid in $fixture")
            }
        }
    }

    /**
     * A bare digit is spoken 0.186 s before `workStartedAt_ms`, and so
     * 0.184 s before the `Hold` row that announces it.
     *
     * The defect, stated as the archive shows it: on set 11 the first thing
     * the lifter hears after `Brace` is `1`, and it arrives before `Hold`.
     */
    @Test
    fun `set 11 speaks a bare digit before the word that starts the clock`() {
        val first = CueTrack.read(SET11).first { it.label == "1" }
        assertEquals(-186L, first.timestampMs - SET11_WORK_STARTED_MS)
        assertTrue(first.timestampMs < holdAt(SET11), "the stray digit no longer precedes Hold")
    }

    /**
     * The stray digits are on their own grid, not the hold's.
     *
     * 0.774-0.836 s off the work-start grid across the five calls, spaced
     * 0.987-1.022 s, stopping after three calls on set 11 and two on set 12
     * while the hold cadence runs on to `Time`. Two independent producers,
     * measured rather than assumed: a resampling of one stream could not stop
     * early.
     *
     * #217's body gives that offset as 0.79-0.84 s. Re-measured here against
     * `workStartedAt_ms` the low end is 0.774 s, on set 12's second call. The
     * issue's figure does not reproduce against either reference and is not
     * accounted for here. The figures below are the ones this test asserts.
     */
    @Test
    fun `the stray digits run on a grid of their own and stop early`() {
        val strayOffsets = mapOf(
            SET11 to listOf(-186L, 836L, 1823L),
            SET12 to listOf(785L, 1774L),
        )
        for ((fixture, work) in listOf(SET11 to SET11_WORK_STARTED_MS, SET12 to SET12_WORK_STARTED_MS)) {
            val stray = CueTrack.read(fixture)
                .filter { it.label.all(Char::isDigit) && it.timestampMs - work in -2_000L..3_000L }
            assertEquals(strayOffsets.getValue(fixture), stray.map { it.timestampMs - work })
            for (offset in strayOffsets.getValue(fixture)) {
                val grid = offset.mod(1000L)
                assertTrue(grid in 770..840, "$offset is ${grid}ms off, not on the stray digits' own grid")
            }
        }
    }
}
