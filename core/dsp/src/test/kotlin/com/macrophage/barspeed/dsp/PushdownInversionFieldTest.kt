package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.AnalysedGeometry
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.StackMountSignal
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import com.macrophage.barspeed.model.armedCaptureOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Field-41 set 16's triceps pushdown, read from the unit on the weight stack
 * under the geometry its plan resolves to. Issue #317.
 *
 * ## The set
 *
 * `field-pushdown-1120-14rep-s41-set16` and its `-imu-b` partner, enrolled by
 * "Measure which unit of a two-unit set rode the stack". App 0.1.52, 13.61 kg,
 * 14 reps of 14, tempo `1120`. Role a sat on the stack (0.28 degrees of roll
 * over the working window) and is the unit #278 analyses; role b was on the
 * rope (23.588 degrees). Its `meta.json` records `startsWith` concentric,
 * `concentric` down, plane vertical, `sensorOnStack` true with source
 * `seeded`, and `sensorInverted` false.
 *
 * ## The declaration this reads
 *
 * The plan block as far as the archive can say it: `start` top, `concentric`
 * down, `plane` vertical, `sensorOnStack` omitted -- certain, because its
 * published source is `seeded` -- and `sensorInverted` omitted. That last is
 * the LIKELIER reading and not a recorded fact: the key has no published
 * source (#289). The same imported plan declared `sensorOnStack` and
 * `sensorInverted` on its lat pulldown and left this pushdown's
 * `sensorOnStack` to the seed; that is the likelier reading, not a recorded
 * fact. A plan that declared it false would resolve false and read 1, and is
 * left alone.
 *
 * ## What this asserts
 *
 * The count the plan's own geometry produces from the stack unit, against the
 * 14 performed. Before #317 that geometry was uninverted and read 1.
 *
 * ## Since #323, through the set-end path
 *
 * The rule's inversion is now taken back at the end of a set wherever the
 * ANALYSED unit's own roll does not say it rode the stack. The last two tests
 * drive that path -- `armedCaptureOf` with `StackRollSignature` over the set's
 * own working window, then `SetGeometryPolicy.analysedUnder` -- once with
 * both streams, where #278 keeps role a (0.28 degrees) and the inversion must
 * stand, and once with role b's stream ALONE, standing for a pushdown recorded
 * with one unit clipped to the rope under a plan naming neither key. Role b
 * really was on the rope in this set (23.588 degrees); a one-unit rope set is
 * the shape #323 names and has never been recorded.
 */
class PushdownInversionFieldTest {
    private val name = "field-pushdown-1120-14rep-s41-set16"

    private fun load(fixture: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$fixture.csv")!!.readBytes().decodeToString())

    private val cues = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    private val workAt: Long = javaClass.getResourceAsStream("/$name-prep.csv")!!
        .readBytes().decodeToString().trim().lines()[1].split(",")[1].trim().toLong()

    private val targets = SetTargets(plannedReps = 14, tempo = Tempo.parse("1120"), cadenceGuided = true)

    private val declared = PlanExerciseDef(
        exercise = "triceps_pushdown",
        start = "top",
        concentric = "down",
        plane = "vertical",
        sets = listOf(PlanSetDef(reps = 14, tempo = "1120")),
    )

    private val base = ExerciseDef(
        id = "triceps_pushdown",
        displayName = "triceps_pushdown",
        startsWith = ExerciseDef.inferStartPhase("triceps_pushdown"),
    )

    private val used = SetGeometryPolicy.resolve(base, declared)

    private val end = SetEnd.of(cues, cadenceGuided = targets.cadenceGuided)

    /**
     * What `RecordViewModel.endSet` does with a capture, over committed
     * streams: `captureAt`'s signature supplier, then `analysedAs`. [armed] is
     * null on a one-sensor set, whose one stream carries no role.
     */
    private fun atSetEnd(armed: RecordedSensors?, analysed: List<ImuSample>, partner: List<ImuSample>): SetEndRead {
        val capture = armedCaptureOf(
            armed = armed,
            secondaryRole = armed?.let { SensorRole.B },
            analysedBuffer = analysed,
            secondaryBuffer = partner,
            declaresStackMount = used.sensorOnStack,
            stackSignalOf = { StackRollSignature.of(it, workAt, end) },
        )
        val result = SetGeometryPolicy.analysedUnder(
            used = used,
            geometry = SetGeometryPolicy.describe(used, declared),
            stackRuleApplied = SetGeometryPolicy.stackRuleApplied(base, declared),
            analysedSignal = capture.analysedSignal,
        )
        return SetEndRead(result, capture.samples)
    }

    /** The definition the set-end path chose and the stream it pointed the analysis at. */
    private data class SetEndRead(val result: AnalysedGeometry, val samples: List<ImuSample>)

    private fun count(read: SetEndRead): Int = SetAnalyzer.analyze(
        read.samples,
        read.result.exercise.liftDirection(),
        targets = targets,
        cues = cues,
        workStartedAtMs = workAt,
    ).reps.size

    /**
     * RED before #317's rule: the stack rises as the handle is driven down.
     * The first four terms are what field-41 recorded and are green either
     * way; they are here so the red can only be the inversion.
     */
    @Test
    fun `the plan resolves field-41's stack mount and drive and reads the stack inverted`() {
        assertEquals(StartPhase.CONCENTRIC, used.startsWith)
        assertEquals(false, used.concentricUp)
        assertEquals(false, used.horizontal)
        assertTrue(used.sensorOnStack, "the stack mount the app ships for this id")
        assertEquals(true, used.sensorInverted)
    }

    /** RED before #317's rule, where the same stream read 1. */
    @Test
    fun `the stack unit reads the fourteen reps performed under the plan's own geometry`() {
        val analysis = SetAnalyzer.analyze(
            load(name),
            used.liftDirection(),
            targets = targets,
            cues = cues,
            workStartedAtMs = workAt,
        )
        assertEquals(14, analysis.reps.size, "against 14 performed; 1 before the rule")
    }

    /**
     * #278 keeps role a, whose roll says stack, so the rule's inversion stands
     * through the set-end path and the count is the one above. Green before
     * #323's fix and after: the fix must not take back an inversion from the
     * unit that rode the stack.
     */
    @Test
    fun `through the set-end path the stack unit keeps the inversion and reads fourteen`() {
        val armed = RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A)
        val stack = load(name)
        val read = atSetEnd(armed, stack, load("$name-imu-b"))
        assertSame(stack, read.samples, "the analysis left the unit whose roll says stack")
        assertEquals(StackMountSignal.ON_STACK, StackRollSignature.of(stack, workAt, end))
        assertEquals(true, read.result.exercise.sensorInverted)
        assertEquals(true, read.result.geometry.sensorInverted)
        assertEquals(14, count(read), "against 14 performed")
    }

    /**
     * RED before #323's fix. Role b's stream alone, as a one-sensor set whose
     * one unit was on the rope, under the same plan naming neither
     * `sensorOnStack` nor `sensorInverted`. Its roll says it moved, so the
     * rule's inversion is taken back and the stream is read the way it moved.
     * The count moves from 16 to 15 against 14 performed -- measured on this
     * stream both ways before the fix, and a small move, because what the
     * inversion swaps is which half of each rep is the drive; the count is
     * pinned so a fix that drops the flag without reaching the analysis
     * cannot pass.
     */
    @Test
    fun `a pushdown recorded with one unit on the rope under a plan naming neither key is read uninverted`() {
        val rope = load("$name-imu-b")
        val read = atSetEnd(null, rope, emptyList())
        assertSame(rope, read.samples)
        assertEquals(StackMountSignal.NOT_ON_STACK, StackRollSignature.of(rope, workAt, end))
        assertEquals(false, read.result.exercise.sensorInverted, "the rope unit is read with drive and return swapped")
        assertEquals(false, read.result.geometry.sensorInverted, "the row publishes an inversion the analysis dropped")
        assertEquals(15, count(read), "16 inverted, 15 as it moved, against 14 performed")
    }
}
