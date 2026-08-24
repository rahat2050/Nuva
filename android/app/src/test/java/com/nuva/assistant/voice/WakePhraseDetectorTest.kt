package com.nuva.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseDetectorTest {

    @Test
    fun `detects hey nuva and keeps inline command`() {
        val match = WakePhraseDetector.detect("Hey Nuva, WhatsApp open koro")
        assertEquals("Hey Nuva", match?.phrase)
        assertEquals("WhatsApp open koro", match?.commandAfterWake)
    }

    @Test
    fun `detects bangla wake phrase`() {
        val match = WakePhraseDetector.detect("হে নুভা ইউটিউব খোলো")
        assertTrue(match != null)
        assertEquals("ইউটিউব খোলো", match?.commandAfterWake)
    }

    @Test
    fun `does not wake on ordinary commands without the assistant name`() {
        assertFalse(WakePhraseDetector.containsWakePhrase("WhatsApp open koro"))
        assertNull(WakePhraseDetector.detect("YouTube e gaan chalao"))
    }

    @Test
    fun `accepts nuva at the start for banglish users`() {
        val match = WakePhraseDetector.detect("Nuva back jao")
        assertTrue(match != null)
        assertEquals("back jao", match?.commandAfterWake)
    }
}
