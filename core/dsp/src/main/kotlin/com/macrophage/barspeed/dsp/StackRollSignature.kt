package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.StackMountSignal
import kotlin.math.abs

/**
 * Whether one unit's stream looks like a unit riding a weight stack, from its
 * roll alone. Issue #278.
 *
 * ## Why a measurement exists at all
 *
 * No per-role mount is declared anywhere. `LiftDirection.mountSpecific` reads
 * `sensorOnStack`, `sensorInverted` and `travelRatio`, and all three come from
 * the declaration the ARMED unit carried, so on a two-unit set the second
 * unit's mount is inferred from the exercise. The owner's rule is what makes a
 * measurement possible: *"For the pushdown and pulldown, the weight stacks
 * shouldn't have a lot of roll."* (owner, 2026-09-12). Both units are
 * magnet-mounted and identical to look at.
 *
 * ## The rule
 *
 * Over the working window -- [RollExcursion]'s own window, not a second one --
 * a unit is [StackMountSignal.ON_STACK] when its unwrapped roll RANGE stays
 * under [MAX_STACK_ROLL_DEG] and no sample's roll RATE exceeds
 * [MAX_STACK_ROLL_RATE_DPS]. Otherwise it is [StackMountSignal.NOT_ON_STACK].
 * A window holding fewer than [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]
 * samples is [StackMountSignal.UNMEASURED] -- absence, not a flat reading.
 *
 * THE RANGE IS [RollExcursion]'s, unwrapped across the +-180 discontinuity and
 * taken over the same interval the archive's `rollExcursion_deg` covers. That
 * is deliberate and load-bearing, and what it buys is a CHECK of the roll half
 * rather than a re-derivation of the verdict: only ONE of this rule's two
 * inputs is published anywhere -- no key in either document carries the peak
 * |wx| [MAX_STACK_ROLL_RATE_DPS] is compared against -- and the roll figure
 * that is published is rounded to 0.1 degrees against a STRICT
 * [MAX_STACK_ROLL_DEG], so a published 3.0 can sit on either side of the
 * bound. Re-deriving this verdict means decoding the archive's own imu CSV.
 * Both of that type's stated faults are inherited with it -- the wrap
 * assumption and roll's ill-conditioning as pitch approaches +-90 degrees.
 *
 * ## Provenance of the two numbers
 *
 * Measured over the window [RollExcursion] reports for each stream -- cue-bounded
 * on thirty-two of the thirty-six, and `fromWorkStart` on field-41 sets 20 and
 * 21, the two rope dead hangs, where no terminal cue bounded it -- on every
 * two-unit set in field-41, field-42 and field-43 that DECLARED `sensorOnStack`
 * -- eighteen sets, thirty-six streams -- by recomputing each stream's unwrapped roll range
 * and maximum |wx| from its CSV. Every figure agreed with the
 * `rollExcursion_deg` that session's own `meta.json` published for that role,
 * to the one decimal place the document carries.
 *
 * The two populations do not overlap and the gap is wide:
 *
 * - Units the owner's account puts on the stack, or which sat still by any
 *   reading: 0.148 to 1.620 degrees. The largest is field-43 set 9 role `a`, a
 *   seated leg curl with BOTH units on the stack.
 * - Units that were held, clipped to a handle, or otherwise moved with the
 *   lifter: 4.977 degrees at the smallest -- field-42 set 11 role `a`, an
 *   assisted pull-up -- then 6.597 (field-41 set 18, lat pulldown), 9.333,
 *   11.827, 12.931, and on up to 210.608.
 *
 * [MAX_STACK_ROLL_DEG] sits between them: 1.85 times the largest still figure
 * and 1.66 times below the smallest moving one. IT IS A FITTED BOUND, not a
 * derived one -- the *measured, not designed* class -- so a capture nobody has
 * taken yet can land between 1.620 and 4.977 and be called wrongly. The
 * failure direction is what makes that survivable: a unit wrongly called
 * [StackMountSignal.NOT_ON_STACK] leaves [com.macrophage.barspeed.model.AnalysedRolePolicy]
 * with no single stack candidate, and the analysed role does not move.
 *
 * SINCE #323 THAT IS NOT THE ONLY CONSEQUENCE, and the second is not
 * survivable in the same way. On a set whose inversion came from
 * `SetGeometryPolicy.stackInversion`'s rule alone, an ANALYSED stack unit
 * wrongly called [StackMountSignal.NOT_ON_STACK] has that inversion taken back
 * by `SetGeometryPolicy.analysedUnder` and is read with drive and return
 * swapped: field-41 set 16's stack stream reads 1 of 14 that way.
 *
 * [MAX_STACK_ROLL_RATE_DPS] DISQUALIFIES NOTHING IN THIS CORPUS and is stated
 * as a guard rather than as a discriminator. Across those thirty-six streams
 * the highest |wx| on a stream this rule calls ON_STACK is 7.57 degrees/s
 * (field-43 set 10 role `b`), while streams it calls NOT_ON_STACK run from 4.46
 * upward -- so the rate separates the two populations not at all, and the roll
 * range does all the work. What it is for is the case the range cannot see: the
 * pole where the sensor's fused roll estimate stops moving while the unit is
 * being turned, which [RollExcursion] records having already met on
 * `field-backsquat-wrapping-s36-set01`. A partner unit reading a flat roll
 * while its gyro says it is being spun is the only way this rule can move the
 * analysis onto a unit that was NOT on the stack, and this is the term that
 * refuses it.
 *
 * ## What is not claimed
 *
 * `roll_deg` and `wx_dps` are the sensor's own outputs. This says what the
 * STREAM did; it does not observe a magnet, a stack or a lifter, and no device
 * has been run against this code. `wx_dps` is read as the rate about the axis
 * whose angle `roll_deg` reports, which is the WitMotion convention the
 * columns are written in and is not verified against hardware here -- the rule
 * does not depend on it, because a unit riding a stack is quiet on every axis
 * and any one of them serves as the guard.
 *
 * Nothing here consults the DECLARATION. A unit left still on a bench through
 * a set of curls reads ON_STACK exactly as a unit on a stack does, which is
 * why the only caller asks this question solely on a set that has already
 * declared a stack mount.
 */
object StackRollSignature {
    /**
     * The roll range, in degrees, a stack-mounted unit is expected to stay
     * under over its working window. See the class KDoc for the thirty-six
     * streams this is drawn from.
     */
    const val MAX_STACK_ROLL_DEG = 3.0

    /**
     * The roll rate, in degrees/second, no sample of a stack-mounted unit may
     * exceed. A guard against a flat roll reading on a unit that is moving,
     * not a discriminator; see the class KDoc.
     */
    const val MAX_STACK_ROLL_RATE_DPS = 25.0

    /**
     * What [samples] say about this unit's mount over the working window
     * [workStartedAtMs]`..`[end] bounds.
     *
     * [workStartedAtMs] is `PrepWindow.workStartedAtMs` or null where the set
     * stored no window, and [end] is [SetEnd.of] over the set's cue track and
     * its prescription -- the same two bounds [RollExcursion.of] takes, read
     * through the same function so the interval cannot drift between the
     * figure and the verdict.
     *
     * A set with neither bound is judged over its whole capture, which is what
     * [RollExcursion.Basis.WHOLE_CAPTURE] already means: that window includes
     * the walk-up and the re-rack, so a stack unit still reads quiet and a
     * handled unit reads moved all the more. The looser window can only refuse
     * a stack candidate, never invent one.
     */
    fun of(samples: List<ImuSample>, workStartedAtMs: Long?, end: SetEnd): StackMountSignal {
        val windowed = RollExcursion.inWindow(samples, workStartedAtMs, end)
        if (windowed.size < SensorCapturePolicy.MIN_ANALYSABLE_FRAMES) return StackMountSignal.UNMEASURED
        val unwrapped = RollExcursion.unwrap(windowed.map { it.rollDeg })
        val range = unwrapped.max() - unwrapped.min()
        val peakRate = windowed.maxOf { abs(it.wxDps) }
        return if (range < MAX_STACK_ROLL_DEG && peakRate <= MAX_STACK_ROLL_RATE_DPS) {
            StackMountSignal.ON_STACK
        } else {
            StackMountSignal.NOT_ON_STACK
        }
    }
}
