package com.nuva.assistant.systemassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionProviderSelectorTest {

    private val own = "com.nuva.assistant"
    private val google = RecognitionProviderId("com.google.android.googlequicksearchbox", "Recognizer")
    private val samsung = RecognitionProviderId("com.samsung.android.bixby.agent", "Recognizer")
    private val nuva = RecognitionProviderId(own, "NuvaRecognitionService")

    @Test
    fun `never delegates recognition back to NUVA`() {
        assertNull(chooseExternalRecognitionProvider(listOf(nuva), own, nuva))
    }

    @Test
    fun `keeps an installed external preferred recognizer`() {
        val chosen = chooseExternalRecognitionProvider(
            listOf(nuva, google, samsung),
            own,
            samsung,
        )
        assertEquals(samsung, chosen)
    }

    @Test
    fun `uses deterministic external fallback when preference is NUVA`() {
        val chosen = chooseExternalRecognitionProvider(
            listOf(samsung, nuva, google, google),
            own,
            nuva,
        )
        assertEquals(google, chosen)
    }
}
