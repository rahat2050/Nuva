package com.nuva.assistant.command

import com.nuva.assistant.command.FailureClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart failure recovery classification (v1.4). */
class FailureClassifierTest {

    @Test
    fun `errors map to the right failure kind`() {
        assertEquals(Kind.PERMISSION_MISSING, FailureClassifier.classify("accessibility missing"))
        assertEquals(Kind.PERMISSION_MISSING, FailureClassifier.classify("notification access missing"))
        assertEquals(Kind.APP_UNAVAILABLE, FailureClassifier.classify("app not found"))
        assertEquals(Kind.APP_UNAVAILABLE, FailureClassifier.classify("whatsapp missing"))
        assertEquals(Kind.UI_CHANGED, FailureClassifier.classify("Message field khuje painai"))
        assertEquals(Kind.UI_CHANGED, FailureClassifier.classify("recipient verification failed"))
        assertEquals(Kind.NETWORK, FailureClassifier.classify("internet e pouchate parchi na"))
        assertEquals(Kind.TIMEOUT, FailureClassifier.classify("whatsapp package verify timeout"))
        assertEquals(Kind.UNSUPPORTED, FailureClassifier.classify("blocked: financial transaction automation (level 3)"))
        assertEquals(Kind.UNKNOWN, FailureClassifier.classify(""))
        assertEquals(Kind.UNKNOWN, FailureClassifier.classify(null))
    }

    @Test
    fun `only transient failures may be retried once`() {
        assertTrue(FailureClassifier.canSafeRetry(Kind.TIMEOUT))
        assertTrue(FailureClassifier.canSafeRetry(Kind.UI_CHANGED))
        assertFalse(FailureClassifier.canSafeRetry(Kind.PERMISSION_MISSING))
        assertFalse(FailureClassifier.canSafeRetry(Kind.UNSUPPORTED))
        assertFalse(FailureClassifier.canSafeRetry(Kind.NETWORK))
        assertFalse(FailureClassifier.canSafeRetry(Kind.APP_UNAVAILABLE))
    }

    @Test
    fun `every failure kind has bangla first user speech`() {
        Kind.entries.forEach { kind ->
            val speech = FailureClassifier.userSpeech(kind)
            assertTrue("speech for $kind must not be blank", speech.isNotBlank())
        }
        // Bangla-first copy for the key kinds (product spec §14).
        assertTrue(FailureClassifier.userSpeech(Kind.UI_CHANGED).contains("থেমলাম"))
        assertTrue(FailureClassifier.userSpeech(Kind.PERMISSION_MISSING).contains("permission"))
        assertTrue(FailureClassifier.userSpeech(Kind.APP_UNAVAILABLE).contains("Play Store"))
    }
}
