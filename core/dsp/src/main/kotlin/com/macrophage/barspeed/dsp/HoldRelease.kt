package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * When the implement was let go, read off a hold's own accelerometer stream.
 *
 * `TimedSetEndPolicy` in `:core:model` answers which instant a hold is
 * measured TO when the app's own clock reaches the target. This answers the
 * other case, which until #259 nothing answered at all: a hold that ends
 * before the target ends when the lifter taps the phone, and on a hold the
 * phone is not in their hands. The owner's words, 2026-09-05, which is the
 * whole reason this file exists:
 *
 * > "The problem with a lot of these holds are that my hands are full because
 * > they are holding onto the rope. So it takes about 5-10 sec to actually
 * > grab my phone."
 *
 * Every one of those seconds is recorded as hold time today, and hold seconds
 * are the progression metric on a `time` exercise, so the error lands on the
 * number the next plan steps up from.
 *
 * ## Retrospective, never live
 *
 * This is asked at the SET WRITE, over the stream the set already captured,
 * and it never ends a set. Two reasons, in the order they matter. A false
 * positive taken live would end a hold the lifter is still holding -- the
 * remaining seconds are then never captured, which is the unrecoverable half
 * of this repository's recompute/capture asymmetry -- while a false positive
 * taken at the write costs a wrong `duration_s` with the full span still in
 * the archive and the rest screen's correction still able to restate it. And
 * the app records faithfully and decides afterwards: nothing here alters,
 * trims or reorders a sample.
 *
 * ## The signal, and why this one
 *
 * [deviationG] is the sample's acceleration magnitude minus one gravity, in g,
 * unsigned. A unit at rest in any orientation reads 1 g, so this is zero for a
 * still hold whatever the mount, with no filter state, no integration and no
 * frame transform -- and therefore nothing for a dropout to rescale, which is
 * what `VelocityEstimator`'s reconstructed clock has.
 *
 * [BAND_G] is a derived bound rather than a fitted one: a released implement
 * falls, a falling unit reads 0 g, and 0 g is exactly 1 g of deviation. The
 * landing is the same crossing from the other side, at 2 g or more. So the
 * band is what a release LOOKS LIKE rather than a level chosen to separate the
 * captures at hand -- though it does separate them, with the margins measured
 * in `HoldReleaseFieldTest`.
 *
 * ## What it cannot see
 *
 * A hold that ends with nothing falling and nothing struck -- a plank stood up
 * out of, a wall sit walked away from -- crosses no band, and the answer is
 * null: no release seen, today's behaviour stands, the lifter's tap decides.
 * That is the intended failure direction and it is not a claim that such holds
 * end quietly; no capture of one has been read. Whether a release the owner
 * would call obvious always crosses 1 g on a mount that is neither on the
 * stack nor in a pocket is a [Field] question, not a property of this file.
 *
 * WHICH UNIT IS READ MOVES THE ANSWER, and the corpus measures it. On field-38
 * set 18 role a crosses at 1788518020443, 26.058 s into the hold, and role b at
 * 1788518017698, 23.313 s in -- 2.745 s apart, so a set that analysed role b
 * would record 23 s where one analysing role a records 26. `captureAt` in
 * `:app` chooses the stream and this file reads whichever it chose; nothing
 * here checks that the chosen unit was on the implement. Every figure in this
 * paragraph is asserted in `HoldReleaseFieldTest`.
 *
 * [Field] On a two-unit rope hold, which unit is on the rope and which is on
 * the lifter -- and is the role the app analyses the one on the rope? Arm both
 * units for one 45 s rope dead hang; before the set, write down which physical
 * unit is role a and which is role b, and where each is mounted (rope handle or
 * trouser pocket). Nothing is timed by hand and no second device is used. Then
 * read that set's exported `duration_s` and `durationEndedBy`. The figure to
 * check is whether the mount the app analysed was the one on the implement:
 * set 18's two units answer 26 s and 23 s for the same hold, and nothing in the
 * capture records which mount produced which.
 */
object HoldRelease {
    /**
     * Deviation from one gravity a sample must exceed to be a candidate
     * release, in g.
     *
     * One gravity, because that is the arithmetic of a fall: the free-fall
     * bound is 0 g of measured acceleration, which is 1.0 of deviation, and an
     * impact reaches it from above at 2 g.
     *
     * THE MARGIN, and it is not the same on every mount. This sentence read
     * "a hand-held implement stays well inside it -- the largest deviation
     * measured anywhere in the working window of the four committed holds is
     * 0.54 g, at the pick-up of a 90 lb rope carry", and that is false: 0.54 g
     * is the largest on the three ANALYSED streams (set 17 reads 0.57 g, set 18
     * 0.51 g, the carry 0.54 g), while field-38 set 18's PARTNER unit reaches
     * 0.98 g without crossing. The false half is deleted rather than reworded.
     * So the band clears a still or carried implement by a factor of about two
     * on the streams the decision actually reads, and by 2% on the one it does
     * not. `HoldReleaseFieldTest` asserts all four figures.
     */
    const val BAND_G = 1.0

    /**
     * How long the stream must stay inside [BAND_G] before a crossing can be
     * read as the release, in milliseconds.
     *
     * The guard against the START of the hold being read as its end. Taking
     * the load is a movement too, and on one committed capture it crosses the
     * band: field-38 set 18's pocket unit reads 5.34 g at 1.025 s after the
     * clock started -- the step off, at the beginning of a 32 s hang -- and is
     * back inside the band by 1.3 s. Without this, that crossing would record
     * a one-second hold for a hang the lifter held for half a minute, which is
     * the same silent loss on the progression metric this file exists to
     * remove, in the opposite direction and much larger.
     *
     * Three seconds is fitted to that one measured onset, not derived, and it
     * is the floor on how short a hold a false crossing can record. It also
     * bounds what this can find at all: a hold broken inside its first three
     * seconds has no sensor end and keeps the lifter's tap.
     */
    const val SETTLED_MS = 3_000L

    /**
     * How far a sample's acceleration magnitude is from one gravity, in g,
     * unsigned.
     *
     * Public because the pins compute the corpus's own profile with it: a test
     * that measured the margin with its own copy of this arithmetic would be
     * measuring a different quantity from the one [atMs] decides on.
     */
    fun deviationG(sample: ImuSample): Double =
        abs(sqrt(sample.axG * sample.axG + sample.ayG * sample.ayG + sample.azG * sample.azG) - 1.0)

    /**
     * The instant the hold was let go, or null where the stream carries no
     * release.
     *
     * The FIRST crossing of [BAND_G] that follows [SETTLED_MS] inside it,
     * counting from [clockStartedAtMs]. First rather than last, because the
     * question is when the hold ENDED: everything after the release -- the
     * walk, the phone coming out of the pocket -- is aftermath, and on a
     * pocket-mounted unit the last crossing is the phone being grabbed, which
     * is the tap instant this exists to get away from.
     *
     * [clockStartedAtMs] is `PrepWindow.workStartedAtMs`, the instant the
     * hold's own clock started, and null is the set whose clock start nothing
     * recorded. Null in, null out: without it there is no working window, the
     * prep's own movement is inside the stream, and a crossing there says
     * nothing about a hold.
     *
     * Samples before [clockStartedAtMs] are skipped rather than trusted to be
     * absent: the capture starts when recording starts, which on a timed set
     * is a whole prep earlier.
     *
     * Absence is null and not an instant. There is no defensible stand-in --
     * the end of the stream is the tap, which is the figure this replaces.
     */
    fun atMs(samples: List<ImuSample>, clockStartedAtMs: Long?): Long? {
        val openedAtMs: Long = clockStartedAtMs ?: return null
        var insideSinceMs: Long = openedAtMs
        for (sample in samples) {
            if (sample.timestampMs < openedAtMs) continue
            if (deviationG(sample) <= BAND_G) continue
            if (sample.timestampMs - insideSinceMs >= SETTLED_MS) return sample.timestampMs
            insideSinceMs = sample.timestampMs
        }
        return null
    }
}
