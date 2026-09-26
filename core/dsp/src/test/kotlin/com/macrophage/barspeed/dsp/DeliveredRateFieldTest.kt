package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DeliveredRate] on the seventeen committed two-unit captures, issue #321.
 *
 * DIFFERENTIALS. At the commit that adds this file [DeliveredRate.of] answers
 * null for every stream, so every case here fails. The fix is the commit after
 * it.
 *
 * THE PAIRS are every `-imu-b.csv` in this module's resources beside its base
 * capture. Each base capture is role a and each `-imu-b.csv` role b, the
 * convention `StackMountFieldTest` states for seven of them. Where a pair has a
 * `-prep.csv`, its work start opens the window. Where it has a `-cues.csv`,
 * its terminal cue closes it, read with `cadenceGuided` true as for the
 * guided sets these are. A missing bound falls back to the stream's own first
 * or last row.
 *
 * WHAT THE PINS ARE. The exact figures were computed this round by a read-only
 * Python pass over the same files and the same definition, rows inside the
 * window over the window's length. They are the arithmetic over these
 * captures. They are not a claim about what any sensor sampled: the rows carry
 * arrival stamps, and a frame lost on the link cannot be seen in them.
 *
 * NOT A THRESHOLD. `the corpus reads two clusters` describes the captures as
 * committed: every unit at 99.1 to 99.6 except field-42's role a at 43.5 to
 * 44.5. It is not a boundary the app applies. Nothing in the app judges these
 * figures, and no capture here places a boundary anywhere in the gap.
 */
class DeliveredRateFieldTest {
    private fun resource(name: String) = javaClass.getResource("/$name")

    private fun load(name: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name.csv")!!.readBytes().decodeToString())

    /** The set's own `-prep.csv` work-start instant, or null where it has none. */
    private fun workStart(base: String): Long? = resource("$base-prep.csv")?.readText()
        ?.trim()?.lines()?.get(1)?.split(",")?.get(1)?.trim()?.toLong()

    private fun end(base: String): SetEnd = if (resource("$base-cues.csv") == null) {
        SetEnd.NotCued
    } else {
        SetEnd.of(CueTrack.read(base).map { VoiceCue(it.timestampMs, it.label) }, cadenceGuided = true)
    }

    private fun measured(base: String, role: String): DeliveredRate.Measured = assertNotNull(
        DeliveredRate.of(load(if (role == "a") base else "$base-imu-b"), workStart(base), end(base)),
        "$base role $role stated no delivered rate",
    )

    private fun round1(value: Double) = Math.round(value * 10.0) / 10.0

    private val pairs =
        listOf(
            "field-cablerow-3010-8rep-s42-set08",
            "field-cablerow-3010-8rep-s42-set09",
            "field-cablerow-3010-8rep-s42-set10",
            "field-deadlift-straight-2rep-s44-set05",
            "field-deadlift-straight-4rep-s44-set04",
            "field-deadlift-straight-5rep-s43-set04",
            "field-deadlift-straight-5rep-s43-set05",
            "field-deadlift-straight-5rep-s43-set06",
            "field-deadlift-straight-5rep-s44-set01",
            "field-deadlift-straight-5rep-s44-set02",
            "field-deadlift-straight-5rep-s44-set03",
            "field-latpulldown-1120-12rep-s38-set14",
            "field-latpulldown-1120-12rep-s41-set18",
            "field-pullup-3010-8rep-s42-set11",
            "field-pullup-4010-8rep-s42-set13",
            "field-pushdown-1120-14rep-s41-set16",
            "field-ropedeadhang-hold45-s38-set18",
        )

    /**
     * field-42's assisted pull-up, set 11: role a's link delivered 1,392 rows
     * in a 32,032 ms window, in four-frame notifications 90 ms apart, while
     * role b delivered 3,184 on a 31 ms rhythm.
     */
    @Test
    fun `field-42's slow link reads about 44 and its partner about 99`() {
        val base = "field-pullup-3010-8rep-s42-set11"
        val slow = measured(base, "a")
        val partner = measured(base, "b")
        assertEquals(43.45654345654346, slow.hz, 1e-9)
        assertEquals(90L, slow.burstSpacingMs)
        assertEquals(99.4005994005994, partner.hz, 1e-9)
        assertEquals(31L, partner.burstSpacingMs)
    }

    /** field-42's seated cable row, set 8: the same shape on another exercise. */
    @Test
    fun `field-42's cable row reads the same shape`() {
        val base = "field-cablerow-3010-8rep-s42-set08"
        assertEquals(44.33066933066933, measured(base, "a").hz, 1e-9)
        assertEquals(99.27572427572427, measured(base, "b").hz, 1e-9)
    }

    /**
     * A pair with neither a prep window nor a cue track: field-44's deadlift,
     * set 1, measured over each stream's own span.
     */
    @Test
    fun `a pair with no bounds reads over its own span`() {
        val base = "field-deadlift-straight-5rep-s44-set01"
        assertEquals(99.46409431939979, measured(base, "a").hz, 1e-9)
        assertEquals(99.52742374337677, measured(base, "b").hz, 1e-9)
    }

    /**
     * A pair with both bounds whose two links are healthy: field-41's lat
     * pulldown, set 18. Both roles delivered 4,772 rows in a 48,051 ms window.
     */
    @Test
    fun `a healthy pair with both bounds reads about 99 on each role`() {
        val base = "field-latpulldown-1120-12rep-s41-set18"
        assertEquals(99.31114857130964, measured(base, "a").hz, 1e-9)
        assertEquals(99.31114857130964, measured(base, "b").hz, 1e-9)
    }

    /**
     * Every committed pair, as it reads: two clusters with nothing between.
     *
     * Every fixture this module holds with an `-imu-b.csv` partner is listed
     * above, so a pair added later without being listed is caught by the
     * count. Each rate is asserted at the one decimal the export publishes,
     * against the values these captures read, not a band wide enough to pass
     * anything.
     */
    @Test
    fun `the corpus reads two clusters`() {
        val onDisk =
            javaClass.getResource("/field-still-0rep.csv")!!.toURI().let { java.io.File(it).parentFile }
                .list()!!.count { it.endsWith("-imu-b.csv") }
        assertEquals(pairs.size, onDisk, "a two-unit capture was added without being measured here")
        for (base in pairs) {
            for (role in listOf("a", "b")) {
                val m = measured(base, role)
                val slow = role == "a" && "-s42-" in base
                if (slow) {
                    assertTrue(round1(m.hz) in listOf(43.5, 44.1, 44.3, 44.5), "$base $role read ${m.hz}")
                    assertEquals(90L, m.burstSpacingMs, "$base $role")
                } else {
                    assertTrue(round1(m.hz) in 99.1..99.6, "$base $role read ${m.hz}")
                    assertTrue((m.burstSpacingMs ?: -1L) in 30L..32L, "$base $role spaced ${m.burstSpacingMs}")
                }
            }
        }
    }
}
