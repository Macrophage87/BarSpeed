package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a recovered zip's `header.json` says about which stream the figures
 * would have come from (#211).
 *
 * The header is written and closed before the first sample line of the set, so
 * it can only name the unit the set ARMED. Its recorded key was called
 * `analysedRole` anyway, and since #207 the analysed role is decided at the
 * END of a set: on a capture whose armed unit went silent, that key named the
 * file holding a header and no rows while the whole capture sat in the file
 * beside it. Nothing downstream was misled -- an orphan is offered back as a
 * zip or discarded and is never analysed -- and the entire cost is a person
 * opening the zip at the moment they are trying to salvage a lost set.
 *
 * This runs against a real filesystem and real zip bytes, as [SetJournalTest]
 * does. What it cannot establish is anything about Android, Room or a BLE
 * link: the streams here are written by the production journal from lists this
 * file builds, and no sensor produced them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrphanHeaderRoleTest {
    private val root: File = Files.createTempDirectory("orphan-header-role").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun TestScope.store() = SetJournalStore(root, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private fun header(armed: SensorRole?, roles: List<SensorRole>) = SetJournalHeader(
        exerciseId = "back_squat",
        exerciseName = "Back Squat",
        sessionId = null,
        sessionStartedAtMs = 900L,
        startedAtMs = 1_000L,
        orderIdx = 0,
        imuConnected = true,
        sensorRoles = roles,
        armedRole = armed,
    )

    /**
     * A run of frames the DSP could be pointed at, or one it could not.
     *
     * The bound is [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] rather than a
     * literal, so this file cannot drift from the estimator's own refusal.
     */
    private fun samples(count: Int): List<ImuSample> = (0 until count).map { i ->
        ImuSample(1_000L + i * 10L, 0.01, -0.02, 0.98, 1.5, -2.5, 0.25, 10.0, -20.0, 30.0)
    }

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun publishedText(orphan: OrphanedSet, store: SetJournalStore): String =
        entries(store.zip(orphan)).getValue(SetJournalStore.HEADER_FILE)

    private fun published(orphan: OrphanedSet, store: SetJournalStore): JsonObject =
        json.parseToJsonElement(publishedText(orphan, store)).jsonObject

    private fun text(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.content

    private fun recordedFile(orphan: OrphanedSet) = File(orphan.directory, SetJournalStore.HEADER_FILE)

    /**
     * The recorded file is untouched, whatever the zip publishes.
     *
     * The capture is not edited after the fact, and the key every directory
     * already on the phone carries is what a build older than this one derives
     * the second stream's role from. Publication is the only place the
     * correction is made.
     */
    @Test
    fun `the recorded header keeps the key it was always written with`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(SensorRole.A, listOf(SensorRole.A, SensorRole.B))))
        samples(2).forEach { journal.appendImu(it) }
        journal.sync()

        val recorded = File(journal.directory, SetJournalStore.HEADER_FILE).readText()
        assertTrue("\"analysedRole\"" in recorded, "the recorded key moved: $recorded")
        assertFalse("\"armedRole\"" in recorded, "the recorded header gained a published-only key: $recorded")
    }

    /**
     * A one-sensor capture publishes exactly what it recorded.
     *
     * No role is in play, so there is nothing to rename and nothing to derive.
     * Byte-identical rather than merely equivalent, because the ordinary set
     * is the one this change must not move at all.
     */
    @Test
    fun `a one-sensor capture publishes the recorded header unchanged`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(armed = null, roles = emptyList())))
        samples(2).forEach { journal.appendImu(it) }
        journal.sync()

        val orphan = store.orphans().single()
        assertEquals(recordedFile(orphan).readText(), publishedText(orphan, store))
        assertNull(text(published(orphan, store), "analysedRole"), "a roleless capture named an analysed role")
        assertNull(text(published(orphan, store), "armedRole"), "a roleless capture named an armed role")
    }

    /**
     * A dual capture whose armed unit delivered publishes the armed role under
     * both names, and no fallback flag.
     *
     * `armedRole` is the honest name for what was recorded; `analysedRole` is
     * derived, and on this capture the derivation agrees with it. The flag is
     * absent rather than false, for the reason
     * `RecordedSensors.analysedFellBack` is absent from an unremarkable row.
     */
    @Test
    fun `a dual capture that kept its armed stream publishes one role under both names`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(SensorRole.A, listOf(SensorRole.A, SensorRole.B))))
        val enough = SensorCapturePolicy.MIN_ANALYSABLE_FRAMES
        samples(enough).forEach { journal.appendImu(it) }
        samples(enough).forEach { journal.appendSecondaryImu(it, SensorRole.B) }
        journal.sync()

        val orphan = store.orphans().single()
        assertEquals(SensorRole.A, orphan.analysedRole, "the recovered capture names the wrong analysed role")
        assertFalse(orphan.analysedFellBack, "a capture that kept its armed stream is flagged as fallen back")
        val doc = published(orphan, store)
        assertEquals("A", text(doc, "armedRole"), "the published header does not name the armed unit")
        assertEquals("A", text(doc, "analysedRole"), "the published header does not name the analysed unit")
        assertNull(doc["analysedFellBack"], "a capture that never fell back published the flag: $doc")
    }

    /**
     * One key is renamed, the derived one is appended, and nothing else moves.
     *
     * Including a key written by a build this one has never heard of: the
     * published document is a transform of the parsed TEXT rather than a
     * re-encoding of the decoded header, so an additive key from a future
     * build survives into the zip instead of being dropped by a decoder
     * configured to ignore it.
     */
    @Test
    fun `publication renames one key, appends the derived one and keeps the rest in order`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(SensorRole.A, listOf(SensorRole.A, SensorRole.B))))
        samples(2).forEach { journal.appendImu(it) }
        journal.sync()
        val file = File(journal.directory, SetJournalStore.HEADER_FILE)
        file.writeText(file.readText().removeSuffix("}") + ",\"aKeyFromTheFuture\":\"kept\"}")

        val orphan = store.orphans().single()
        val recordedKeys =
            json.parseToJsonElement(file.readText()).jsonObject.keys
                .map { if (it == SetJournalStore.RECORDED_ROLE_KEY) SetJournalStore.ARMED_ROLE_KEY else it }
        val doc = published(orphan, store)
        assertEquals(recordedKeys + SetJournalStore.RECORDED_ROLE_KEY, doc.keys.toList())
        assertEquals("kept", text(doc, "aKeyFromTheFuture"), "an unknown key was dropped: $doc")
        assertEquals("back_squat", text(doc, "exerciseId"), "an untouched key changed: $doc")
    }

    /**
     * A header that will not parse is copied through rather than dropped.
     *
     * `read` refuses such a directory outright, so this only reaches the zip
     * for a file damaged between the walk and the export -- but the direction
     * of the failure is the point. Some of the capture is worth more than none
     * of it, and losing the identity file over a publication step would be a
     * strictly worse outcome than publishing a name that is wrong.
     */
    @Test
    fun `an unparseable header is copied through untouched`() = runTest {
        val store = store()
        val journal = requireNotNull(store.open(header(SensorRole.A, listOf(SensorRole.A, SensorRole.B))))
        samples(2).forEach { journal.appendImu(it) }
        journal.sync()

        val orphan = store.orphans().single()
        recordedFile(orphan).writeText("{not json at all")
        assertEquals("{not json at all", publishedText(orphan, store))
    }
}
