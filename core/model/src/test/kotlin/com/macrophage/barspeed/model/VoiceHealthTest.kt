package com.macrophage.barspeed.model

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX review 2026-11-06, voice F1 and F3: what the voice's health is, and the line the lifter reads about it. */
class VoiceHealthTest {
    private val missingData = -1
    private val notSupported = -2
    private val available = 0

    @Test
    fun `a failed init with no engine installed is no engine`() {
        assertEquals(VoiceStatus.NO_ENGINE, VoiceHealth.afterInit(false, false, Locale.UK) { available })
    }

    @Test
    fun `a failed init with an engine installed is a failure, not a missing engine`() {
        assertEquals(VoiceStatus.FAILED, VoiceHealth.afterInit(false, true, Locale.UK) { available })
    }

    @Test
    fun `a successful init proves an engine whatever the package query said`() {
        assertEquals(VoiceStatus.READY, VoiceHealth.afterInit(true, false, Locale.UK) { available })
    }

    @Test
    fun `the phone's own language is tried first and kept when it works`() {
        val tried = mutableListOf<Locale>()
        val status = VoiceHealth.afterInit(true, true, Locale.UK) {
            tried += it
            available
        }
        assertEquals(VoiceStatus.READY, status)
        assertEquals(listOf(Locale.UK), tried)
    }

    @Test
    fun `missing language data falls back to US English`() {
        val tried = mutableListOf<Locale>()
        val status = VoiceHealth.afterInit(true, true, Locale.GERMANY) {
            tried += it
            if (it == Locale.US) available else missingData
        }
        assertEquals(VoiceStatus.READY, status)
        assertEquals(listOf(Locale.GERMANY, Locale.US), tried)
    }

    @Test
    fun `no usable language at all is no language`() {
        assertEquals(VoiceStatus.NO_LANGUAGE, VoiceHealth.afterInit(true, true, Locale.GERMANY) { notSupported })
    }

    @Test
    fun `a phone already on US English is not asked twice`() {
        val tried = mutableListOf<Locale>()
        VoiceHealth.afterInit(true, true, Locale.US) {
            tried += it
            missingData
        }
        assertEquals(listOf(Locale.US), tried)
    }

    @Test
    fun `language results 0 to 2 are usable and negatives are not`() {
        assertTrue((0..2).all(VoiceHealth::languageUsable))
        assertFalse(VoiceHealth.languageUsable(missingData))
        assertFalse(VoiceHealth.languageUsable(notSupported))
    }

    @Test
    fun `a failed engine is retried once and only once`() {
        assertTrue(VoiceHealth.mayRetry(VoiceStatus.FAILED, retriesUsed = 0))
        assertFalse(VoiceHealth.mayRetry(VoiceStatus.FAILED, retriesUsed = 1))
    }

    @Test
    fun `only a failure is retried`() {
        for (status in VoiceStatus.entries - VoiceStatus.FAILED) {
            assertFalse(VoiceHealth.mayRetry(status, 0), "$status")
        }
    }

    @Test
    fun `service and not-installed errors mean the engine died, others are one word failing`() {
        assertTrue(VoiceHealth.engineDied(-4))
        assertTrue(VoiceHealth.engineDied(-9))
        assertFalse(VoiceHealth.engineDied(-1))
        assertFalse(VoiceHealth.engineDied(-5))
    }

    @Test
    fun `each broken engine says the voice is off and offers the speech settings`() {
        assertEquals(
            VoiceWarning("Voice is off: no speech engine", opensSpeechSettings = true),
            VoiceHealth.warning(VoiceStatus.NO_ENGINE, mediaVolumeZero = false, voiceWanted = true),
        )
        assertEquals(
            VoiceWarning("Voice is off: speech engine failed", opensSpeechSettings = true),
            VoiceHealth.warning(VoiceStatus.FAILED, mediaVolumeZero = false, voiceWanted = true),
        )
        assertEquals(
            VoiceWarning("Voice is off: no voice for this language", opensSpeechSettings = true),
            VoiceHealth.warning(VoiceStatus.NO_LANGUAGE, mediaVolumeZero = false, voiceWanted = true),
        )
    }

    @Test
    fun `a working engine at volume 0 says the voice is silent and offers no settings`() {
        assertEquals(
            VoiceWarning("Voice is silent: media volume is 0", opensSpeechSettings = false),
            VoiceHealth.warning(VoiceStatus.READY, mediaVolumeZero = true, voiceWanted = true),
        )
    }

    @Test
    fun `a broken engine outranks the volume`() {
        assertEquals(
            "Voice is off: no speech engine",
            VoiceHealth.warning(VoiceStatus.NO_ENGINE, mediaVolumeZero = true, voiceWanted = true)?.text,
        )
    }

    @Test
    fun `a healthy or still-starting engine at audible volume shows nothing`() {
        assertNull(VoiceHealth.warning(VoiceStatus.READY, mediaVolumeZero = false, voiceWanted = true))
        assertNull(VoiceHealth.warning(VoiceStatus.STARTING, mediaVolumeZero = false, voiceWanted = true))
    }

    @Test
    fun `nothing is shown when nothing would speak`() {
        for (status in VoiceStatus.entries) {
            assertNull(VoiceHealth.warning(status, mediaVolumeZero = true, voiceWanted = false), "$status")
        }
    }
}
