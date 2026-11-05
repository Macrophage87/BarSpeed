package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.UnitMotion
import com.macrophage.barspeed.model.armedCaptureOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every committed IMU capture says about the two facts
 * `SensorCapturePolicy.analysable` judges a unit on besides its frame count:
 * whether it moved (#287) and how long it ran beside its partner (#242).
 *
 * ## Moved
 *
 * One committed capture is of a unit nobody moved, `field-still-0rep`, and its
 * noisiest accelerometer axis deviates 0.0005 g. The quietest capture of a unit
 * that WAS moved is `field-ropedeadhang-hold20-s37-set11` at 0.0331 g, a dead
 * hang where only the lifter's sway moved it. [UnitMotion.STILL_ACCEL_SD_G] is
 * 0.01 g, between them.
 *
 * #287 says field-41 set 16's analysed unit "never moved": 0.45 dps of
 * rotation, 0.3 degrees of roll, 0.04 g of acceleration sigma over the set.
 * Its committed stream deviates 0.0374 g, 75 times the still capture, and
 * since #317 it counts 14 of its 14 reps (`PushdownInversionFieldTest`). It is
 * a stack unit: a stack slides without turning, which is why its gyro is
 * quiet, and the leg curl, leg press, face pull and cable-row stack units here
 * read 0.5 to 2.2 dps for the same reason. Nothing in this commit's rule may
 * fire on it, and the last test pins that through `armedCaptureOf`.
 *
 * ## Ran
 *
 * Every committed two-unit capture's two spans agree within 94 ms
 * (field-42 set 11, 60.84 s against 60.93 s). FRAME COUNTS DO NOT: field-42's
 * role a streamed at about 44 Hz beside role b's 99, so its five sets carry
 * 0.437 to 0.447 of the partner's frames over the same seconds. A rule judging
 * a unit by its share of the partner's FRAMES would have moved the analysis
 * off five healthy streams; the span is the quantity a unit that stopped
 * early changes and a slower unit does not.
 */
class UnitMotionFieldTest {
    private val resources = File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile

    private fun load(name: String): List<ImuSample> = ImuCsv.decode(File(resources, name).readText())

    /** Every committed IMU stream: not a cue track, a mark file or a prep window. */
    private val captures: List<String> =
        resources.list()!!.filter { it.startsWith("field-") && it.endsWith(".csv") }
            .filterNot { it.endsWith("-cues.csv") || it.endsWith("-reps.csv") || it.endsWith("-prep.csv") }
            .sorted()

    private val pairs: List<Pair<String, String>> =
        captures.filter { it.endsWith("-imu-b.csv") }.map { it.removeSuffix("-imu-b.csv") + ".csv" to it }

    @Test
    fun `the one still capture is still and every other capture moved`() {
        val still = captures.filter { UnitMotion.still(load(it)) == true }
        assertEquals(listOf("field-still-0rep.csv"), still, "still under the floor")
        val quietestMoved = captures.filterNot { it in still }.minOf { UnitMotion.accelSdG(load(it)) }
        assertEquals(0.0331, quietestMoved, 0.0001, "the quietest moved capture")
        assertEquals(0.0005, UnitMotion.accelSdG(load("field-still-0rep.csv")), 0.0001, "the still capture")
    }

    @Test
    fun `every committed pair ran for the same seconds whatever its frame counts`() {
        assertEquals(22, pairs.size, "committed two-unit captures")
        for ((a, b) in pairs) {
            val sa = UnitMotion.spanMs(load(a))!!
            val sb = UnitMotion.spanMs(load(b))!!
            assertTrue(kotlin.math.abs(sa - sb) <= 94L, "$a: spans $sa and $sb ms")
        }
        val frameShares = pairs.map { (a, b) -> load(a).size.toDouble() / load(b).size }
        assertEquals(5, frameShares.count { it < 0.5 }, "field-42's five 44 Hz role-a streams")
    }

    /** #287's unit, through the set-end path: both streams delivered, both moved, so role a is analysed. */
    @Test
    fun `field-41 set 16's stack unit keeps the analysis`() {
        val a = load("field-pushdown-1120-14rep-s41-set16.csv")
        val b = load("field-pushdown-1120-14rep-s41-set16-imu-b.csv")
        assertEquals(false, UnitMotion.still(a), "the stack unit moved")
        assertEquals(0.0374, UnitMotion.accelSdG(a), 0.0001, "the stack unit's deviation")
        val capture = armedCaptureOf(
            armed = RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A),
            secondaryRole = SensorRole.B,
            analysedBuffer = a,
            secondaryBuffer = b,
        )
        assertEquals(SensorRole.A, capture.sensors?.analysed)
        assertEquals(false, capture.sensors?.analysedFellBack)
        assertTrue(capture.samples === a, "the analysis is not on the stack unit's buffer")
    }

    /** A 44 Hz unit beside a 99 Hz partner over the same seconds keeps the analysis. */
    @Test
    fun `field-42's slower unit keeps the analysis`() {
        val a = load("field-cablerow-3010-8rep-s42-set08.csv")
        val b = load("field-cablerow-3010-8rep-s42-set08-imu-b.csv")
        val capture = armedCaptureOf(
            armed = RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), analysed = SensorRole.A),
            secondaryRole = SensorRole.B,
            analysedBuffer = a,
            secondaryBuffer = b,
        )
        assertEquals(SensorRole.A, capture.sensors?.analysed)
        assertEquals(false, capture.sensors?.analysedFellBack)
    }
}
