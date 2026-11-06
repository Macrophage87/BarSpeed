package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.JournalSetFacts
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RepCounter
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SetGeometryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What a set journal carries for #100: the facts a capture needs, from the
 * moment its set began, to be written back into History as a row, and the
 * prep window that bounds its reps.
 *
 * Against a real temporary directory, [SetJournalTest]'s way: [SetJournal]
 * holds no Android type, so this executes the production path. What it cannot
 * establish is [SetJournalTest]'s to state -- what survives a real kill is a
 * property of the device, and #42 stage 1's field check is what measures it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetJournalRecoveryFactsTest {
    private val root: File = Files.createTempDirectory("set-journal-facts").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun TestScope.store() = SetJournalStore(root, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private val squat = ExerciseDef(id = "back_squat", displayName = "Back Squat")

    private val facts =
        JournalSetFacts(
            exercise = squat,
            geometry = SetGeometryPolicy.describe(squat, null),
            stackRuleApplied = false,
            loadKg = 102.5,
            bodyWeightKg = null,
            timed = false,
            prepCase = PrepCase.CUED,
            armedSensors = RecordedSensors(count = 2, expected = listOf(SensorRole.A, SensorRole.B), SensorRole.A),
            counter = RepCounter.METRONOME,
            tempo = "4011",
            warmup = true,
            added = true,
            plannedSide = "left",
            plannedPrepS = 10,
            prepS = 8,
        )

    private fun header(facts: JournalSetFacts?) = SetJournalHeader(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        sessionId = 7L,
        sessionStartedAtMs = 900L,
        startedAtMs = 1_000L,
        orderIdx = 2,
        imuConnected = true,
        facts = facts,
    )

    @Test
    fun `the facts a recovery needs come back exactly as the set began with them`() = runTest {
        val store = store()
        store.open(header(facts))!!.close()
        assertEquals(facts, store.orphans().single().header.facts)
    }

    /**
     * A header an earlier build wrote has no facts, and it must still be
     * offered back -- as a zip or a discard, #42 stage 1 in full. A field the
     * reader required would drop the whole directory instead.
     */
    @Test
    fun `a header written before the facts existed is still offered, with none`() = runTest {
        val dir = File(root, "s900/set0-1000").apply { mkdirs() }
        File(dir, SetJournalStore.HEADER_FILE).writeText(
            """{"journalVersion":1,"exerciseId":"back_squat","exerciseName":"Back Squat","sessionId":null,""" +
                """"sessionStartedAtMs":900,"startedAtMs":1000,"orderIdx":0,"imuConnected":true}""",
        )
        assertNull(store().orphans().single().header.facts)
    }

    /**
     * The window is the stored `prep_window` stream's own format, so the one
     * decoder reads it back and a recovered set is bounded exactly as its own
     * write would have bounded it.
     */
    @Test
    fun `the prep window is journaled in the stored stream's format`() = runTest {
        val store = store()
        val journal = store.open(header(facts))!!
        journal.appendPrepWindow(PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 4_250L))
        journal.close()
        val text = File(journal.directory, SetJournal.PREP).readText()
        assertEquals(PrepWindowCsv.encode(PrepWindow(1_000L, 4_250L)), text)
        assertEquals(PrepWindow(1_000L, 4_250L), PrepWindowCsv.decode(text))
    }

    @Test
    fun `a set that ran no prep journals no prep window`() = runTest {
        val store = store()
        val journal = store.open(header(facts.copy(prepCase = PrepCase.NONE)))!!
        journal.close()
        assertFalse(File(journal.directory, SetJournal.PREP).exists())
    }
}
