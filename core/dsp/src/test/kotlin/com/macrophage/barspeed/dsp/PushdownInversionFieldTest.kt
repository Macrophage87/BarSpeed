package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * source (#289), and the draft plan kept for that session omits it on both
 * cable lifts. A plan that declared it false would resolve false and read 1,
 * and is left alone.
 *
 * ## What this asserts
 *
 * The count the plan's own geometry produces from the stack unit, against the
 * 14 performed. Before #317 that geometry was uninverted and read 1.
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
}
