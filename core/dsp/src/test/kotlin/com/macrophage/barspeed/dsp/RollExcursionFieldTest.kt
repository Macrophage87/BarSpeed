package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four captures issue #133 was filed and re-argued from, pinned against
 * [RollExcursion] rather than against a recomputation nobody can re-run.
 *
 * ## Provenance
 *
 * All four are `imu-a` streams copied verbatim out of the owner's capture
 * archive, with the `-cues.csv` written by the same set and, where the build
 * that recorded it wrote one, the `-prep.csv`. Nothing is regenerated through
 * `ImuCsv.encode` and no column is dropped.
 *
 * - `field-backsquat-wrapping-s36-set01` -- field-36, recorded
 *   2026-09-01T08:35:19.892Z by app 0.1.47, set 1, back squat, 52.2 kg,
 *   6 reps.
 * - `field-rdl-wrapping-s36-set05` -- the same session, set 5, Romanian
 *   deadlift, 52.2 kg, 10 reps.
 * - `field-ohp-prepinflated-s37-set03` -- field-37, recorded
 *   2026-09-02T09:20:45.365Z by app 0.1.48, set 3, seated overhead press,
 *   22.7 kg, 7 reps.
 * - `field-ohp-prepinflated-s37-set04` -- the same session, set 4, seated
 *   overhead press, 22.7 kg, 5 reps.
 *
 * Sensor `WitMotion WT901BLECL` on all four, zone `America/New_York`, both
 * sessions dual-armed. field-36 predates prep windows, so its two sets carry no
 * `-prep.csv` and no work-start bound exists for them; that is the archive's
 * own state, not an omission here.
 *
 * ## What the two sessions each show
 *
 * They fail in DIFFERENT halves, which is why both are here. field-36 shows the
 * SATURATION: its two sets report 358.6 and 360.0 wrapped -- three degrees
 * apart, and both effectively at the ceiling -- while their unwrapped sweeps
 * are 909.0 and 515.2, which are not close to each other at all. field-37 shows
 * the WINDOW, and shows it in the half the issue did not originally name:
 * neither of its sets has a single sample after its terminal cue, so their
 * whole excess comes from the PREP, and a fix that trimmed only the tail would
 * have moved neither figure.
 *
 * ## What these numbers are not
 *
 * They are figures about the streams in these files. Nothing here observed the
 * bar, the mount or the lifter, and the published `rollExcursion_deg` values
 * quoted below are read out of each session's archived `meta.json` -- they are
 * what the app WROTE, not what the sensor did.
 */
class RollExcursionFieldTest {
    private data class Capture(
        val fixture: String,
        val samples: List<ImuSample>,
        val workStartedAtMs: Long?,
        val end: SetEnd,
    )

    private fun read(fixture: String, hasPrep: Boolean): Capture {
        val samples =
            ImuCsv.decode(
                javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString(),
            )
        val cues =
            CueTrack.read(fixture).map { VoiceCue(it.timestampMs, it.label) }
        return Capture(fixture, samples, if (hasPrep) workStartedAtMs(fixture) else null, SetEnd.of(cues))
    }

    /**
     * The `work_started_ms` column of the set's own `-prep.csv`.
     *
     * Parsed here rather than through `PrepWindowCsv`, which lives in
     * `:core:data` and is not on this module's classpath. Two columns, one
     * data row; a wrong parse would be visible as a window bound in 1970.
     */
    private fun workStartedAtMs(fixture: String): Long {
        val rows =
            javaClass.getResourceAsStream("/$fixture-prep.csv")!!
                .readBytes()
                .decodeToString()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("prep_started_ms") }
                .toList()
        return rows.single().split(',')[1].toLong()
    }

    private fun wrappedRange(samples: List<ImuSample>): Double {
        val rolls = samples.map { it.rollDeg }
        return rolls.max() - rolls.min()
    }

    private val backSquat36 get() = read("field-backsquat-wrapping-s36-set01", hasPrep = false)
    private val rdl36 get() = read("field-rdl-wrapping-s36-set05", hasPrep = false)
    private val ohp37Set03 get() = read("field-ohp-prepinflated-s37-set03", hasPrep = true)
    private val ohp37Set04 get() = read("field-ohp-prepinflated-s37-set04", hasPrep = true)

    // ---- the fixtures are the captures the issue quotes ---------------------

    /**
     * The four published figures, reproduced by the arithmetic that produced
     * them, so the before column of every assertion below is measured here and
     * not quoted from a comment.
     *
     * `field-36` published 358.6 and 360.0 as the `sensors[]` entries for role
     * `a` in its `meta.json`; `field-37` published 92.9 and 86.7 as its sets'
     * own `rollExcursion_deg`.
     */
    @Test
    fun `the fixtures reproduce the figures these sessions published`() {
        assertEquals(358.6, wrappedRange(backSquat36.samples), 0.05)
        assertEquals(360.0, wrappedRange(rdl36.samples), 0.05)
        assertEquals(92.9, wrappedRange(ohp37Set03.samples), 0.05)
        assertEquals(86.7, wrappedRange(ohp37Set04.samples), 0.05)
    }

    @Test
    fun `the fixtures carry the sample counts the archive recorded`() {
        assertEquals(5248, backSquat36.samples.size)
        assertEquals(6752, rdl36.samples.size)
        assertEquals(3852, ohp37Set03.samples.size)
        assertEquals(3144, ohp37Set04.samples.size)
    }

    // ---- saturation: field-36 ----------------------------------------------

    /**
     * Two sets three degrees apart on the published figure, 394 degrees apart
     * on what their mounts swept.
     *
     * This is the discrimination the field is supposed to provide and does not:
     * an analysis reading 358.6 against 360.0 cannot tell these two sets apart,
     * and both round to "it tumbled".
     */
    @Test
    fun `field-36 stops discriminating wrapped and separates unwrapped`() {
        val squat = RollExcursion.of(backSquat36.samples, backSquat36.workStartedAtMs, backSquat36.end)!!
        val rdl = RollExcursion.of(rdl36.samples, rdl36.workStartedAtMs, rdl36.end)!!
        assertTrue(
            wrappedRange(rdl36.samples) - wrappedRange(backSquat36.samples) < 2.0,
            "the published figures are within 2 degrees of each other, which is the defect",
        )
        assertEquals(909.0, squat.degrees, 0.05)
        assertEquals(515.2, rdl.degrees, 0.05)
        assertTrue(
            squat.degrees > 360.0 && rdl.degrees > 360.0,
            "both swept more than a full turn, which the bounded figure cannot express",
        )
    }

    /**
     * field-36 predates the prep window, so these two sets are bounded at one
     * end only and say so.
     */
    @Test
    fun `field-36 is bounded at the terminal cue alone`() {
        assertEquals(
            RollExcursion.Basis.TO_TERMINAL_CUE,
            RollExcursion.of(rdl36.samples, rdl36.workStartedAtMs, rdl36.end)!!.basis,
        )
    }

    // ---- the window: field-37 ----------------------------------------------

    /**
     * The half issue #133 did not name until field-37 measured it: both sets
     * have ZERO samples after their terminal cue and are still inflated, so the
     * excess is entirely the prep.
     */
    @Test
    fun `field-37 sets 3 and 4 are inflated by their prep, not by a tail`() {
        for (capture in listOf(ohp37Set03, ohp37Set04)) {
            val end = capture.end as SetEnd.Cued
            assertEquals(
                0,
                capture.samples.count { it.timestampMs > end.atMs },
                "${capture.fixture} was expected to carry no tail at all",
            )
            assertTrue(
                capture.samples.count { it.timestampMs < capture.workStartedAtMs!! } > 0,
                "${capture.fixture} was expected to carry prep samples",
            )
        }
    }

    @Test
    fun `field-37 set 3 reports its working window and not its whole file`() {
        val measured = RollExcursion.of(ohp37Set03.samples, ohp37Set03.workStartedAtMs, ohp37Set03.end)!!
        assertEquals(54.0, measured.degrees, 0.05)
        assertEquals(RollExcursion.Basis.WORKING_WINDOW, measured.basis)
    }

    @Test
    fun `field-37 set 4 reports its working window and not its whole file`() {
        val measured = RollExcursion.of(ohp37Set04.samples, ohp37Set04.workStartedAtMs, ohp37Set04.end)!!
        assertEquals(63.7, measured.degrees, 0.05)
        assertEquals(RollExcursion.Basis.WORKING_WINDOW, measured.basis)
    }

    /**
     * Neither field-37 set crosses the discontinuity, so unwrapping alone
     * changes nothing on them -- stated as an assertion because it is the
     * reason the window half needed its own fixtures.
     */
    @Test
    fun `unwrapping alone would not have moved field-37`() {
        for (capture in listOf(ohp37Set03, ohp37Set04)) {
            val unwrapped = RollExcursion.unwrap(capture.samples.map { it.rollDeg })
            assertEquals(
                wrappedRange(capture.samples),
                unwrapped.max() - unwrapped.min(),
                1e-9,
                "${capture.fixture} was expected not to cross +-180",
            )
        }
    }
}
